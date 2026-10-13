package com.lifemoments.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.Color;
import android.net.*;
import android.os.Bundle;
import android.os.Build;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final String HOST="appassets.androidplatform.net";
    private WebView web;
    private volatile CloudReader cloud;
    private volatile LibraryStore library;
    private LaunchScreen launchScreen;
    private SharedPreferences preferences;
    private final Map<String,JSONObject> mediaStates=new ConcurrentHashMap<>();
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile ThumbnailWarmup thumbnailWarmup;
    private LocalDeviceTransfer deviceTransfer;
    private volatile boolean foreground=false,transferring=false,transferApplying=false,waitingForUnlock=false;
    private final ExecutorService operations=Executors.newSingleThreadExecutor();
    private final ExecutorService downloads=Executors.newSingleThreadExecutor();
    private final AtomicInteger previewEpoch=new AtomicInteger();
    private final Object previewLock=new Object();
    private volatile Future<?> previewRequest;
    private volatile CloudReader.MemoryPreview currentPreview;
    private volatile String previewId="";
    private volatile boolean savingPhoto=false;
    private JSONObject pendingExport;
    private volatile boolean busy=false,destroyed=false;
    private volatile Future<?> authorization;
    private AlertDialog connectionDialog;
    private TextView connectionStatus;
    private Button authorizationButton;
    private volatile String syncText="尚未连接百度网盘";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences=getSharedPreferences("reader-local",MODE_PRIVATE);
        syncText=preferences.getString("lastStatus","尚未连接百度网盘");
        web=new WebView(this);
        web.setBackgroundColor(Color.rgb(251,251,253));
        // Resize the viewport itself: WebView padding does not move CSS fixed elements.
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(Color.rgb(251,251,253));
        frame.addView(web,new FrameLayout.LayoutParams(-1,-1));
        launchScreen=new LaunchScreen(this);frame.addView(launchScreen,new FrameLayout.LayoutParams(-1,-1));
        frame.setOnApplyWindowInsetsListener((view,insets)-> {
            if(web==null)return insets;
            int left,top,right,bottom;
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                left=bars.left;top=bars.top;right=bars.right;bottom=Math.max(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();
                DisplayCutout cutout=insets.getDisplayCutout();
                if(cutout!=null){left=Math.max(left,cutout.getSafeInsetLeft());top=Math.max(top,cutout.getSafeInsetTop());right=Math.max(right,cutout.getSafeInsetRight());bottom=Math.max(bottom,cutout.getSafeInsetBottom());}
            }
            FrameLayout.LayoutParams layout=(FrameLayout.LayoutParams)web.getLayoutParams();
            if(layout.leftMargin!=left||layout.topMargin!=top||layout.rightMargin!=right||layout.bottomMargin!=bottom){layout.setMargins(left,top,right,bottom);web.setLayoutParams(layout);}
            FrameLayout.LayoutParams splashLayout=(FrameLayout.LayoutParams)launchScreen.getLayoutParams();
            if(splashLayout.leftMargin!=left||splashLayout.topMargin!=top||splashLayout.rightMargin!=right||splashLayout.bottomMargin!=bottom){splashLayout.setMargins(left,top,right,bottom);launchScreen.setLayoutParams(splashLayout);}
            return Build.VERSION.SDK_INT>=30?WindowInsets.CONSUMED:insets.consumeSystemWindowInsets();
        });
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if(Build.VERSION.SDK_INT>=30)getWindow().getInsetsController().setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        setContentView(frame);frame.requestApplyInsets();
        if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)WebView.setWebContentsDebuggingEnabled(true);
        // Remove old HTTP-200 fallback SVGs, retaining native photo caches, index and credentials.
        if(preferences.getInt("webCacheGeneration",0)<3){web.clearCache(true);preferences.edit().putInt("webCacheGeneration",3).apply();}
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.addJavascriptInterface(new Bridge(),"Mobile");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail) {
                // A terminated renderer cannot be reused. Keep native state and offer a fresh page.
                releasePreview();pauseThumbnailWarmup("页面正在恢复");
                ViewParent parent=view.getParent();if(parent instanceof ViewGroup)((ViewGroup)parent).removeView(view);
                view.removeJavascriptInterface("Mobile");view.destroy();if(web==view)web=null;
                preferences.edit().putString("lastRecovery",detail.didCrash()?"renderer-crash":"renderer-memory").putLong("lastRecoveryAt",System.currentTimeMillis()).apply();
                if(!isFinishing()&&!destroyed)NativeUi.dialog(MainActivity.this).setTitle("重新打开照片页面")
                    .setMessage("照片页面被系统中断，已保留连接、设置和索引。重新打开即可继续浏览。")
                    .setPositiveButton("重新打开",(d,w)->recreate()).setNegativeButton("退出",(d,w)->finish()).setCancelable(false).show();
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                Uri uri=request.getUrl();
                if("https".equals(uri.getScheme())&&HOST.equals(uri.getHost()))return false;
                if(request.isForMainFrame()&&"https".equals(uri.getScheme()))openExternal(uri.toString());
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) {
                Uri uri=request.getUrl();
                if(!"https".equals(uri.getScheme())||!HOST.equals(uri.getHost()))return null;
                try {
                    String path=uri.getPath();
                    if(path==null||path.contains(".."))throw new IOException("Invalid resource");
                    if(path.equals("/snapshot.json")) {
                        JSONObject doc=library.get();return response("application/json",doc==null?"null":doc.toString());
                    }
                    if(path.startsWith("/media/"))return media(uri);
                    String asset=path.equals("/")?"index.html":path.substring(1);
                    String type=asset.endsWith(".js")?"application/javascript":asset.endsWith(".css")?"text/css":asset.endsWith(".json")?"application/json":asset.endsWith(".svg")?"image/svg+xml":asset.endsWith(".png")?"image/png":asset.endsWith(".jpg")?"image/jpeg":"text/html";
                    WebResourceResponse result=new WebResourceResponse(type,"UTF-8",getAssets().open(asset));
                    Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-cache");
                    if(type.equals("text/html"))headers.put("Content-Security-Policy","default-src 'self'; script-src 'self' blob:; worker-src 'self' blob:; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https://api.maptiler.com https://*.maptiler.com; connect-src 'self' https://api.maptiler.com https://*.maptiler.com; font-src 'self' data:; object-src 'none'; frame-src 'none'; base-uri 'none'");
                    result.setResponseHeaders(headers);return result;
                } catch(Exception e) { return missingImage(); }
                catch(OutOfMemoryError e){releasePreview();return missingImage();}
            }
        });
        operations.execute(()->{
            try {
                cloud=new CloudReader(this);library=new LibraryStore(this);createThumbnailWarmup();
                if(library.get()!=null&&(syncText.startsWith("云端没有索引中的完整原片路径")||syncText.startsWith("云端文件大小与索引不符")))
                    syncText="索引已保存 · "+library.get().getJSONArray("photos").length()+" 张照片";
                runOnUiThread(()->{if(!destroyed)web.loadUrl("https://"+HOST+"/index.html");});
            }catch(Exception e){runOnUiThread(()->{if(!destroyed){launchScreen.status("本机资料无法读取");showStartupFailure();}});}
        });
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){updateThumbnailWarmup();status();}
            @Override public void onLost(Network network){updateThumbnailWarmup();status();}
        };
        ((ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(networkCallback);
    }
    private void showStartupFailure() {
        NativeUi.dialog(this).setTitle("无法读取本机资料")
            .setMessage("缓存或加密凭据无法读取。你可以重置手机端资料后重新连接；电脑端和网盘文件不会被修改。")
            .setPositiveButton("重置手机端资料",(d,w)-> {
                new CredentialVault(this).clear();new android.util.AtomicFile(new File(getFilesDir(),"library-v2.json")).delete();
                getSharedPreferences("reader-local",MODE_PRIVATE).edit().clear().apply();recreate();
            }).setNegativeButton("退出",(d,w)->finish()).setCancelable(false).show();
    }
    private static WebResourceResponse response(String mime,String text) {
        return noStore(new WebResourceResponse(mime,"UTF-8",new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }
    private static WebResourceResponse noStore(WebResourceResponse response){response.setResponseHeaders(Collections.singletonMap("Cache-Control","no-store"));return response;}
    private static WebResourceResponse missingImage() {
        WebResourceResponse result=response("text/plain","Resource unavailable");result.setStatusCodeAndReasonPhrase(404,"Not Found");return result;
    }
    private WebResourceResponse mediaFailure(String id,boolean full,String code,String message)throws Exception {
        JSONObject state=new JSONObject().put("code",code).put("message",message);mediaStates.put(id+(full?"/full":"/thumb"),state);
        WebResourceResponse result=response("application/json",state.toString());result.setStatusCodeAndReasonPhrase(code.equals("missing")?404:503,code.equals("missing")?"Not Found":"Service Unavailable");return result;
    }
    private WebResourceResponse media(Uri uri) throws Exception {
        List<String> parts=uri.getPathSegments();if(parts.size()!=4) return missingImage();
        JSONObject snapshot=library.get();
        if(snapshot==null||!parts.get(1).equals(snapshot.optString("libraryId")))return missingImage();
        JSONObject photo=library.photo(parts.get(2));if(photo==null)return missingImage();
        boolean full=parts.get(3).equals("full");
        if(!full&&!parts.get(3).equals("thumb"))return missingImage();
        if(full){CloudReader.MemoryPreview memory=currentPreview;if(memory!=null&&previewId.equals(parts.get(2))&&memory.path.equals(photo.optString("cloudPath"))&&memory.session.equals(cloud.session())&&bound())return noStore(new WebResourceResponse("image/jpeg",null,new ByteArrayInputStream(memory.display)));return mediaFailure(parts.get(2),true,"original","高清图正在读取，请稍候或重试。");}
        String id=parts.get(2),key=id+(full?"/full":"/thumb");
        InputStream cached=cloud.readThumbnail(photo);
        if(cached!=null){mediaStates.remove(key);return noStore(new WebResourceResponse("image/jpeg",null,cached));}
        if(full)return mediaFailure(id,true,"original","请点击加载原图。");
        if(transferring||transferApplying)return mediaFailure(id,false,"paused","设置迁移结束后继续读取。");
        if(!cloud.connected())return mediaFailure(id,false,"connection","请先连接百度网盘。");
        if(!bound())return mediaFailure(id,false,"binding","请刷新索引，确认当前百度账号。");
        if(!foreground||locked())return mediaFailure(id,false,"paused","解锁并回到应用后继续读取。");
        if(!online())return mediaFailure(id,false,"network","网络已断开，恢复后会重试。");
        if(!wifi()&&preferences.getBoolean("wifiImages",true))return mediaFailure(id,false,"wifi","仅在 Wi-Fi 下自动加载，请连接 Wi-Fi 或调整设置。");
        try { cloud.imageForeground(photo);InputStream image=cloud.readThumbnail(photo);if(image==null)throw new IOException("缩略图缓存已更新，请重试。");mediaStates.remove(key);return noStore(new WebResourceResponse("image/jpeg",null,image)); }
        catch(Exception e) { return mediaFailure(id,false,e instanceof CloudReader.ImageUnavailable?((CloudReader.ImageUnavailable)e).code:"network",safeError(e)); }
        catch(OutOfMemoryError e){return mediaFailure(id,false,"memory","图片内存不足，请稍后重试。");}
    }
    private boolean bound() { CloudReader reader=cloud;return reader!=null&&!reader.session().isEmpty()&&reader.session().equals(preferences.getString("snapshotSession","")); }
    private boolean locked(){KeyguardManager manager=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);return manager!=null&&manager.isKeyguardLocked();}
    private boolean online(){ConnectivityManager manager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);NetworkCapabilities caps=manager.getNetworkCapabilities(manager.getActiveNetwork());return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}
    private boolean wifi() {
        ConnectivityManager manager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities caps=manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps!=null&&(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }
    private void js(String method,JSONObject value) {
        if(destroyed||web==null)return;
        runOnUiThread(()-> { WebView current=web;if(!destroyed&&current!=null)current.evaluateJavascript("typeof window."+method+"==='function' && window."+method+"("+value.toString()+")",null); });
    }
    private void status() {
        if(cloud==null||library==null||destroyed)return;
        try {
            JSONObject result=cloud.publicStatus().put("busy",busy).put("text",syncText)
                .put("wifiImages",preferences.getBoolean("wifiImages",true)).put("wifi",wifi())
                .put("thumbnailWarmupAuto",preferences.getBoolean("thumbnailWarmupAuto",true))
                .put("lastSync",preferences.getString("lastSync",""))
                .put("cacheBytes",cloud.cacheBytes()).put("thumbnailCacheBytes",cloud.thumbnailCacheBytes()).put("hasSnapshot",library.get()!=null).put("bound",bound())
                .put("thumbnailWarmup",thumbnailWarmup==null?new JSONObject():thumbnailWarmup.status());
            js("setMobileStatus",result);
        }catch(Exception ignored) { }
    }
    private void createThumbnailWarmup(){
        ThumbnailWarmup previous=thumbnailWarmup;if(previous!=null)previous.close();
        thumbnailWarmup=new ThumbnailWarmup(cloud,this::status);thumbnailWarmup.replace(library.get());updateThumbnailWarmup();
    }
    private void pauseThumbnailWarmup(String reason){ThumbnailWarmup worker=thumbnailWarmup;if(worker!=null)worker.setEnabled(false,reason);}
    private void updateThumbnailWarmup(){
        ThumbnailWarmup worker=thumbnailWarmup;CloudReader reader=cloud;if(worker==null||reader==null||destroyed)return;
        String reason=!preferences.getBoolean("thumbnailWarmupAuto",true)?"自动缓存已暂停":transferring||transferApplying?"正在迁移设置":busy?"正在更新连接或索引":!foreground||locked()?"回到应用后继续缓存":!reader.connected()?"等待连接百度网盘":!bound()?"等待刷新索引":!online()?"等待网络恢复":preferences.getBoolean("wifiImages",true)&&!wifi()?"等待 Wi-Fi":"";
        worker.setEnabled(reason.isEmpty(),reason);
    }
    private void progress(String text) {
        syncText=text;preferences.edit().putString("lastStatus",text.contains("用户码：")?"等待百度授权完成":text).apply();status();runOnUiThread(()-> { if(connectionStatus!=null)connectionStatus.setText(text); });
    }
    private static String safeError(Exception e) {
        if(e instanceof InterruptedException)return "操作已取消";
        if(e instanceof org.json.JSONException) {
            String message=e.getMessage();
            if(message!=null&&message.startsWith("No value for "))return "数据缺少字段："+message.substring(13);
            return "索引或百度接口的数据格式不兼容，请检查索引版本。";
        }
        if(e instanceof IOException||e instanceof IllegalArgumentException)return e.getMessage()==null?"读取失败，请重试。":e.getMessage();
        return "数据读取失败，请检查索引格式或重新连接。";
    }
    private void notice(String text) {
        try { js("mobileNotice",new JSONObject().put("text",text)); }catch(Exception ignored) { }
    }
    private void openExternal(String url) {
        runOnUiThread(()-> { try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }
        catch(ActivityNotFoundException e) { notice("手机没有可用的浏览器。"); } });
    }
    final class Bridge {
        @JavascriptInterface public String mediaState(String id,boolean full){JSONObject state=mediaStates.get(id+(full?"/full":"/thumb"));return state==null?"{}":state.toString();}
        @JavascriptInterface public void retryPreviews(){cloud.invalidateMetadata();mediaStates.clear();}
        @JavascriptInterface public void ready() {updateThumbnailWarmup();status();runOnUiThread(()->{if(!destroyed)launchScreen.complete();});}
        @JavascriptInterface public void configure() { runOnUiThread(()->showConnection()); }
        @JavascriptInterface public void refresh() { runOnUiThread(()->sync()); }
        @JavascriptInterface public void importIndex() {
            runOnUiThread(()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),12));
        }
        @JavascriptInterface public void guide() { runOnUiThread(()->web.loadUrl("https://"+HOST+"/guide.html")); }
        @JavascriptInterface public void wifiImages(boolean value) { preferences.edit().putBoolean("wifiImages",value).apply();updateThumbnailWarmup();status(); }
        @JavascriptInterface public void cacheAllThumbnails(){preferences.edit().putBoolean("thumbnailWarmupAuto",true).apply();ThumbnailWarmup worker=thumbnailWarmup;if(worker!=null){worker.replace(library.get());worker.retryFailures();}updateThumbnailWarmup();status();}
        @JavascriptInterface public void cancelThumbnailCache(){preferences.edit().putBoolean("thumbnailWarmupAuto",false).apply();updateThumbnailWarmup();status();}
        @JavascriptInterface public void transferDevices(){runOnUiThread(()->showDeviceTransfer());}
        @JavascriptInterface public void clearCache() {
            preferences.edit().putBoolean("thumbnailWarmupAuto",false).apply();pauseThumbnailWarmup("自动缓存已暂停");
            operations.execute(()-> { try { cloud.clearCache();ThumbnailWarmup worker=thumbnailWarmup;if(worker!=null)worker.replace(library.get()==null?null:new JSONObject(library.get().toString()));js("cacheCleared",new JSONObject());notice("照片缓存已清理，索引保留。可手动恢复全图库缓存。"); }catch(Exception e){notice(safeError(e));}status(); });
        }
        @JavascriptInterface public void disconnect() {
            runOnUiThread(()->NativeUi.dialog(MainActivity.this).setTitle("断开百度网盘？")
                .setMessage("保留本机索引。再次连接后需重新刷新索引，照片才能继续加载。")
                .setNegativeButton("取消",null).setPositiveButton("断开",(d,w)-> {
                    releasePreview();pauseThumbnailWarmup("等待连接百度网盘");cloud.cancelImageRequests();
                    Future<?> pending=authorization;if(pending!=null)pending.cancel(true);
                    operations.execute(()-> {cloud.disconnect();preferences.edit().remove("snapshotSession").apply();updateThumbnailWarmup();progress("已断开连接");});
                }).show());
        }
        @JavascriptInterface public void original(String id) {int epoch=previewEpoch.get();runOnUiThread(()->{synchronized(previewLock){if(previewEpoch.get()==epoch)loadOriginal(id);}});}
        @JavascriptInterface public void preview(String id){int epoch=previewEpoch.get();runOnUiThread(()->{synchronized(previewLock){if(previewEpoch.get()==epoch)loadPreview(id,true);}});}
        @JavascriptInterface public void releasePreview(){MainActivity.this.releasePreview();}
        @JavascriptInterface public void photoMenu(String id){runOnUiThread(()->showPhotoMenu(id));}
        @JavascriptInterface public String windowState(){
            if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)return "{}";
            CompletableFuture<String> result=new CompletableFuture<>();runOnUiThread(()->{try{
                int[] position=new int[2];web.getLocationOnScreen(position);WindowInsets insets=getWindow().getDecorView().getRootWindowInsets();
                int left=insets.getSystemWindowInsetLeft(),top=insets.getSystemWindowInsetTop(),right=insets.getSystemWindowInsetRight(),bottom=insets.getSystemWindowInsetBottom();
                if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());left=bars.left;top=bars.top;right=bars.right;bottom=bars.bottom;}
                result.complete(new JSONObject().put("screen",new org.json.JSONArray(new int[]{getWindow().getDecorView().getWidth(),getWindow().getDecorView().getHeight()})).put("safeInsets",new org.json.JSONArray(new int[]{left,top,right,bottom})).put("webBounds",new org.json.JSONArray(new int[]{position[0],position[1],web.getWidth(),web.getHeight()})).put("splashReady",launchScreen.getVisibility()==View.GONE).put("memoryPreview",currentPreview!=null).toString());
            }catch(Exception e){result.complete("{}");}});try{return result.get(5,TimeUnit.SECONDS);}catch(Exception e){return "{}";}
        }
    }
    private void loadOriginal(String id) {
        if(library==null||cloud==null||destroyed||transferring||transferApplying){notice("正在准备照片，请稍后重试。");return;}
        int epoch=previewEpoch.get();
        if(!wifi()&&preferences.getBoolean("wifiImages",true))NativeUi.dialog(this).setTitle("使用移动网络读取高清图？").setMessage("约 "+String.format(Locale.CHINA,"%.1f",library.photo(id)==null?0:library.photo(id).optLong("size")/1048576.0)+" MiB · 仅在浏览内存中显示").setNegativeButton("取消",(d,w)->{if(previewEpoch.get()==epoch)previewResult(id,false,"已取消读取",false);}).setPositiveButton("读取",(d,w)->{synchronized(previewLock){if(previewEpoch.get()==epoch)loadPreview(id,false);}}).show();
        else loadPreview(id,false);
    }
    private void previewResult(String id,boolean ok,String error,boolean automatic){try{js("originalReady",new JSONObject().put("id",id).put("ok",ok).put("error",error).put("automatic",automatic));}catch(Exception ignored){}}
    private void releasePreview(){synchronized(previewLock){previewEpoch.incrementAndGet();Future<?> previous=previewRequest;previewRequest=null;if(previous!=null)previous.cancel(true);CloudReader reader=cloud;if(reader!=null)reader.cancelPreview();currentPreview=null;previewId="";}}
    private void loadPreview(String id,boolean automatic){
        if(library==null||cloud==null||destroyed||transferring||transferApplying){previewResult(id,false,"正在准备照片，请稍后重试",automatic);return;}
        JSONObject photo=library.photo(id);if(photo==null){previewResult(id,false,"照片不在当前图库中",automatic);return;}
        if(!foreground||locked()){previewResult(id,false,"解锁并回到应用后继续读取",automatic);return;}
        if(!online()){previewResult(id,false,"网络已断开，恢复后可重试",automatic);return;}
        if(!cloud.connected()||!bound()){previewResult(id,false,"请连接百度网盘并刷新索引后读取高清图",automatic);return;}
        if(automatic&&!wifi()&&preferences.getBoolean("wifiImages",true)){previewResult(id,false,"等待 Wi-Fi，也可点击按钮使用流量读取",true);return;}
        CloudReader.MemoryPreview existing=currentPreview;
        if(existing!=null&&previewId.equals(id)&&existing.path.equals(photo.optString("cloudPath"))&&existing.session.equals(cloud.session())){previewResult(id,true,"",automatic);return;}
        if(previewRequest!=null&&!previewRequest.isDone()&&previewId.equals(id))return;
        synchronized(previewLock){
        releasePreview();ThumbnailWarmup worker=thumbnailWarmup;if(worker!=null)worker.prioritize(photo);int epoch=previewEpoch.get();previewId=id;
        previewRequest=downloads.submit(()->{
            try{CloudReader.MemoryPreview memory=cloud.preview(photo);synchronized(previewLock){if(destroyed||!foreground||previewEpoch.get()!=epoch)return;currentPreview=memory;previewResult(id,true,"",automatic);}}
            catch(Exception e){if(previewEpoch.get()==epoch)previewResult(id,false,safeError(e),automatic);}
            catch(OutOfMemoryError e){if(previewEpoch.get()==epoch)previewResult(id,false,"图片过大，请长按下载到手机后查看",automatic);}
        });
        }
    }
    private void showPhotoMenu(String id){
        if(library==null||cloud==null||destroyed)return;
        JSONObject photo=library.photo(id);if(photo==null)return;
        NativeUi.dialog(this).setTitle(photo.optString("name")).setItems(new String[]{"下载到手机相册","查看人物与照片信息"},(dialog,which)->{
            if(which==0)savePhoto(photo);else try{js("showPhotoInfo",new JSONObject().put("id",id));}catch(Exception ignored){}
        }).setNegativeButton("取消",null).show();
    }
    private void savePhoto(JSONObject photo){
        if(transferring||transferApplying){notice("设置迁移完成后再下载照片。");return;}
        if(savingPhoto){notice("已有照片正在下载，请稍候。");return;}
        if(!cloud.connected()||!bound()){notice("请连接百度网盘并刷新索引后下载。");return;}
        if(Build.VERSION.SDK_INT<29){pendingExport=photo;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(PhoneDownloads.mime(photo.optString("name"))).putExtra(Intent.EXTRA_TITLE,photo.optString("name")),13);return;}
        savingPhoto=true;notice("正在下载原片…");downloads.execute(()->{
            try{PhoneDownloads.save(this,photo.optString("name"),output->writeOriginal(photo,output));notice("已保存到手机相册 · 生活点滴");}
            catch(Exception e){notice(safeError(e));}finally{savingPhoto=false;}
        });
    }
    private void writeOriginal(JSONObject photo,OutputStream output)throws Exception{
        if(!cloud.connected()||!bound())throw new IOException("百度连接已更改，请刷新索引后重新下载。");
        CloudReader.MemoryPreview memory=currentPreview;
        if(memory!=null&&memory.path.equals(photo.optString("cloudPath"))&&memory.session.equals(cloud.session())&&bound())output.write(memory.original);
        else cloud.saveOriginal(photo,output);
    }
    private void sync() {
        if(busy||transferring||transferApplying){notice("正在读取或迁移，请稍候。");return;}
        if(!cloud.connected()){showConnection();return;}
        busy=true;updateThumbnailWarmup();progress("正在查找最新索引…");
        operations.execute(()-> {
            try {
                List<JSONObject> candidates=cloud.snapshots();
                if(candidates.isEmpty())throw new IOException("索引目录没有 index-*.json，请在电脑端开启索引同步。");
                JSONObject chosen=null;String lastError="";int tried=0;
                for(JSONObject candidate:candidates) {
                    if(tried++==3)break;
                    try {
                        progress("正在下载索引…");
                        byte[] bytes=cloud.snapshot(candidate);
                        chosen=LibraryStore.validate(new String(bytes,StandardCharsets.UTF_8));break;
                    }catch(Exception e) {
                        // A permission, authorization or frequency error will not improve by trying older files.
                        if(!(e instanceof org.json.JSONException)&&!(e instanceof IOException))throw e;
                        lastError=safeError(e);
                        if(lastError.contains("频控")||lastError.contains("权限")||lastError.contains("授权"))throw e;
                    }
                }
                if(chosen==null)throw new IOException("最近索引无法使用，保留旧版。"+lastError);
                chosen=LibraryStore.bindRoot(chosen,cloud.root());
                releasePreview();mediaStates.clear();
                library.commit(chosen);
                String stamp=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(new Date());
                preferences.edit().putString("snapshotSession",cloud.session()).putString("lastSync",stamp).commit();
                progress("已更新 · "+chosen.getJSONArray("photos").length()+" 张照片");
                thumbnailWarmup.replace(chosen);updateThumbnailWarmup();
                js("snapshotChanged",new JSONObject());
            }catch(Exception e){progress(safeError(e));notice(safeError(e));}
            finally{busy=false;updateThumbnailWarmup();status();}
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent intent) {
        super.onActivityResult(request,result,intent);
        if(request==13){JSONObject photo=pendingExport;pendingExport=null;if(result!=RESULT_OK||intent==null||intent.getData()==null||photo==null)return;
            Uri target=intent.getData();savingPhoto=true;downloads.execute(()->{try(OutputStream output=getContentResolver().openOutputStream(target,"w")){if(output==null)throw new IOException("无法写入选择的位置。");writeOriginal(photo,output);notice("已下载到选择的位置");}catch(Exception e){try{android.provider.DocumentsContract.deleteDocument(getContentResolver(),target);}catch(Exception ignored){}notice(safeError(e));}finally{savingPhoto=false;}});return;
        }
        if(request!=12||result!=RESULT_OK||intent==null||intent.getData()==null)return;
        Uri uri=intent.getData();
        operations.execute(()-> {
            try {
                byte[] bytes=LibraryStore.read(getContentResolver().openInputStream(uri),LibraryStore.MAX_INDEX);
                JSONObject doc=LibraryStore.validate(new String(bytes,StandardCharsets.UTF_8));
                if(cloud.connected())doc=LibraryStore.bindRoot(doc,cloud.root());releasePreview();library.commit(doc);
                preferences.edit().putString("snapshotSession",cloud.session()).putString("lastSync","本地导入").commit();
                progress("已导入 · "+doc.getJSONArray("photos").length()+" 张照片");thumbnailWarmup.replace(doc);updateThumbnailWarmup();js("snapshotChanged",new JSONObject());
            }catch(Exception e){notice(safeError(e));}status();
        });
    }
    private EditText field(LinearLayout parent,String label,String value,boolean password) {
        return NativeUi.field(parent,label,value,password);
    }
    private void showConnection() {
        if(transferring||transferApplying){notice("设置迁移完成后再连接网盘。");return;}
        if(connectionDialog!=null&&connectionDialog.isShowing())return;
        LinearLayout content=NativeUi.content(this);
        TextView hint=NativeUi.paragraph(this,"复用电脑端现有的同一个百度应用。填写后会打开系统浏览器，登录存放照片的百度账号。");content.addView(hint);
        EditText key=field(content,"AppKey",cloud.appKey(),false),secret=field(content,"SecretKey",cloud.appSecret(),true);
        EditText folder=field(content,"索引目录",cloud.folder().isEmpty()?"/apps/你的应用名称/生活点滴索引":cloud.folder(),false);
        String defaultRoot=library.get()==null?"/生活点滴":library.get().optString("cloudRoot","/生活点滴");
        EditText root=field(content,"已获百度授权的原片目录",cloud.root().isEmpty()?defaultRoot:cloud.root(),false);
        TextView note=NativeUi.paragraph(this,"手机按此原片根目录加索引内的相对子目录读取，不搜索同名文件。填写目录不会增加百度权限，目录仍须已获授权。");content.addView(note);
        connectionStatus=NativeUi.status(this,syncText);content.addView(connectionStatus);
        ScrollView scroll=NativeUi.scroll(this,content);
        connectionDialog=NativeUi.dialog(this).setTitle("连接百度网盘 · Beta").setView(scroll)
            .setNegativeButton("取消",(d,w)-> {Future<?> pending=authorization;if(pending!=null)pending.cancel(true);})
            .setPositiveButton("授权连接",null).create();
        connectionDialog.setOnDismissListener(d-> { connectionStatus=null;authorizationButton=null; });
        connectionDialog.setCanceledOnTouchOutside(false);connectionDialog.show();
        authorizationButton=connectionDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        authorizationButton.setOnClickListener(v-> {
            if(busy){notice("请等当前读取完成。");return;}
            String appKey=key.getText().toString().trim(),appSecret=secret.getText().toString().trim();
            String indexFolder=folder.getText().toString().trim(),photoRoot=root.getText().toString().trim();
            if(appKey.isEmpty()||appSecret.isEmpty()){connectionStatus.setText("请填写 AppKey 和 SecretKey。");return;}
            busy=true;releasePreview();pauseThumbnailWarmup("正在连接百度网盘");cloud.cancelImageRequests();authorizationButton.setEnabled(false);progress("正在申请授权码…");
            authorization=operations.submit(()-> {
                try {
                    cloud.authorize(appKey,appSecret,indexFolder,photoRoot,MainActivity.this::progress,MainActivity.this::openExternal);
                    preferences.edit().remove("snapshotSession").apply();
                    runOnUiThread(()-> {if(connectionDialog!=null&&connectionDialog.isShowing())connectionDialog.dismiss();});
                    notice("连接成功，点击刷新索引。");
                }catch(Exception e){progress(safeError(e));}
                finally {busy=false;updateThumbnailWarmup();status();runOnUiThread(()-> {if(authorizationButton!=null)authorizationButton.setEnabled(true);});}
            });
        });
    }
    private JSONObject webSettings(boolean importSettings,JSONObject value)throws Exception{
        CompletableFuture<JSONObject> result=new CompletableFuture<>();
        runOnUiThread(()->{
            WebView current=web;if(current==null||destroyed){result.completeExceptionally(new IOException("照片页面已暂停，请重新打开后迁移。"));return;}
            String script=importSettings?"typeof window.importLocalSettings==='function'?window.importLocalSettings("+value.toString()+"):null":"typeof window.exportLocalSettings==='function'?window.exportLocalSettings():null";
            current.evaluateJavascript(script,encoded->{try{
                Object decoded=new org.json.JSONArray("["+encoded+"]").get(0);
                if(!(decoded instanceof String))throw new IOException("照片页面尚未就绪，请回到设置页重试。");
                JSONObject answer=new JSONObject((String)decoded);
                if(importSettings&&!answer.optBoolean("ok"))throw new IOException("页面设置格式不兼容。");
                result.complete(answer);
            }catch(Exception e){result.completeExceptionally(e);}});
        });
        try{return result.get(8,TimeUnit.SECONDS);}catch(Exception e){throw new IOException("无法读取或应用页面设置，请回到设置页重试。",e);}
    }
    private JSONObject exportReaderSettings()throws Exception{
        return new JSONObject().put("wifiImages",preferences.getBoolean("wifiImages",true)).put("thumbnailWarmupAuto",preferences.getBoolean("thumbnailWarmupAuto",true))
            .put("snapshotSession",preferences.getString("snapshotSession","")).put("lastSync",preferences.getString("lastSync",""))
            .put("lastStatus",preferences.getString("lastStatus","尚未连接百度网盘"));
    }
    private void applyReaderSettings(JSONObject settings)throws Exception{
        if(!preferences.edit().putBoolean("wifiImages",settings.getBoolean("wifiImages")).putBoolean("thumbnailWarmupAuto",settings.getBoolean("thumbnailWarmupAuto"))
            .putString("snapshotSession",settings.getString("snapshotSession")).putString("lastSync",settings.getString("lastSync")).putString("lastStatus",settings.getString("lastStatus")).commit())throw new IOException("无法保存本机设置。");
    }
    private void showDeviceTransfer(){
        if(cloud==null||library==null||busy||savingPhoto||transferring||transferApplying||(authorization!=null&&!authorization.isDone())||(connectionDialog!=null&&connectionDialog.isShowing())){notice("当前操作完成后再迁移设置。");return;}
        if(!wifi()){notice("请将两台设备连接到同一个 Wi-Fi 后再迁移。");return;}
        transferring=true;releasePreview();pauseThumbnailWarmup("正在迁移设置");cloud.cancelImageRequests();
        deviceTransfer=new LocalDeviceTransfer(this,new LocalDeviceTransfer.Provider(){
            @Override public TransferPayload exportPayload()throws Exception{
                return new TransferPayload(exportReaderSettings(),webSettings(false,null),cloud.exportCredentials(),library.get());
            }
            @Override public void importPayload(TransferPayload payload)throws Exception{
                transferApplying=true;
                try{importTransferredPayload(payload);}finally{transferApplying=false;updateThumbnailWarmup();status();}
            }
            private void importTransferredPayload(TransferPayload payload)throws Exception{
                JSONObject incoming=payload.library(),credentials=payload.credentials(),readerSettings=payload.readerSettings();
                if(incoming!=null){incoming=LibraryStore.validate(incoming.toString());if(credentials.has("accessToken"))incoming=LibraryStore.bindRoot(incoming,credentials.getString("root"));}
                if(!readerSettings.optString("snapshotSession").isEmpty()&&!readerSettings.optString("snapshotSession").equals(credentials.optString("session")))throw new IOException("连接与索引绑定不一致，请在旧设备刷新索引后重试。");
                JSONObject oldCredentials=cloud.exportCredentials(),oldReader=exportReaderSettings(),oldWeb=webSettings(false,null),oldLibrary=library.get();
                CredentialVault vault=new CredentialVault(MainActivity.this);
                try{
                    vault.importFromTransfer(credentials);CloudReader replacement=new CloudReader(MainActivity.this);
                    if(incoming!=null)library.commit(incoming);else{new android.util.AtomicFile(new File(getFilesDir(),"library-v2.json")).delete();library=new LibraryStore(MainActivity.this);}
                    applyReaderSettings(readerSettings);webSettings(true,payload.webSettings());cloud=replacement;
                    syncText=preferences.getString("lastStatus","设置已迁移");mediaStates.clear();createThumbnailWarmup();js("snapshotChanged",new JSONObject());
                }catch(Exception failure){
                    vault.write(oldCredentials);applyReaderSettings(oldReader);
                    if(oldLibrary!=null)library.commit(oldLibrary);else{new android.util.AtomicFile(new File(getFilesDir(),"library-v2.json")).delete();library=new LibraryStore(MainActivity.this);}
                    cloud=new CloudReader(MainActivity.this);try{webSettings(true,oldWeb);}catch(Exception ignored){}
                    createThumbnailWarmup();throw failure;
                }
            }
            @Override public void onStatus(String text){notice(text);}
            @Override public void onClosed(){transferring=false;deviceTransfer=null;updateThumbnailWarmup();status();}
        });
        deviceTransfer.show();
    }
    @Override protected void onResume(){
        super.onResume();foreground=true;waitingForUnlock=locked();WebView current=web;if(current!=null)current.onResume();
        if(!waitingForUnlock)notifyAppResumed();else{updateThumbnailWarmup();status();}
    }
    private void notifyAppResumed(){
        if(cloud==null||!foreground||locked()||destroyed)return;
        mediaStates.entrySet().removeIf(entry->{String code=entry.getValue().optString("code");return code.equals("network")||code.equals("paused")||code.equals("memory")||code.equals("wifi");});
        updateThumbnailWarmup();status();try{js("appResumed",new JSONObject().put("wifi",wifi()).put("online",online()));}catch(Exception ignored){}
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&foreground&&waitingForUnlock&&!locked()){waitingForUnlock=false;notifyAppResumed();}}
    @Override protected void onPause(){
        foreground=false;pauseThumbnailWarmup("解锁并回到应用后继续缓存");releasePreview();CloudReader reader=cloud;if(reader!=null)reader.cancelImageRequests();
        LocalDeviceTransfer transfer=deviceTransfer;if(transfer!=null)transfer.close();WebView current=web;if(current!=null)current.onPause();super.onPause();
    }
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW){releasePreview();try{js("previewMemoryReleased",new JSONObject());}catch(Exception ignored){}}}
    @Override public void onBackPressed() {
        if(web!=null&&web.getUrl()!=null&&web.getUrl().contains("/guide.html")){web.loadUrl("https://"+HOST+"/index.html");return;}
        if(web!=null)web.evaluateJavascript("window.mobileBack && window.mobileBack()",value-> {if("false".equals(value))finish();});else super.onBackPressed();
    }
    @Override protected void onDestroy(){destroyed=true;releasePreview();ThumbnailWarmup worker=thumbnailWarmup;if(worker!=null)worker.close();LocalDeviceTransfer transfer=deviceTransfer;if(transfer!=null)transfer.close();if(networkCallback!=null)try{((ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);}catch(IllegalArgumentException ignored){}operations.shutdownNow();downloads.shutdownNow();WebView current=web;web=null;if(current!=null){current.removeJavascriptInterface("Mobile");ViewParent parent=current.getParent();if(parent instanceof ViewGroup)((ViewGroup)parent).removeView(current);current.destroy();}super.onDestroy();}
}
