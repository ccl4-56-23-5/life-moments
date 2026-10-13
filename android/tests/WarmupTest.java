package com.lifemoments.reader;

import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs the production scheduler on the JVM with an isolated, cancellable cloud fixture. */
public final class WarmupTest {
    private static int checks;
    private static final List<String> covered=new ArrayList<>();
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);covered.add(message);}
    private static JSONObject document(int count){JSONArray photos=new JSONArray();for(int i=0;i<count;i++)photos.put(new JSONObject().put("id",i).put("cloudPath","/fixture/"+i+".jpg").put("time",String.format("2026-10-%02d",i+1)));return new JSONObject().put("photos",photos);}
    private static void await(java.util.function.BooleanSupplier predicate,int seconds)throws Exception{long end=System.currentTimeMillis()+seconds*1000L;while(!predicate.getAsBoolean()){if(System.currentTimeMillis()>end)throw new AssertionError("Fixture timed out");Thread.sleep(30);}}
    public static void main(String[] args)throws Exception{
        CloudReader cloud=new CloudReader();cloud.delay=300;
        ThumbnailWarmup warm=new ThumbnailWarmup(cloud,()->{});JSONObject doc=document(10);warm.replace(doc);Thread.sleep(200);
        check(cloud.downloads.get()==0,"Paused startup never accesses cloud");warm.setEnabled(true,"");await(()->cloud.active.get()>0,3);
        warm.setEnabled(false,"屏幕已锁定");Thread.sleep(450);check(warm.status().optInt("failed")==0,"Lock cancellation is not a failed photo");
        check(warm.status().optString("state").equals("paused"),"Lock has explicit pause state");
        warm.setEnabled(true,"");await(()->warm.status().optString("state").equals("complete"),6);
        check(warm.status().optInt("cached")==10,"Unlock resumes every indexed thumbnail");check(cloud.maximum.get()<=2,"Global cache uses at most two downloads");warm.close();
        int downloaded=cloud.downloads.get();ThumbnailWarmup restarted=new ThumbnailWarmup(cloud,()->{});restarted.replace(doc);restarted.setEnabled(true,"");await(()->restarted.status().optString("state").equals("complete"),3);
        check(cloud.downloads.get()==downloaded,"Restart counts persistent hits without downloading again");restarted.close();

        CloudReader failing=new CloudReader();failing.permanent.add(7);failing.transientOnce.add(5);
        List<String> notifications=Collections.synchronizedList(new ArrayList<>());ThumbnailWarmup[] holder=new ThumbnailWarmup[1];
        holder[0]=new ThumbnailWarmup(failing,()->{if(holder[0]!=null)notifications.add(holder[0].status().optString("state"));});holder[0].replace(doc);holder[0].setEnabled(true,"");
        await(()->holder[0].status().optString("state").equals("complete"),22);await(()->notifications.contains("complete"),3);
        check(holder[0].status().optInt("cached")==9&&holder[0].status().optInt("failed")==1,"Missing file does not block the rest of a batch");
        check(failing.attempts.get(5).get()==2,"Transient connection failure retries successfully");
        failing.permanent.clear();holder[0].retryFailures();await(()->holder[0].status().optInt("cached")==10,3);check(holder[0].status().optInt("failed")==0,"Explicit retry repairs previous missing photo");holder[0].close();

        CloudReader limited=new CloudReader();limited.maximumCache=3;ThumbnailWarmup budget=new ThumbnailWarmup(limited,()->{});budget.replace(doc);budget.setEnabled(true,"");await(()->budget.status().optString("state").equals("limited"),3);int requests=limited.downloads.get();Thread.sleep(1200);
        check(limited.cached.size()==3,"Capacity retains existing cached thumbnails");check(limited.downloads.get()==requests,"Capacity limit does not continuously download and evict");budget.close();
        JSONObject report=new JSONObject().put("status","PASS").put("checks",checks).put("execution","JVM production ThumbnailWarmup with isolated cancellable CloudReader fixture").put("realPhone",false).put("realBaiduRequests",false).put("covered",covered);
        if(args.length>0)java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]),report.toString(2),java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("PASS: "+checks+" global thumbnail scheduler checks");
    }
}

/** Compiled only by the isolated JVM test, never included in the app. */
final class CloudReader {
    static final long THUMBNAIL_LIMIT=1024L*1024*1024;
    static final class CacheFull extends IOException{}
    static final class ImageUnavailable extends IOException{}
    final Set<Integer> cached=ConcurrentHashMap.newKeySet(),permanent=ConcurrentHashMap.newKeySet(),transientOnce=ConcurrentHashMap.newKeySet();
    final Map<Integer,AtomicInteger> attempts=new ConcurrentHashMap<>();
    final AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger(),downloads=new AtomicInteger();
    volatile int delay=20,maximumCache=Integer.MAX_VALUE;
    String session(){return "fixture";}
    long retryAfterMillis(){return 0;}
    boolean hasThumbnail(JSONObject photo){return cached.contains(photo.optInt("id"));}
    void prepareThumbnailMetadata(List<JSONObject> photos)throws Exception{if(Thread.currentThread().isInterrupted())throw new InterruptedException();}
    void cancelRequestsFor(Thread thread){thread.interrupt();}
    File imageBackground(JSONObject photo)throws Exception{
        int id=photo.optInt("id");downloads.incrementAndGet();int concurrent=active.incrementAndGet();maximum.accumulateAndGet(concurrent,Math::max);
        try{Thread.sleep(delay);int attempt=attempts.computeIfAbsent(id,key->new AtomicInteger()).incrementAndGet();
            if(permanent.contains(id))throw new ImageUnavailable();if(transientOnce.contains(id)&&attempt==1)throw new IOException("Fixture reconnects");
            synchronized(cached){if(cached.size()>=maximumCache)throw new CacheFull();cached.add(id);}return new File("fixture.jpg");
        }finally{active.decrementAndGet();}
    }
}
