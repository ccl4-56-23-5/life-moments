package com.lifemoments.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.ColorSpace;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only Baidu client. It deliberately has no upload, delete, move or edit methods. */
final class CloudReader {
    static final class ImageUnavailable extends IOException {
        final String code;
        ImageUnavailable(String code,String message){super(message);this.code=code;}
    }
    static final class CacheFull extends IOException {
        CacheFull(){super("缩略图缓存已到容量或剩余空间上限；已缓存的照片仍可浏览。");}
    }
    private static final class DownloadFailure extends IOException {
        final int status;DownloadFailure(int status){super("照片下载失败（HTTP "+status+"）。");this.status=status;}
    }
    interface Reporter { void report(String value); }
    interface Browser { void open(String url); }
    private final CredentialVault vault;
    private final File cache;
    private final File thumbnailCache;
    private JSONObject credentials;
    private final Map<String, JSONObject> metas=new ConcurrentHashMap<>();
    private final Map<String, Directory> directories=new ConcurrentHashMap<>();
    private final Object[] directoryLocks=locks(),metadataLocks=locks(),imageLocks=locks();
    private final Semaphore thumbnails=new Semaphore(2,true), originals=new Semaphore(1,true);
    private final AtomicInteger foregroundThumbnails=new AtomicInteger();
    private final Object diskLock=new Object();
    private final Map<Thread,HttpURLConnection> activeConnections=new ConcurrentHashMap<>();
    private final AtomicInteger imageEpoch=new AtomicInteger();
    private final ThreadLocal<Integer> requestEpoch=new ThreadLocal<>();
    private volatile Thread previewThread;
    private final Object tokenLock=new Object();
    private volatile long blockedUntil=0;
    private static final long CACHE_LIMIT=512L*1024*1024;
    static final long THUMBNAIL_LIMIT=1024L*1024*1024;
    private static final long SPACE_RESERVE=128L*1024*1024;
    private long thumbnailBytes,legacyCacheBytes;
    private static Object[] locks(){Object[] values=new Object[64];for(int i=0;i<values.length;i++)values[i]=new Object();return values;}
    private static Object lock(Object[] values,String key){return values[(key.hashCode()&Integer.MAX_VALUE)%values.length];}
    private static final class Directory {
        final Map<String,JSONObject> files;final long expires;
        Directory(Map<String,JSONObject> files){this.files=files;expires=System.currentTimeMillis()+15*60000L;}
    }
    CloudReader(Context context) throws Exception {
        vault=new CredentialVault(context); credentials=vault.read();
        cache=new File(context.getCacheDir(), "photos-v1");
        if (!cache.exists() && !cache.mkdirs()) throw new IOException("无法创建照片缓存。");
        thumbnailCache=new File(context.getFilesDir(),"thumbnails-v2");
        if(!thumbnailCache.exists()&&!thumbnailCache.mkdirs())throw new IOException("无法创建缩略图缓存。");
        File[] legacy=cache.listFiles();
        if(legacy!=null)for(File file:legacy)if(file.getName().endsWith("-thumb.jpg")){
            File target=new File(thumbnailCache,file.getName());if(target.exists())file.delete();else file.renameTo(target);
        } else if(file.getName().endsWith("-thumb.jpg.part")||file.getName().endsWith("-display.jpg")||file.getName().endsWith("-display.jpg.part")||file.getName().endsWith("-original.bin")||file.getName().endsWith("-original.bin.part"))file.delete();
        File[] incomplete=thumbnailCache.listFiles();if(incomplete!=null)for(File file:incomplete)if(file.getName().endsWith(".part"))file.delete();
        thumbnailBytes=directoryBytes(thumbnailCache);legacyCacheBytes=directoryBytes(cache);
    }
    synchronized boolean connected() { return credentials.has("accessToken"); }
    synchronized String folder() { return credentials.optString("folder", ""); }
    synchronized String root() { return credentials.optString("root", ""); }
    synchronized String session() { return credentials.optString("session", ""); }
    // Native connection dialog only. Never include these in publicStatus or the WebView bridge.
    synchronized String appKey(){return credentials.optString("key","");}
    synchronized String appSecret(){return credentials.optString("secret","");}
    JSONObject exportCredentials()throws Exception {synchronized(tokenLock){synchronized(this){return new JSONObject(credentials.toString());}}}
    synchronized void disconnect() { credentials=new JSONObject(); vault.clear(); metas.clear();directories.clear(); }
    synchronized JSONObject publicStatus() throws Exception {
        return new JSONObject().put("connected",connected()).put("folder",folder()).put("root",root());
    }
    void authorize(String key, String secret, String folder, String root, Reporter reporter, Browser browser) throws Exception {
        folder=Protocol.path(folder); root=Protocol.path(root);
        if (!folder.startsWith("/apps/") || folder.split("/").length < 4)
            throw new IOException("索引目录应为 /apps/应用名称/生活点滴索引。");
        if (key.trim().isEmpty() || secret.trim().isEmpty()) throw new IOException("请填写现有应用的 AppKey 和 SecretKey。");
        JSONObject device=oauth("device/code",new String[]{"response_type","device_code","client_id",key,"scope","basic,netdisk"});
        checkOAuth(device);
        String userCode=device.getString("user_code");
        reporter.report("请在浏览器中登录并授权，用户码："+userCode);
        browser.open("https://openapi.baidu.com/device?display=mobile&code="+Uri.encode(userCode));
        int interval=Math.max(5,device.optInt("interval",5));
        long deadline=System.currentTimeMillis()+device.optLong("expires_in",300)*1000;
        while (System.currentTimeMillis()<deadline) {
            Thread.sleep(interval*1000L);
            JSONObject result=oauth("token",new String[]{"grant_type","device_token","code",device.getString("device_code"),"client_id",key,"client_secret",secret});
            String error=result.optString("error");
            if (error.equals("authorization_pending")) continue;
            if (error.equals("slow_down")) { interval+=5; continue; }
            checkOAuth(result);
            JSONObject fresh=new JSONObject().put("key",key).put("secret",secret).put("folder",folder).put("root",root)
                .put("session",UUID.randomUUID().toString());
            applyTokens(fresh,result);
            synchronized(this) { vault.write(fresh); credentials=fresh; metas.clear();directories.clear(); }
            reporter.report("授权成功，可以回到生活点滴刷新索引。"); return;
        }
        throw new IOException("授权超时，请重新点击授权连接。");
    }
    private static void applyTokens(JSONObject target, JSONObject tokens) throws Exception {
        target.put("accessToken",tokens.getString("access_token"));
        // A rotated refresh token is mandatory; never silently reuse a consumed one.
        target.remove("refreshToken");
        if (tokens.has("refresh_token")) target.put("refreshToken",tokens.getString("refresh_token"));
        target.put("expiresAt",System.currentTimeMillis()+tokens.optLong("expires_in",2592000)*1000-300000);
    }
    private String token() throws Exception {
      // Serialize rotation without holding the status/UI monitor during network I/O.
      synchronized(tokenLock) {
        JSONObject current;
        synchronized(this) { current=new JSONObject(credentials.toString()); }
        if (!current.has("accessToken")) throw new IOException("请先在设置中连接百度网盘。");
        if (current.optLong("expiresAt",0) <= System.currentTimeMillis()) {
            String refresh=current.optString("refreshToken","");
            if (refresh.isEmpty()) throw new IOException("授权已过期，请重新连接百度网盘。");
            // Persist consumption before the network call: a crash or failed refresh must not reuse it.
            current.remove("refreshToken");
            synchronized(this) {
                requireSession(current);vault.write(current);credentials=current;
            }
            JSONObject tokens=oauth("token",new String[]{"grant_type","refresh_token","refresh_token",refresh,
                "client_id",current.getString("key"),"client_secret",current.getString("secret")});
            checkOAuth(tokens);
            current=new JSONObject(current.toString());applyTokens(current,tokens);
            synchronized(this) {
                // Disconnect or a new authorization wins over an older in-flight refresh.
                requireSession(current);vault.write(current);credentials=current;
            }
        }
        synchronized(this) { requireSession(current);return current.getString("accessToken"); }
      }
    }
    private void requireSession(JSONObject current) throws IOException {
        if(!current.optString("session").equals(credentials.optString("session")))
            throw new IOException("百度连接已更改，请重新读取。");
    }
    private void permission(String path) throws Exception {
        String p=Protocol.path(path),f=folder(),r=root();
        if (!Protocol.within(p,f) && (r.isEmpty() || !Protocol.within(p,r)))
            throw new IOException("文件不在已配置的索引或原片目录中。");
    }
    private JSONObject api(String section, String... pairs) throws Exception {
        if (System.currentTimeMillis()<blockedUntil) throw new IOException("百度接口已触发频控，请稍后再试。");
        String url="https://pan.baidu.com/rest/2.0/xpan/"+section+"?access_token="+Uri.encode(token())+query(pairs);
        JSONObject result=requestJSON(url);
        int errno=result.optInt("errno",result.optInt("error_code",0));
        if (errno!=0) {
            if (errno==20012) blockedUntil=System.currentTimeMillis()+3600000;
            throw new IOException(Protocol.message(errno));
        }
        return result;
    }
    List<JSONObject> snapshots() throws Exception {
        metas.clear();directories.clear();
        String folder=folder(); permission(folder);
        List<JSONObject> files=new ArrayList<>(); int start=0;
        for(int page=0;page<200;page++) {
            JSONObject response=api("file","method","list","dir",folder,"order","name","desc","1","start",String.valueOf(start),"limit","1000");
            JSONArray items=response.getJSONArray("list");
            for(int i=0;i<items.length();i++) {
                JSONObject file=items.getJSONObject(i);
                String name=file.optString("server_filename");
                if (file.optInt("isdir")==0 && name.matches("index-[0-9]{8}T[0-9]{9}-[a-f0-9]{8}\\.json")) files.add(file);
            }
            if(items.length()<1000) break;
            start+=items.length();
            if(page==199) throw new IOException("索引目录文件过多，请在电脑端清理历史版本。");
        }
        files.sort((a,b)->b.optString("server_filename").compareTo(a.optString("server_filename")));
        return files;
    }
    byte[] snapshot(JSONObject file) throws Exception {
        String path=file.getString("path"); permission(path);
        JSONObject meta=metadata(path,true,file);
        byte[] bytes=download(meta.getString("dlink"), LibraryStore.MAX_INDEX);
        if(bytes.length!=file.getLong("size")) throw new IOException("索引下载长度不完整。");
        return bytes;
    }
    private JSONObject metadata(String path, boolean full) throws Exception {
        return metadata(path,full,null);
    }
    private JSONObject metadata(String path, boolean full, JSONObject listed) throws Exception {
        permission(path); String key=session()+"|"+path;
      synchronized(lock(metadataLocks,key)) {
        JSONObject existing=metas.get(key);
        if(existing!=null && existing.optLong("_expires")>System.currentTimeMillis() && (!full||!existing.optString("dlink").isEmpty())) return existing;
        // Resolve the exact published path through the official directory listing API.
        // The legacy path/meta API has different directory restrictions and lacks dlink.
        JSONObject basic=listed==null?findExact(path):listed;
        if(!path.equals(basic.optString("path")))throw new IOException("百度返回的文件路径与请求不一致。");
        String fsid=basic.optString("fs_id");
        if(!fsid.matches("[0-9]{1,20}"))throw new IOException("百度未返回有效文件编号，无法取得下载地址。");
        JSONObject item=fileFrom(api("multimedia","method","filemetas","fsids","["+fsid+"]","thumb","1","dlink","1","extra","1"),path);
        if(full&&item.optString("dlink").isEmpty())throw new IOException("百度未返回下载地址，请检查文件下载接口权限。");
        if(!key.equals(session()+"|"+path))throw new InterruptedException("百度连接已更改");
        item.put("_expires",System.currentTimeMillis()+6*3600000L); metas.put(key,item); return item;
      }
    }
    private JSONObject findExact(String path) throws Exception {
        String parent=path.substring(0,path.lastIndexOf('/')),key=session()+"|"+parent;
        synchronized(lock(directoryLocks,key)) {
            Directory directory=directories.get(key);
            if(directory==null||directory.expires<System.currentTimeMillis()) {
                Map<String,JSONObject> files=new HashMap<>();int start=0;
                for(int page=0;page<200;page++) {
                    JSONArray list=api("file","method","list","dir",parent,"order","name","desc","1","start",String.valueOf(start),"limit","1000").getJSONArray("list");
                    for(int i=0;i<list.length();i++) {
                        JSONObject file=list.getJSONObject(i);String returned=file.optString("path");
                        if(file.optInt("isdir")==0&&returned.startsWith(parent+"/"))files.put(returned,file);
                    }
                    if(list.length()<1000)break;
                    start+=list.length();
                    if(page==199)throw new IOException("原片单个目录文件过多，请按年份等子目录整理。");
                }
                directory=new Directory(files);directories.put(key,directory);
            }
            JSONObject file=directory.files.get(path);
            if(file==null)throw new ImageUnavailable("missing","网盘尚未找到这张原片。请按照片信息中的云端路径检查备份；完成备份后重试。");
            return file;
        }
    }
    static JSONObject fileFrom(JSONObject result,String path) throws Exception {
        JSONObject item=result.optJSONObject("info");
        if(item==null) {
            JSONArray list=result.optJSONArray("list");if(list==null)list=result.optJSONArray("info");
            if(list!=null) {
                if(list.length()!=1)throw new IOException("百度没有返回唯一的文件信息，请检查完整路径和目录权限。");
                item=list.getJSONObject(0);
            }
        }
        if(item==null&&result.has("fs_id"))item=result;
        if(item==null)throw new IOException("百度文件信息为空，请检查完整路径和目录权限。");
        if(item.optInt("errno",0)!=0) throw new IOException(Protocol.message(item.optInt("errno")));
        if(!path.equals(item.optString("path")))throw new IOException("百度返回的文件路径与请求不一致。");
        return item;
    }
    private String cacheName(JSONObject photo) {
        return Protocol.digest(session()+"|"+photo.optString("cloudPath")+"|"+photo.optString("size")+"|"+photo.optString("time"));
    }
    File cached(JSONObject photo, boolean full) {
      synchronized(diskLock) {
        File file=new File(full?cache:thumbnailCache,cacheName(photo)+(full?"-display.jpg":"-thumb.jpg"));
        if(!full&&!file.exists()){File legacy=new File(cache,file.getName());if(legacy.exists())file=legacy;}
        if(file.exists())file.setLastModified(System.currentTimeMillis());return file;
      }
    }
    boolean hasThumbnail(JSONObject photo){synchronized(diskLock){File file=cached(photo,false);return file.isFile()&&file.length()>0;}}
    InputStream readThumbnail(JSONObject photo)throws IOException {
        synchronized(diskLock){File file=cached(photo,false);return file.isFile()&&file.length()>0?new FileInputStream(file):null;}
    }
    void cancelImageRequests(){imageEpoch.incrementAndGet();for(Map.Entry<Thread,HttpURLConnection> entry:activeConnections.entrySet())if(imageThreads.contains(entry.getKey()))entry.getValue().disconnect();}
    void cancelPreview(){Thread thread=previewThread;if(thread!=null)cancelRequestsFor(thread);}
    void cancelRequestsFor(Thread thread){HttpURLConnection connection=activeConnections.get(thread);if(connection!=null)connection.disconnect();}
    private final Set<Thread> imageThreads=ConcurrentHashMap.newKeySet();
    private void beginImage(){requestEpoch.set(imageEpoch.get());imageThreads.add(Thread.currentThread());}
    private void endImage(){imageThreads.remove(Thread.currentThread());requestEpoch.remove();}
    private void checkCancelled()throws InterruptedException {
        Integer epoch=requestEpoch.get();if(Thread.currentThread().isInterrupted()||(epoch!=null&&epoch!=imageEpoch.get()))throw new InterruptedException("照片读取已暂停");
    }
    void invalidateMetadata(){metas.clear();directories.clear();}
    long retryAfterMillis(){return Math.max(0,blockedUntil-System.currentTimeMillis());}
    void prefetchMetadata(JSONObject photo)throws Exception {beginImage();try{originalMetadata(photo);}finally{endImage();}}
    /** Warm up to 64 exact paths per request, retaining the same metadata used by HD reads. */
    void prepareThumbnailMetadata(List<JSONObject> photos)throws Exception {
        beginImage();try {
            String activeSession=session();Map<String,String> requested=new HashMap<>();StringJoiner fsids=new StringJoiner(",","[","]");
            for(JSONObject photo:photos){
                checkCancelled();String path=photo.getString("cloudPath"),key=activeSession+"|"+path;
                JSONObject cached=metas.get(key);if(cached!=null&&cached.optLong("_expires")>System.currentTimeMillis())continue;
                permission(path);JSONObject basic;
                try{basic=findExact(path);}catch(ImageUnavailable missing){continue;}
                String id=basic.optString("fs_id");if(!id.matches("[0-9]{1,20}"))continue;
                requested.put(path,id);fsids.add(id);if(requested.size()>=64)break;
            }
            if(requested.isEmpty())return;
            JSONObject response=api("multimedia","method","filemetas","fsids",fsids.toString(),"thumb","1","dlink","1","extra","1");
            JSONArray items=response.optJSONArray("list");if(items==null)items=response.optJSONArray("info");
            if(items==null){if(requested.size()==1){String path=requested.keySet().iterator().next();JSONObject item=fileFrom(response,path);items=new JSONArray().put(item);}else throw new IOException("百度未返回批量文件信息。");}
            checkCancelled();if(!activeSession.equals(session()))throw new InterruptedException("百度连接已更改");
            for(int i=0;i<items.length();i++){
                JSONObject item=items.optJSONObject(i);if(item==null||item.optInt("errno",0)!=0)continue;
                String path=item.optString("path"),expected=requested.get(path);if(expected==null||!expected.equals(item.optString("fs_id")))continue;
                item.put("_expires",System.currentTimeMillis()+6*3600000L);metas.put(activeSession+"|"+path,item);
            }
        }finally{endImage();}
    }
    File image(JSONObject photo, boolean full) throws Exception {
        if(full)throw new IOException("高清图仅在浏览内存中读取，请使用 preview 接口。");
        return thumbnail(photo,false);
    }
    File imageBackground(JSONObject photo)throws Exception{return thumbnail(photo,true);}
    File imageForeground(JSONObject photo)throws Exception{return thumbnail(photo,false);}
    private File thumbnail(JSONObject photo,boolean background)throws Exception {
        File output=cached(photo,false);if(output.isFile()&&output.length()>0)return output;
        boolean acquired=false;beginImage();if(!background)foregroundThumbnails.incrementAndGet();
        try {
          while(!acquired){checkCancelled();if(background&&(foregroundThumbnails.get()>0||previewThread!=null)){Thread.sleep(150);continue;}acquired=thumbnails.tryAcquire(200,TimeUnit.MILLISECONDS);}
          checkCancelled();
          synchronized(lock(imageLocks,output.getName())) {
            if(output.isFile()&&output.length()>0)return output;
            String activeSession=session();JSONObject meta=metadata(photo.getString("cloudPath"),false);
            if(meta.has("size") && photo.has("size") && meta.getLong("size")!=photo.getLong("size"))
                throw new ImageUnavailable("mismatch","云端文件大小与索引不符，请先完成原片备份并重新发布索引。");
            JSONObject thumbs=meta.optJSONObject("thumbs");
            String url=thumbs==null?"":thumbs.optString("url3",thumbs.optString("url2",thumbs.optString("url1","")));
            if(url.isEmpty()) throw new ImageUnavailable("thumbnail","百度没有返回这张照片的缩略图，可在大图中加载原图。");
            byte[] bytes=download(url,8*1024*1024);
            android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("百度返回的缩略图不是有效图片，请重试。");
            checkCancelled();if(!activeSession.equals(session()))throw new InterruptedException("百度连接已更改");
            synchronized(diskLock){
                if(thumbnailCache.getUsableSpace()-bytes.length<SPACE_RESERVE)throw new CacheFull();
                if(background&&thumbnailBytes+legacyCacheBytes+bytes.length>THUMBNAIL_LIMIT)throw new CacheFull();
                if(!background)trimDirectory(thumbnailCache,bytes.length,Math.max(0,THUMBNAIL_LIMIT-legacyCacheBytes));
                if(thumbnailBytes+legacyCacheBytes+bytes.length>THUMBNAIL_LIMIT)throw new CacheFull();
                checkCancelled();if(!output.getName().equals(cacheName(photo)+"-thumb.jpg"))throw new InterruptedException("百度连接已更改");
                long old=output.length();writeAtomic(output,bytes);thumbnailBytes+=bytes.length-old;return output;
            }
          }
        }catch(OutOfMemoryError lowMemory){throw new IOException("缩略图读取内存不足，请稍后重试。");}
        finally{if(acquired)thumbnails.release();if(!background)foregroundThumbnails.decrementAndGet();endImage();}
    }
    static final class MemoryPreview {
        final byte[] original,display;final String path,session;
        MemoryPreview(byte[] original,byte[] display,String path,String session){this.original=original;this.display=display;this.path=path;this.session=session;}
    }
    private JSONObject originalMetadata(JSONObject photo)throws Exception{
        JSONObject meta=metadata(photo.getString("cloudPath"),true);
        if(meta.has("size")&&photo.has("size")&&meta.getLong("size")!=photo.getLong("size"))throw new ImageUnavailable("mismatch","云端文件大小与索引不符，请先完成原片备份并重新发布索引。");
        return meta;
    }
    MemoryPreview preview(JSONObject photo)throws Exception {
        boolean acquired=false;beginImage();previewThread=Thread.currentThread();try{
            originals.acquire();acquired=true;checkCancelled();
            String activeSession=session();JSONObject meta=originalMetadata(photo);long expected=photo.optLong("size");
            long headroom=Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
            long allowed=Math.min(64L*1024*1024,Math.max(8L*1024*1024,headroom/3));
            if(expected>allowed)throw new IOException("这张原片超出当前浏览内存预算，请长按下载到手机后查看。");
            byte[] raw;
            try{raw=downloadKnown(meta.getString("dlink"),expected,(int)allowed);}
            catch(DownloadFailure expired){
                if(expired.status!=401&&expired.status!=403&&expired.status!=404)throw expired;
                checkCancelled();metas.remove(activeSession+"|"+photo.getString("cloudPath"));meta=originalMetadata(photo);
                raw=downloadKnown(meta.getString("dlink"),expected,(int)allowed);
            }
            if(expected>0&&raw.length!=expected)throw new IOException("高清图下载不完整，请重试。");
            checkCancelled();byte[] display;
            try{display=renderMemory(raw,4096);}catch(OutOfMemoryError lowMemory){try{display=renderMemory(raw,2048);}catch(OutOfMemoryError retry){throw new IOException("当前浏览内存不足，请关闭其他应用后重试，或长按下载查看。");}}
            checkCancelled();if(!activeSession.equals(session()))throw new InterruptedException("百度连接已更改");
            return new MemoryPreview(raw,display,photo.getString("cloudPath"),activeSession);
        }catch(OutOfMemoryError lowMemory){throw new IOException("当前浏览内存不足，请重试或长按下载查看。");}
        finally{if(acquired)originals.release();if(previewThread==Thread.currentThread())previewThread=null;endImage();}
    }
    static byte[] renderMemory(byte[] raw,int side)throws Exception {
        long headroom=Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        long maxPixels=Math.min(10L*1024*1024,Math.max(1L*1024*1024,headroom/12));
        Bitmap image=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(raw)),(decoder,info,source)-> {
            int width=info.getSize().getWidth(),height=info.getSize().getHeight();
            if(width<=0||height<=0)throw new IllegalArgumentException("照片尺寸无效。");
            double ratio=Math.min(Math.min(1.0,(double)side/Math.max(width,height)),Math.sqrt((double)maxPixels/((double)width*height)));
            decoder.setTargetSize(Math.max(1,(int)(width*ratio)),Math.max(1,(int)(height*ratio)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if(!image.compress(Bitmap.CompressFormat.JPEG,92,out)) throw new IOException("照片无法解码。");
            return out.toByteArray();
        } finally { image.recycle(); }
    }
    void clearCache() throws IOException {
        if(thumbnails.availablePermits()!=2 || originals.availablePermits()!=1) throw new IOException("照片正在加载，请稍后清理缓存。");
        synchronized(diskLock){try{for(File directory:new File[]{cache,thumbnailCache}){File[] files=directory.listFiles();if(files!=null)for(File file:files)if(!file.delete())throw new IOException("部分缓存正在使用，请稍后重试。");}}finally{thumbnailBytes=directoryBytes(thumbnailCache);legacyCacheBytes=directoryBytes(cache);}}
        metas.clear();
        directories.clear();
    }
    private static long directoryBytes(File directory){long size=0;File[] files=directory.listFiles();if(files!=null)for(File file:files)size+=file.length();return size;}
    long cacheBytes(){synchronized(diskLock){return thumbnailBytes+legacyCacheBytes;}}
    long thumbnailCacheBytes(){synchronized(diskLock){return thumbnailBytes+legacyCacheBytes;}}
    private void trimDirectory(File directory,long incoming,long limit){
        File[] files=directory.listFiles(); if(files==null)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified)); long size=directoryBytes(directory);
        for(File file:files) {
            if(size+incoming<=limit)break;
            if(file.getName().endsWith(".part"))continue;
            long length=file.length();if(file.delete()){size-=length;if(directory.equals(thumbnailCache))thumbnailBytes-=length;}
        }
    }
    private static void writeAtomic(File target,byte[] bytes) throws IOException {
        File temp=new File(target.getPath()+".part");
        try{try(FileOutputStream out=new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
            if(!temp.renameTo(target))throw new IOException("无法保存缓存。");
        }finally{if(temp.exists())temp.delete();}
    }
    void saveOriginal(JSONObject photo,OutputStream out)throws Exception {
        JSONObject meta=originalMetadata(photo);long expected=photo.optLong("size");
        HttpURLConnection connection=openDownload(authenticated(meta.getString("dlink")));long length=0;
        try(InputStream input=connection.getInputStream()) {
            byte[] buffer=new byte[65536];int n;
            while((n=input.read(buffer))!=-1) {if(Thread.currentThread().isInterrupted())throw new InterruptedException();length+=n;if(length>CACHE_LIMIT||(expected>0&&length>expected))throw new IOException("原片下载长度超出限制。");out.write(buffer,0,n);}
            if(expected>0&&length!=expected)throw new IOException("原片下载不完整，未保存到手机相册。");out.flush();
        }finally{closeConnection(connection);}
    }
    private byte[] downloadKnown(String link,long expected,int max)throws Exception {
        if(expected<=0)return download(link,max);
        if(expected>max||expected>Integer.MAX_VALUE)throw new IOException("图片超过浏览内存允许大小，请长按下载后查看。");
        checkCancelled();HttpURLConnection connection=openDownload(authenticated(link));
        try(InputStream input=connection.getInputStream()){
            byte[] bytes=new byte[(int)expected];int offset=0,n;
            while(offset<bytes.length){checkCancelled();n=input.read(bytes,offset,Math.min(131072,bytes.length-offset));if(n<0)throw new IOException("高清图下载不完整，请重试。");offset+=n;}
            checkCancelled();if(input.read()!=-1)throw new IOException("云端原片长度与索引不符，请重新发布索引。");return bytes;
        }finally{closeConnection(connection);}
    }
    private byte[] download(String link,int max) throws Exception {
        checkCancelled();
        HttpURLConnection connection=openDownload(authenticated(link));
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){checkCancelled();if(output.size()+n>max)throw new IOException("图片超过浏览内存允许大小，请长按下载后查看。");output.write(buffer,0,n);}checkCancelled();return output.toByteArray();
        }finally{closeConnection(connection);}
    }
    private String authenticated(String link) throws Exception {
        if(!Protocol.downloadHost(link))throw new IOException("百度返回的下载地址不可信。");
        Uri uri=Uri.parse(link);
        // Replace rather than append a stale token embedded in a thumbnail link.
        Uri.Builder builder=uri.buildUpon().clearQuery();
        for(String key:uri.getQueryParameterNames()) if(!key.equals("access_token"))
            for(String value:uri.getQueryParameters(key))builder.appendQueryParameter(key,value);
        return builder.appendQueryParameter("access_token",token()).build().toString();
    }
    private HttpURLConnection openDownload(String url) throws Exception {
        for(int i=0;i<6;i++) {
            checkCancelled();
            if(!Protocol.downloadHost(url))throw new IOException("下载跳转到非百度域名，已停止。");
            HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(20000);connection.setReadTimeout(90000);
            connection.setRequestProperty("User-Agent","pan.baidu.com");
            activeConnections.put(Thread.currentThread(),connection);int code;
            try{checkCancelled();code=connection.getResponseCode();checkCancelled();}catch(Exception failure){closeConnection(connection);throw failure;}
            if(code>=300&&code<400) {
                String location=connection.getHeaderField("Location");closeConnection(connection);
                if(location==null)throw new IOException("下载跳转缺少地址。");
                String next=new URL(new URL(url),location).toString();
                if(!Protocol.downloadHost(next))throw new IOException("下载跳转地址无效。");
                String access=Uri.parse(url).getQueryParameter("access_token");
                if(Uri.parse(next).getQueryParameter("access_token")==null&&access!=null)
                    next=Uri.parse(next).buildUpon().appendQueryParameter("access_token",access).build().toString();
                url=next;continue;
            }
            if(code!=200) { closeConnection(connection);throw new DownloadFailure(code); }
            return connection;
        }
        throw new IOException("下载跳转次数过多。");
    }
    private void closeConnection(HttpURLConnection connection){activeConnections.remove(Thread.currentThread(),connection);connection.disconnect();}
    private JSONObject oauth(String method,String[] pairs) throws Exception {
        return requestJSON("https://openapi.baidu.com/oauth/2.0/"+method+"?"+query(pairs).substring(1));
    }
    private static void checkOAuth(JSONObject result) throws IOException {
        if(result.has("error")) {
            String error=result.optString("error");
            if(error.equals("invalid_client"))throw new IOException("应用密钥无效，请检查同一个应用的 AppKey 和 SecretKey。");
            if(error.equals("access_denied")||error.equals("authorization_declined"))throw new IOException("你取消了百度授权，请重新连接。");
            if(error.equals("invalid_grant"))throw new IOException("授权凭据失效，请重新连接百度网盘。");
            throw new IOException("百度授权失败（"+error+"），请重新连接。");
        }
    }
    private static String query(String[] pairs) {
        StringBuilder query=new StringBuilder();for(int i=0;i<pairs.length;i+=2)query.append('&').append(Uri.encode(pairs[i])).append('=').append(Uri.encode(pairs[i+1]));return query.toString();
    }
    private JSONObject requestJSON(String url) throws Exception {
        boolean cancellable=!url.startsWith("https://openapi.baidu.com/oauth/");if(cancellable)checkCancelled();
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(20000);connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent","pan.baidu.com");
        if(cancellable)activeConnections.put(Thread.currentThread(),connection);
        try {
            int status=connection.getResponseCode();InputStream stream=status<400?connection.getInputStream():connection.getErrorStream();
            if(stream==null)throw new IOException("百度接口暂不可用（HTTP "+status+"）。");
            byte[] bytes=LibraryStore.read(stream,4*1024*1024);if(cancellable)checkCancelled();JSONObject result=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            if(status!=200&&!result.has("error"))throw new IOException("百度接口请求失败（HTTP "+status+"）。");return result;
        } finally {closeConnection(connection);}
    }
}
