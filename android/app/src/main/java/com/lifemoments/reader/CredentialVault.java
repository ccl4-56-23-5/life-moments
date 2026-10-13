package com.lifemoments.reader;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CredentialVault {
    private final AtomicFile file;
    private static final String ALIAS = "LifeMomentsBaiduV1";
    CredentialVault(Context context) { file = new AtomicFile(new File(context.getFilesDir(), "credentials.enc")); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS, null);
    }
    synchronized JSONObject read() throws Exception {
        if (!file.getBaseFile().exists()) return new JSONObject();
        byte[] data = file.readFully();
        if (data.length < 29) throw new IllegalStateException("授权凭据损坏，请重新连接。");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, java.util.Arrays.copyOfRange(data, 0, 12)));
        return new JSONObject(new String(cipher.doFinal(data, 12, data.length-12), StandardCharsets.UTF_8));
    }
    synchronized void write(JSONObject value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(cipher.getIV()); stream.write(encrypted); file.finishWrite(stream);
        } catch (Exception e) { if (stream != null) file.failWrite(stream); throw e; }
    }
    synchronized void clear() { file.delete(); }
    /** Transfer plaintext exists only inside the authenticated native channel, never in WebView or a file. */
    synchronized JSONObject exportForTransfer() throws Exception { return new JSONObject(read().toString()); }
    /** Always encrypt with this device's Android Keystore; exporting its key or encrypted file cannot work. */
    synchronized void importFromTransfer(JSONObject value) throws Exception {
        JSONObject checked=TransferPayload.validateCredentials(new JSONObject(value.toString()));
        if(checked.length()==0)clear();else write(checked);
    }
}
