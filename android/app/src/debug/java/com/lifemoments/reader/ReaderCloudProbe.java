package com.lifemoments.reader;

import android.app.Instrumentation;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** ADB-only debug verification; keeps credentials inside Android Keystore and prints no download URLs. */
public final class ReaderCloudProbe extends Instrumentation {
    private boolean commit=false,images=false;
    @Override public void onCreate(Bundle args){super.onCreate(args);commit="sync".equals(args.getString("mode"));images="yes".equals(args.getString("images"));start();}
    @Override public void onStart(){
        JSONObject report=new JSONObject();Bundle result=new Bundle();String stage="credentials";
        try {
            Context context=getTargetContext();CloudReader cloud=new CloudReader(context);
            report.put("connected",cloud.connected()).put("configuredRoot",cloud.root());
            stage="list";List<JSONObject> candidates=cloud.snapshots();report.put("candidates",candidates.size());
            if(candidates.isEmpty())throw new java.io.IOException("No index candidates");
            stage="download";byte[] bytes=cloud.snapshot(candidates.get(0));report.put("downloadBytes",bytes.length);
            stage="validate";JSONObject doc=LibraryStore.validate(new String(bytes,StandardCharsets.UTF_8));
            report.put("publishedRoot",doc.getString("cloudRoot"));
            doc=LibraryStore.bindRoot(doc,cloud.root());report.put("effectiveRoot",doc.getString("cloudRoot")).put("photos",doc.getJSONArray("photos").length());
            if(commit) {
                stage="commit";new LibraryStore(context).commit(doc);
                context.getSharedPreferences("reader-local",Context.MODE_PRIVATE).edit().putString("snapshotSession",cloud.session())
                    .putString("lastSync",new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.CHINA).format(new java.util.Date()))
                    .putString("lastStatus","已更新 · "+doc.getJSONArray("photos").length()+" 张照片").commit();
                report.put("committed",true);
            }
            ConnectivityManager network=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps=network.getNetworkCapabilities(network.getActiveNetwork());
            boolean wifi=caps!=null&&(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            report.put("wifi",wifi);
            if(images&&wifi) {
                stage="select-jpeg";JSONArray photos=doc.getJSONArray("photos");JSONObject photo=null;
                for(int i=0;i<photos.length();i++) {
                    JSONObject p=photos.getJSONObject(i);String name=p.optString("name").toLowerCase(java.util.Locale.ROOT);
                    if(p.optString("relativePath").startsWith("2022/")&&(name.endsWith(".jpg")||name.endsWith(".jpeg"))&&p.optLong("size")<20*1024*1024){photo=p;break;}
                }
                if(photo==null)throw new java.io.IOException("No suitable small JPEG");
                stage="thumbnail";report.put("thumbnailBytes",cloud.image(photo,false).length());
                stage="original";CloudReader.MemoryPreview preview=cloud.preview(photo);report.put("displayBytes",preview.display.length).put("originalBytes",preview.original.length).put("originalStorage","memory-only");
            }
            report.put("stage","complete").put("result","PASS");
        }catch(Throwable e) {
            try {report.put("stage",stage).put("result","FAIL").put("errorType",e.getClass().getSimpleName())
                .put("error",e instanceof java.io.IOException?e.getMessage():e.getClass().getSimpleName());}catch(Exception ignored){}
        }
        result.putString("stream",report.toString()+"\n");result.putString("result",report.optString("result"));finish(-1,result);
    }
}
