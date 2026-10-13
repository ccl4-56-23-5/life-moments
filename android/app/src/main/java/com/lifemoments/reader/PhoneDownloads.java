package com.lifemoments.reader;

import android.content.*;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import java.io.*;

/** Only an explicit Download menu action publishes an original into the phone's photo library. */
final class PhoneDownloads {
    interface Writer {void write(OutputStream output)throws Exception;}
    static String mime(String name){int dot=name.lastIndexOf('.');String extension=dot<0?"":name.substring(dot+1).toLowerCase(java.util.Locale.ROOT);String mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);return mime==null?"image/jpeg":mime;}
    @android.annotation.TargetApi(29)
    static Uri createPending(Context context,String name)throws IOException {
        ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name.replace('/', '_').replace('\\','_'));values.put(MediaStore.Images.Media.MIME_TYPE,mime(name));values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/生活点滴");values.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("无法创建手机相册文件。");return uri;
    }
    @android.annotation.TargetApi(29)
    static Uri save(Context context,String name,Writer writer)throws Exception {
        Uri uri=createPending(context,name);ContentResolver resolver=context.getContentResolver();boolean complete=false;
        try {
            try(OutputStream output=resolver.openOutputStream(uri,"w")){if(output==null)throw new IOException("无法写入手机相册。");writer.write(output);}
            ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.IS_PENDING,0);
            if(resolver.update(uri,values,null,null)!=1)throw new IOException("无法完成手机相册保存。");complete=true;return uri;
        }finally{if(!complete)resolver.delete(uri,null,null);}
    }
}
