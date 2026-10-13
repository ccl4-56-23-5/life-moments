package com.lifemoments.reader;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.*;

/** Development QA for the own app window and isolated thumbnail cache; no user files are changed. */
public final class ReaderDeviceProbe extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private static WebView findWeb(View view){if(view instanceof WebView)return (WebView)view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){WebView found=findWeb(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);}
    @android.annotation.TargetApi(30)
    @Override public void onStart(){
        JSONObject report=new JSONObject();Bundle result=new Bundle();
        try {
            if(Build.VERSION.SDK_INT<30)throw new IOException("Window QA requires Android 11 or newer");
            File isolatedRoot=new File(getTargetContext().getCacheDir(),"thumbnail-qa-"+java.util.UUID.randomUUID());check(isolatedRoot.mkdirs(),"Isolated cache directory");
            Context isolated=new ContextWrapper(getTargetContext()){@Override public File getFilesDir(){return isolatedRoot;}@Override public File getCacheDir(){return isolatedRoot;}};
            byte[][] encoded=new byte[1][];
            runOnMainSync(()->{try{
                LaunchScreen splash=new LaunchScreen(getTargetContext());splash.layout(0,0,720,1440);int frames=0;
                for(float time:new float[]{.24f,.62f,1.05f,1.42f}){Bitmap bitmap=Bitmap.createBitmap(720,1440,Bitmap.Config.ARGB_8888);splash.renderAt(new Canvas(bitmap),time);try(FileOutputStream output=new FileOutputStream(new File(isolatedRoot,"launch-"+(frames++)+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}bitmap.recycle();}
                Bitmap fixture=Bitmap.createBitmap(16,8,Bitmap.Config.ARGB_8888);fixture.eraseColor(Color.BLUE);try(ByteArrayOutputStream output=new ByteArrayOutputStream()){fixture.compress(Bitmap.CompressFormat.PNG,100,output);encoded[0]=output.toByteArray();}fixture.recycle();report.put("splashFrames",frames);
            }catch(Exception e){throw new RuntimeException(e);}});
            int filesBefore=isolatedRoot.list().length;byte[] display=CloudReader.renderMemory(encoded[0],4096);Bitmap decoded=BitmapFactory.decodeByteArray(display,0,display.length);check(decoded.getWidth()==16&&decoded.getHeight()==8,"Memory preview decode");decoded.recycle();check(isolatedRoot.list().length==filesBefore,"Memory rendering writes no files");report.put("memoryPreviewNoFiles",true);
            android.net.Uri pending=PhoneDownloads.createPending(getTargetContext(),"LifeMoments-QA-"+java.util.UUID.randomUUID()+".png");
            try {try(OutputStream output=getTargetContext().getContentResolver().openOutputStream(pending,"w")){output.write(encoded[0]);}try(InputStream input=getTargetContext().getContentResolver().openInputStream(pending)){check(LibraryStore.read(input,1024*1024).length==encoded[0].length,"MediaStore exact bytes");}try(android.database.Cursor cursor=getTargetContext().getContentResolver().query(pending,new String[]{android.provider.MediaStore.Images.Media.IS_PENDING},null,null,null)){check(cursor!=null&&cursor.moveToFirst()&&cursor.getInt(0)==1,"Unpublished download stays hidden");}}finally{getTargetContext().getContentResolver().delete(pending,null,null);}
            report.put("downloadPendingWriteCleanup",true);
            JSONObject photo=new JSONObject().put("cloudPath","/qa/fixture.jpg").put("size",42).put("time","2022-01-01");
            File legacy=new File(isolatedRoot,"photos-v1");check(legacy.mkdirs(),"Legacy cache directory");
            File legacyThumb=new File(legacy,Protocol.digest("|/qa/fixture.jpg|42|2022-01-01")+"-thumb.jpg");
            try(FileOutputStream output=new FileOutputStream(legacyThumb)){output.write(new byte[]{1,2,3,4});}
            CloudReader cloud=new CloudReader(isolated);File migrated=cloud.cached(photo,false);
            check(migrated.getParentFile().getName().equals("thumbnails-v2")&&migrated.length()==4,"Legacy thumbnail migrated");
            // No credentials exist here. These reads can only succeed through persistent local cache.
            check(!cloud.connected()&&cloud.image(photo,false).length()==4,"Offline cache hit");
            CloudReader restarted=new CloudReader(isolated);check(restarted.image(photo,false).length()==4,"Cache survives client restart");
            check(restarted.thumbnailCacheBytes()==4&&restarted.cacheBytes()==4,"Separate cache accounting");
            try(InputStream held=restarted.readThumbnail(photo)){
                check(held!=null,"Native cache opens a stream before returning to WebView");
                restarted.clearCache();check(!migrated.exists()&&new LibraryStore(getTargetContext()).get()!=null,"Explicit cache clearing keeps real index");
                check(LibraryStore.read(held,32).length==4,"Open thumbnail stream survives later cache deletion");
            }
            check(restarted.cacheBytes()==0&&restarted.thumbnailCacheBytes()==0,"Cache counters reset after clear");
            report.put("thumbnailStreamDeleteIsolation",true);
            report.put("thumbnailMigration",true).put("thumbnailOfflineRestart",true).put("cacheIsolation",true).put("status","PASS");
        }catch(Throwable e){try{report.put("status","FAIL").put("error",e.getClass().getSimpleName()+": "+e.getMessage());}catch(Exception ignored){}}
        result.putString("stream",report.toString()+"\n");finish(-1,result);
    }
}
