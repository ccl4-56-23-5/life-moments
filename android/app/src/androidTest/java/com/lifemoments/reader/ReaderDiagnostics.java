package com.lifemoments.reader;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Explicitly invoked, read-only diagnostics. Never prints tokens or writes the active library. */
public final class ReaderDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        String stage="credentials";JSONObject report=new JSONObject();Bundle result=new Bundle();
        try {
            CloudReader cloud=new CloudReader(getTargetContext());report.put("connection",cloud.publicStatus());
            stage="list";List<JSONObject> candidates=cloud.snapshots();report.put("candidates",candidates.size());
            if(candidates.isEmpty())throw new java.io.IOException("No index candidates");
            JSONObject file=candidates.get(0);report.put("indexPath",file.optString("path")).put("indexSize",file.optLong("size"));
            stage="metadata";Method method=CloudReader.class.getDeclaredMethod("metadata",String.class,boolean.class);method.setAccessible(true);
            JSONObject meta=(JSONObject)method.invoke(cloud,file.getString("path"),true);
            report.put("metaFields",meta.names()).put("hasDownloadLink",meta.has("dlink"));
            stage="download";byte[] bytes=cloud.snapshot(file);report.put("downloadBytes",bytes.length);
            stage="validate";JSONObject doc=LibraryStore.validate(new String(bytes,StandardCharsets.UTF_8));
            report.put("photos",doc.getJSONArray("photos").length()).put("cloudRoot",doc.getString("cloudRoot"));
            report.put("stage","complete").put("result","PASS");
        }catch(Throwable e){
            if(e instanceof InvocationTargetException&&e.getCause()!=null)e=e.getCause();
            try {
                String message=e.getMessage();
                if(message==null||(!(e instanceof java.io.IOException)&&!message.startsWith("No value for ")))message=e.getClass().getSimpleName();
                message=message.replaceAll("(?i)(access_token|refresh_token|client_secret|client_id)=[^&\\s]+","$1=REDACTED");
                report.put("stage",stage).put("result","FAIL").put("errorType",e.getClass().getSimpleName()).put("error",message);
            }catch(Exception ignored){}
        }
        result.putString("stream",report.toString()+"\n");result.putString("result",report.optString("result"));finish(-1,result);
    }
}
