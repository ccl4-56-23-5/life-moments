package com.lifemoments.reader;

import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Only application business preferences, cloud credentials and validated index may cross devices. */
final class TransferPayload {
    static final int MAX_WIRE = 16 * 1024 * 1024;
    static final int MAX_PLAIN = LibraryStore.MAX_INDEX + 128 * 1024;
    private final JSONObject reader, web, auth, index;
    TransferPayload(JSONObject readerSettings, JSONObject webSettings, JSONObject credentials, JSONObject library) throws Exception {
        reader = validateReader(copy(readerSettings)); web = validateWeb(copy(webSettings));
        auth = validateCredentials(copy(credentials));
        index = library == null ? null : LibraryStore.validate(library.toString());
        if (index != null && auth.has("root")) {
            String root = Protocol.path(auth.getString("root"));
            if (!root.equals(index.getString("cloudRoot"))) throw new IOException("连接目录与图库索引不一致，不能迁移。");
        }
        if (!reader.optString("snapshotSession").isEmpty()
                && !reader.optString("snapshotSession").equals(auth.optString("session")))
            throw new IOException("图库与当前网盘授权不一致，请先刷新索引。");
    }
    private static JSONObject copy(JSONObject value) throws Exception { return value == null ? new JSONObject() : new JSONObject(value.toString()); }
    JSONObject readerSettings() throws Exception { return copy(reader); }
    JSONObject webSettings() throws Exception { return copy(web); }
    JSONObject credentials() throws Exception { return copy(auth); }
    JSONObject library() throws Exception { return index == null ? null : copy(index); }
    byte[] encode() throws Exception {
        JSONObject envelope = new JSONObject().put("schemaVersion", 1).put("reader", reader).put("web", web).put("credentials", auth);
        if (index != null) envelope.put("library", index);
        byte[] plaintext = envelope.toString().getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > MAX_PLAIN) throw new IOException("设置与索引超过迁移大小限制。");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream zipped = new GZIPOutputStream(bytes)) { zipped.write(plaintext); }
        java.util.Arrays.fill(plaintext, (byte) 0);
        if (bytes.size() > MAX_WIRE) throw new IOException("压缩索引超过 16 MiB，请在新设备重新拉取索引。");
        return bytes.toByteArray();
    }
    static TransferPayload decode(byte[] bytes) throws Exception {
        if (bytes.length == 0 || bytes.length > MAX_WIRE) throw new IOException("迁移数据大小无效。");
        byte[] plaintext = inflate(bytes, MAX_PLAIN);
        try {
            JSONObject envelope = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            if (!(envelope.get("schemaVersion") instanceof Number) || envelope.getDouble("schemaVersion") != 1) throw new IOException("设备版本不兼容，请更新双方安装包。");
            for (Iterator<String> keys = envelope.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!(key.equals("schemaVersion") || key.equals("reader") || key.equals("web") || key.equals("credentials") || key.equals("library")))
                    throw new IOException("迁移数据包含不支持的字段。");
            }
            if (envelope.has("library") && envelope.optJSONObject("library") == null) throw new IOException("迁移图库格式无效。");
            return new TransferPayload(envelope.getJSONObject("reader"), envelope.getJSONObject("web"),
                    envelope.getJSONObject("credentials"), envelope.optJSONObject("library"));
        } finally { java.util.Arrays.fill(plaintext, (byte) 0); }
    }
    static byte[] inflate(byte[] bytes, int maximum) throws Exception {
        return LibraryStore.read(new GZIPInputStream(new ByteArrayInputStream(bytes)), maximum);
    }
    static JSONObject validateReader(JSONObject value) throws Exception {
        for (Iterator<String> keys = value.keys(); keys.hasNext();) {
            String key = keys.next(); Object field = value.get(key);
            if (key.equals("wifiImages") || key.equals("thumbnailWarmupAuto")) {
                if (!(field instanceof Boolean)) throw new IOException("网络偏好格式无效。");
            } else if (key.equals("snapshotSession") || key.equals("lastSync") || key.equals("lastStatus")) {
                if (!(field instanceof String) || ((String) field).length() > 1024) throw new IOException("图库设置格式无效。");
            } else throw new IOException("迁移中包含不支持的设置。");
        }
        for (String key : new String[]{"wifiImages", "thumbnailWarmupAuto"}) if (!value.has(key)) value.put(key, true);
        for (String key : new String[]{"snapshotSession", "lastSync", "lastStatus"}) if (!value.has(key)) value.put(key, "");
        return value;
    }
    static JSONObject validateWeb(JSONObject value) throws Exception {
        if (value.length() == 0) return new JSONObject().put("schemaVersion", 1).put("preferences", new JSONObject());
        if (!(value.get("schemaVersion") instanceof Number) || value.getDouble("schemaVersion") != 1) throw new IOException("界面设置版本无效。");
        for (Iterator<String> keys = value.keys(); keys.hasNext();) {
            String key = keys.next();
            if (!(key.equals("schemaVersion") || key.equals("preferences"))) throw new IOException("界面设置包含不支持的字段。");
        }
        JSONObject preferences = value.getJSONObject("preferences");
        for (Iterator<String> keys = preferences.keys(); keys.hasNext();) {
            String key = keys.next(); Object field = preferences.get(key);
            if (!(key.equals("sort") || key.equals("group") || key.equals("albumSort"))
                    || !(field instanceof String) || ((String) field).length() > 64) throw new IOException("界面设置格式无效。");
            String allowed = key.equals("sort") ? "time-desc|time-asc|name-asc|name-desc" : key.equals("group") ? "day|month|year" : "created|name|count";
            if (!java.util.Arrays.asList(allowed.split("\\|")).contains(field)) throw new IOException("界面设置值无效。");
        }
        return new JSONObject().put("schemaVersion", 1).put("preferences", preferences);
    }
    static JSONObject validateCredentials(JSONObject value) throws Exception {
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > 32 * 1024) throw new IOException("连接设置过大。");
        for (Iterator<String> keys = value.keys(); keys.hasNext();) {
            String key = keys.next(); Object field = value.get(key);
            if (key.equals("expiresAt")) {
                if (!(field instanceof Number) || value.getLong(key) < 0 || value.getDouble(key) != value.getLong(key)) throw new IOException("授权时间无效。");
            } else if (key.equals("key") || key.equals("secret") || key.equals("folder") || key.equals("root")
                    || key.equals("session") || key.equals("accessToken") || key.equals("refreshToken")) {
                if (!(field instanceof String) || ((String) field).length() > 8192) throw new IOException("连接设置格式无效。");
            } else throw new IOException("连接设置包含不支持的字段。");
        }
        if (value.has("root")) Protocol.path(value.getString("root"));
        if (value.has("folder")) {
            String folder = Protocol.path(value.getString("folder"));
            if (!folder.startsWith("/apps/") || folder.split("/").length < 4) throw new IOException("索引目录无效。");
        }
        if (value.has("accessToken")) for (String field : new String[]{"accessToken", "key", "secret", "root", "folder", "session", "expiresAt"})
            if (!value.has(field) || value.optString(field).isEmpty()) throw new IOException("网盘连接设置不完整。");
        return value;
    }
}
