package com.lifemoments.reader;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

final class LibraryStore {
    static final int MAX_INDEX = 64 * 1024 * 1024;
    private final AtomicFile file;
    private volatile JSONObject snapshot;
    private volatile Map<String, JSONObject> lookup = new HashMap<>();
    LibraryStore(Context context) throws Exception {
        file = new AtomicFile(new File(context.getFilesDir(), "library-v2.json"));
        if (file.getBaseFile().exists()) {
            JSONObject saved = validate(new String(file.readFully(), StandardCharsets.UTF_8)); set(saved);
        }
    }
    static JSONObject validate(String text) throws Exception {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_INDEX) throw new IOException("索引超过 64 MiB。");
        JSONObject doc = new JSONObject(text);
        if (doc.getInt("schemaVersion") != 2) throw new IOException("索引版本不兼容；请用当前电脑端重新导出。");
        if (doc.getString("libraryId").isEmpty()) throw new IOException("索引缺少图库标识。");
        Protocol.path(doc.getString("cloudRoot"));
        JSONArray photos = doc.getJSONArray("photos"); JSONObject index=doc.getJSONObject("index");
        JSONArray people=index.optJSONArray("people"),albums=index.optJSONArray("albums");
        if(people==null||albums==null)throw new IOException("索引人物或相册格式无效。");
        for(int i=0;i<people.length();i++)if(!(people.get(i) instanceof String))throw new IOException("人物名称格式无效。");
        for(String field:new String[]{"profiles","assignments","notes","photoLocations"})
            if(index.has(field)&&index.optJSONObject(field)==null)throw new IOException("索引 "+field+" 格式无效。");
        for(int i=0;i<albums.length();i++) {
            JSONObject album=albums.getJSONObject(i);album.getString("name");
            if(album.optJSONArray("ids")==null)throw new IOException("相册成员格式无效。");
        }
        HashSet<String> ids = new HashSet<>();
        for (int i=0;i<photos.length();i++) {
            JSONObject photo = photos.getJSONObject(i);
            String id = photo.get("id").toString();
            if (!id.matches("[0-9]{1,15}") || Long.parseLong(id) > 281474976710655L || !ids.add(id))
                throw new IOException("索引照片编号重复或无效。");
            Protocol.path(photo.getString("cloudPath"));
            if (photo.optDouble("width", 1) <= 0 || photo.optDouble("height", 1) <= 0) throw new IOException("照片尺寸无效。");
        }
        // Strip untrusted fields that could make the WebView load remote or local script URLs.
        for (int i=0;i<photos.length();i++) {
            JSONObject photo = photos.getJSONObject(i); photo.remove("image"); photo.remove("fullImage"); photo.remove("path");
        }
        return doc;
    }
    synchronized void commit(JSONObject doc) throws Exception {
        FileOutputStream stream = null;
        try { stream=file.startWrite(); stream.write(doc.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); }
        catch (Exception e) { if (stream != null) file.failWrite(stream); throw e; }
        set(doc);
    }
    static JSONObject bindRoot(JSONObject source,String configuredRoot) throws Exception {
        String root=Protocol.path(configuredRoot),old=Protocol.path(source.getString("cloudRoot"));
        if(root.equals(old))return source;
        JSONObject doc=new JSONObject(source.toString());JSONArray photos=doc.getJSONArray("photos");
        for(int i=0;i<photos.length();i++) {
            JSONObject photo=photos.getJSONObject(i);String path=Protocol.path(photo.getString("cloudPath"));
            if(!Protocol.within(path,old)||path.equals(old))throw new IOException("索引原片路径不属于发布目录，不能替换目录前缀。");
            String relative=photo.optString("relativePath",path.substring(old.length()+1)).replace('\\','/');
            if(!Protocol.path(old+"/"+relative).equals(path))throw new IOException("索引的相对路径与完整路径不一致，不能替换目录前缀。");
            photo.put("cloudPath",Protocol.path(root+"/"+relative));
        }
        doc.put("sourceCloudRoot",old);doc.put("cloudRoot",root);return doc;
    }
    private void set(JSONObject doc) throws Exception {
        Map<String, JSONObject> items = new HashMap<>(); JSONArray photos = doc.getJSONArray("photos");
        for(int i=0;i<photos.length();i++) { JSONObject photo=photos.getJSONObject(i); items.put(photo.get("id").toString(), photo); }
        lookup=items; snapshot=doc;
    }
    JSONObject get() { return snapshot; }
    JSONObject photo(String id) { return lookup.get(id); }
    static byte[] read(InputStream stream, int maximum) throws IOException {
        try (InputStream input=stream; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[16384]; int length;
            while ((length=input.read(buffer)) != -1) {
                if (out.size()+length > maximum) throw new IOException("文件超过允许大小。");
                out.write(buffer,0,length);
            }
            return out.toByteArray();
        }
    }
}
