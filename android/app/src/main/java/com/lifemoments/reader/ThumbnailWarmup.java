package com.lifemoments.reader;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

/** Two resumable workers visit every indexed photo once, without evicting each other's results. */
final class ThumbnailWarmup implements AutoCloseable {
    private static final class Item {
        final JSONObject photo;int index,state,attempts;long retryAt;
        Item(JSONObject photo){this.photo=photo;}
    }
    private final CloudReader cloud;
    private final Runnable onProgress;
    private final ExecutorService executor=Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService notifications=Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> notification;
    private final List<Future<?>> requests=new ArrayList<>();
    private final Set<Thread> threads=ConcurrentHashMap.newKeySet();
    private final Object metadataBatchLock=new Object();
    private final List<Item> items=new ArrayList<>();
    private final Set<Integer> preparedBatches=new HashSet<>();
    private JSONObject currentDoc;
    private String sourceSession="",waitingReason="等待图库与百度连接";
    private boolean enabled,closed,limited;
    private int generation,cursor,cached,failed,inFlight;
    private long quietUntil,lastNotification;

    ThumbnailWarmup(CloudReader cloud,Runnable onProgress){this.cloud=cloud;this.onProgress=onProgress;}

    synchronized void replace(JSONObject doc){
        String session=cloud.session();if(currentDoc==doc&&sourceSession.equals(session))return;
        cancelLocked();currentDoc=doc;sourceSession=session;items.clear();preparedBatches.clear();cursor=0;cached=failed=inFlight=0;limited=false;
        JSONArray photos=doc==null?null:doc.optJSONArray("photos");
        if(photos!=null)for(int i=0;i<photos.length();i++){JSONObject photo=photos.optJSONObject(i);if(photo!=null)items.add(new Item(photo));}
        items.sort((a,b)->b.photo.optString("time").compareTo(a.photo.optString("time")));
        for(int i=0;i<items.size();i++)items.get(i).index=i;
        startLocked();notifyProgress();
    }
    synchronized void setEnabled(boolean value,String reason){
        waitingReason=reason==null?"":reason;if(closed||enabled==value)return;
        enabled=value;if(!enabled)cancelLocked();else startLocked();notifyProgress();
    }
    synchronized void prioritize(JSONObject photo){
        if(closed)return;quietUntil=System.currentTimeMillis()+2000;cancelLocked();startLocked();
    }
    synchronized void retryFailures(){
        if(closed)return;cancelLocked();failed=0;limited=false;preparedBatches.clear();cursor=0;
        for(Item item:items)if(item.state!=2){item.state=0;item.attempts=0;item.retryAt=0;}
        startLocked();notifyProgress();
    }
    synchronized JSONObject status(){
        String state=closed?"stopped":items.isEmpty()?"idle":limited?"limited":!enabled?"paused":cached+failed>=items.size()?"complete":"running";
        String text=state.equals("limited")?"缩略图缓存达到容量或可用空间上限":state.equals("paused")?waitingReason:
            state.equals("complete")?(failed==0?"全图库缩略图已缓存":"全图库扫描完成 · 部分照片暂不可用"):
            state.equals("running")?(cloud.retryAfterMillis()>0?"百度暂时频控，稍后自动继续缓存":"启动后缓存全图库缩略图"):"等待图库";
        try{return new JSONObject().put("state",state).put("text",text).put("total",items.size()).put("cached",cached).put("failed",failed).put("inFlight",inFlight).put("limitBytes",CloudReader.THUMBNAIL_LIMIT);}
        catch(Exception ignored){return new JSONObject();}
    }
    private void cancelLocked(){
        generation++;for(Future<?> request:requests)request.cancel(true);requests.clear();
        for(Thread thread:threads)cloud.cancelRequestsFor(thread);
        for(Item item:items)if(item.state==1)item.state=0;inFlight=0;
    }
    private void startLocked(){
        if(closed||!enabled||limited||items.isEmpty()||cached+failed>=items.size())return;
        int epoch=generation;for(int i=0;i<2;i++)requests.add(executor.submit(()->work(epoch)));
    }
    private synchronized Item next(int epoch)throws InterruptedException{
        while(!closed&&enabled&&!limited&&epoch==generation){
            long now=System.currentTimeMillis(),waitUntil=now+1000;
            long cooldown=cloud.retryAfterMillis();if(cooldown>0){wait(Math.min(1000,cooldown));continue;}
            if(now<quietUntil){wait(Math.min(1000,quietUntil-now));continue;}
            for(int n=0;n<items.size();n++){
                int index=cursor;cursor=(cursor+1)%items.size();Item item=items.get(index);
                if(item.state!=0)continue;
                if(item.retryAt>now){waitUntil=Math.min(waitUntil,item.retryAt);continue;}
                item.state=1;inFlight++;return item;
            }
            if(cached+failed>=items.size())return null;
            wait(Math.max(50,Math.min(1000,waitUntil-now)));
        }
        return null;
    }
    private void prepare(Item selected,int epoch)throws Exception{
        synchronized(metadataBatchLock){
            List<JSONObject> batch=new ArrayList<>();int number;
            synchronized(this){
                if(epoch!=generation||!enabled)throw new InterruptedException();
                number=selected.index/64;if(preparedBatches.contains(number))return;
                for(int i=number*64;i<Math.min(items.size(),(number+1)*64);i++){Item item=items.get(i);if(!cloud.hasThumbnail(item.photo))batch.add(item.photo);}
            }
            cloud.prepareThumbnailMetadata(batch);
            synchronized(this){if(epoch==generation)preparedBatches.add(number);}
        }
    }
    private void work(int epoch){
        Thread worker=Thread.currentThread();threads.add(worker);
        try{
            Item item;
            while((item=next(epoch))!=null){
                boolean success=false;Exception failure=null;
                try{if(!cloud.hasThumbnail(item.photo)){prepare(item,epoch);cloud.imageBackground(item.photo);}success=true;}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
                catch(Exception error){failure=error;}
                synchronized(this){
                    if(epoch!=generation||!enabled||closed)break;
                    inFlight=Math.max(0,inFlight-1);
                    if(success){item.state=2;cached++;}
                    else if(failure instanceof CloudReader.CacheFull){item.state=0;limited=true;}
                    else if(failure instanceof CloudReader.ImageUnavailable){item.state=3;failed++;}
                    else if(++item.attempts>=3){item.state=3;failed++;}
                    else{item.state=0;item.retryAt=System.currentTimeMillis()+Math.min(60000,15000L*item.attempts);}
                    notifyAll();
                }
                notifyProgress();if(limited)break;
                Thread.sleep(100);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        finally{threads.remove(worker);notifyProgress();}
    }
    private void notifyProgress(){
        synchronized(this){
            if(closed)return;long now=System.currentTimeMillis(),delay=1000-(now-lastNotification);
            if(delay>0){if(notification==null||notification.isDone())notification=notifications.schedule(()->{synchronized(this){notification=null;}notifyProgress();},delay,TimeUnit.MILLISECONDS);return;}
            if(notification!=null){notification.cancel(false);notification=null;}lastNotification=now;
        }
        if(onProgress!=null)try{onProgress.run();}catch(RuntimeException ignored){}
    }
    @Override public synchronized void close(){closed=true;enabled=false;cancelLocked();executor.shutdownNow();if(notification!=null)notification.cancel(false);notifications.shutdownNow();}
}
