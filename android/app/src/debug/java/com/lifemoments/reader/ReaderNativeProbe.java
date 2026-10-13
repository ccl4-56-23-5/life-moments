package com.lifemoments.reader;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Tests native storage and schema on an isolated instrumentation context, never a user's library. */
public final class ReaderNativeProbe extends Instrumentation {
    private int checks=0;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    private void check(boolean value){checks++;if(!value)throw new AssertionError("Check "+checks);}
    private String snapshot(){return "{\"schemaVersion\":2,\"libraryId\":\"qa-only\",\"cloudRoot\":\"/apps/test/photos\",\"photos\":[{\"id\":123,\"name\":\"中文.jpg\",\"cloudPath\":\"/apps/test/photos/2022/中文.jpg\",\"width\":400,\"height\":300,\"image\":\"javascript:alert(1)\",\"path\":\"file:///secret\"}],\"index\":{\"people\":[],\"albums\":[]}}";}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            JSONObject doc=LibraryStore.validate(snapshot());
            check(!doc.getJSONArray("photos").getJSONObject(0).has("image"));
            check(!doc.getJSONArray("photos").getJSONObject(0).has("path"));
            final java.io.File testRoot=new java.io.File(getTargetContext().getCacheDir(),"native-qa-"+java.util.UUID.randomUUID());
            if(!testRoot.mkdirs())throw new IOException("Cannot create isolated test storage");
            android.content.Context isolated=new android.content.ContextWrapper(getTargetContext()){
                @Override public java.io.File getFilesDir(){return testRoot;}
                @Override public java.io.File getCacheDir(){return testRoot;}
            };
            LibraryStore store=new LibraryStore(isolated);store.commit(doc);
            check(store.get().getString("libraryId").equals("qa-only"));
            check(store.photo("123").getString("name").equals("中文.jpg"));
            check(new LibraryStore(isolated).photo("123")!=null);
            JSONObject mapped=LibraryStore.bindRoot(doc,"/数据备份/照片");
            check(mapped.getJSONArray("photos").getJSONObject(0).getString("cloudPath").equals("/数据备份/照片/2022/中文.jpg"));
            check(doc.getString("cloudRoot").equals("/apps/test/photos"));
            check(CloudReader.fileFrom(new JSONObject("{\"list\":[{\"fs_id\":404268537588050,\"path\":\"/apps/test/file.json\"}]}"),"/apps/test/file.json").getLong("fs_id")==404268537588050L);
            boolean badMeta=false;try{CloudReader.fileFrom(new JSONObject("{\"list\":[]}"),"/apps/test/file.json");}catch(IOException e){badMeta=true;}check(badMeta);
            badMeta=false;try{CloudReader.fileFrom(new JSONObject("{\"info\":{\"path\":\"/apps/test/wrong.json\"}}"),"/apps/test/file.json");}catch(IOException e){badMeta=true;}check(badMeta);
            boolean invalid=false;try{LibraryStore.validate(snapshot().replace("\"schemaVersion\":2","\"schemaVersion\":9"));}catch(Exception e){invalid=true;}check(invalid);
            invalid=false;try{LibraryStore.validate(snapshot().replace("/2022/中文.jpg","/../中文.jpg"));}catch(Exception e){invalid=true;}check(invalid);
            invalid=false;try{LibraryStore.validate(snapshot().replace("\"width\":400","\"width\":0"));}catch(Exception e){invalid=true;}check(invalid);
            invalid=false;try{LibraryStore.validate(snapshot().substring(0,60));}catch(Exception e){invalid=true;}check(invalid);
            check(store.photo("123")!=null); // Rejected download did not modify the active snapshot.
            invalid=false;try{LibraryStore.read(new ByteArrayInputStream(new byte[11]),10);}catch(IOException e){invalid=true;}check(invalid);
            CredentialVault vault=new CredentialVault(isolated);vault.clear();
            vault.write(new JSONObject().put("accessToken","qa-token").put("refreshToken","qa-refresh"));
            check(vault.read().getString("accessToken").equals("qa-token"));
            byte[] encrypted=new android.util.AtomicFile(new java.io.File(isolated.getFilesDir(),"credentials.enc")).readFully();
            check(!new String(encrypted,java.nio.charset.StandardCharsets.UTF_8).contains("qa-token"));
            vault.write(new JSONObject().put("accessToken","qa-next").put("refreshToken","qa-rotated"));
            check(vault.read().getString("refreshToken").equals("qa-rotated"));
            vault.clear();check(vault.read().length()==0);
            check(Protocol.downloadHost("https://d.pcs.baidu.com/path"));check(!Protocol.downloadHost("https://baidu.com.example.test/path"));
            result.putString("stream","PASS: "+checks+" Android native storage/protocol checks\n");
            result.putString("result","PASS");finish(-1,result);
        }catch(Throwable e){result.putString("stream","FAIL: "+e.toString());result.putString("result","FAIL");finish(0,result);}
    }
}
