package com.lifemoments.reader;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/** Isolated settings fixtures: never reads or overwrites the real vault/index/preferences. */
public final class ReaderTransferProbe extends Instrumentation {
    private int checks;
    private interface Attempt { void run() throws Exception; }
    private void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private void reject(Attempt action, String label) throws Exception {
        boolean failed = false; try { action.run(); } catch (Exception expected) { failed = true; } check(failed, label);
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        JSONObject report = new JSONObject(); Bundle result = new Bundle(); File root = null; CredentialVault vault = null;
        try {
            JSONObject auth = new JSONObject().put("key", "fixture-key").put("secret", "fixture-secret").put("folder", "/apps/fixture/索引")
                    .put("root", "/照片").put("session", "fixture-session").put("accessToken", "fixture-access-token")
                    .put("refreshToken", "fixture-refresh-token").put("expiresAt", System.currentTimeMillis() + 3600000);
            JSONObject index = new JSONObject().put("schemaVersion", 2).put("libraryId", "fixture-library").put("cloudRoot", "/照片")
                    .put("photos", new JSONArray()).put("index", new JSONObject().put("people", new JSONArray()).put("albums", new JSONArray()));
            JSONObject reader = new JSONObject().put("wifiImages", true).put("thumbnailWarmupAuto", false).put("snapshotSession", "fixture-session");
            JSONObject web = new JSONObject().put("schemaVersion", 1).put("preferences", new JSONObject().put("sort", "name-asc").put("group", "month").put("albumSort", "count"));
            TransferPayload payload = new TransferPayload(reader, web, auth, index), decoded = TransferPayload.decode(payload.encode());
            check(decoded.credentials().getString("accessToken").equals("fixture-access-token"), "Encrypted channel payload token roundtrip");
            check(decoded.readerSettings().length() == 5 && decoded.readerSettings().getString("lastSync").isEmpty(), "Reader defaults and exact five fields");
            check(decoded.webSettings().getJSONObject("preferences").getString("sort").equals("name-asc"), "UI preference roundtrip");
            check(decoded.library().getString("libraryId").equals("fixture-library"), "Validated index roundtrip");
            JSONObject copied = decoded.credentials(); copied.put("secret", "changed"); check(decoded.credentials().getString("secret").equals("fixture-secret"), "Defensive credential copies");
            reject(() -> new TransferPayload(new JSONObject().put("debugCrashAt", 1), web, auth, index), "Internal reader setting rejected");
            reject(() -> new TransferPayload(new JSONObject().put("wifiImages", "true"), web, auth, index), "Wrong reader type rejected");
            reject(() -> new TransferPayload(reader, new JSONObject().put("schemaVersion", 2).put("preferences", new JSONObject()), auth, index), "Bad UI schema rejected");
            reject(() -> new TransferPayload(reader, new JSONObject(web.toString()).put("clientSecret", "fake"), auth, index), "UI extra field rejected");
            reject(() -> new TransferPayload(reader, new JSONObject().put("schemaVersion", 1).put("preferences", new JSONObject().put("sort", "eval")), auth, index), "Unsupported UI enum rejected");
            reject(() -> new TransferPayload(reader, web, new JSONObject(auth.toString()).put("extra", "fake"), index), "Unknown credentials rejected");
            reject(() -> new TransferPayload(reader, web, new JSONObject(auth.toString()).put("expiresAt", "tomorrow"), index), "Expiry type rejected");
            reject(() -> new TransferPayload(reader, web, new JSONObject(auth.toString()).put("root", "/different"), index), "Index root mismatch rejected");
            reject(() -> new TransferPayload(new JSONObject(reader.toString()).put("snapshotSession", "other"), web, auth, index), "Index authorization mismatch rejected");
            reject(() -> TransferPayload.decode(gzip(new JSONObject().put("schemaVersion", 2).toString().getBytes(StandardCharsets.UTF_8))), "Transfer schema rejected");
            reject(() -> TransferPayload.decode(new byte[TransferPayload.MAX_WIRE + 1]), "Compressed size limit enforced before inflate");
            reject(() -> TransferPayload.inflate(gzip(new byte[65537]), 65536), "Same bounded inflate rejects compression bomb");
            check(new TransferPayload(new JSONObject(), new JSONObject(), new JSONObject(), null).credentials().length() == 0, "Unconnected device allowed");
            report.put("payloadChecks", checks);
            root = new File(getTargetContext().getCacheDir(), "transfer-qa-" + java.util.UUID.randomUUID()); check(root.mkdirs(), "Fixture folder created");
            final File isolatedRoot = root;
            Context isolated = new ContextWrapper(getTargetContext()) { @Override public File getFilesDir() { return isolatedRoot; } };
            vault = new CredentialVault(isolated); vault.importFromTransfer(auth);
            check(vault.read().getString("secret").equals("fixture-secret"), "This device Keystore encrypted import readable");
            byte[] encrypted = java.nio.file.Files.readAllBytes(new File(root, "credentials.enc").toPath());
            check(!new String(encrypted, StandardCharsets.ISO_8859_1).contains("fixture-secret"), "Vault file contains ciphertext");
            check(vault.exportForTransfer().getString("refreshToken").equals("fixture-refresh-token"), "Native export includes complete connection");
            final CredentialVault checkedVault = vault;
            reject(() -> checkedVault.importFromTransfer(new JSONObject(auth.toString()).put("shell", "fake")), "Invalid import rejected before write");
            check(vault.read().getString("secret").equals("fixture-secret"), "Rejected import preserves previous vault");
            report.put("vaultImportReencrypted", true);
            AtomicLong clock = new AtomicLong(1);
            TransferCrypto.Hello first = new TransferCrypto.Hello(), second = new TransferCrypto.Hello();
            try (TransferCrypto.Session sender = TransferCrypto.establish(first, second.encoded(), true, 9, clock::get);
                 TransferCrypto.Session receiver = TransferCrypto.establish(second, first.encoded(), false, 9, clock::get)) {
                check(sender.code.equals(receiver.code), "Android provider paired screen codes");
                byte[] frame = sender.seal(new byte[]{1,2,3}), damaged = frame.clone(); damaged[damaged.length-1] ^= 1;
                reject(() -> receiver.open(damaged), "Android GCM rejects tamper");
                check(Arrays.equals(receiver.open(frame), new byte[]{1,2,3}), "Android ECDH/AES roundtrip");
                clock.set(9); reject(() -> sender.seal(new byte[]{1}), "Android expiration enforced");
            }
            report.put("productionWireChecks", TransferWireFixture.run()).put("checks", checks).put("status", "PASS");
        } catch (Throwable e) {
            try { report.put("status", "FAIL").put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); } catch (Exception ignored) { }
        } finally { if (vault != null) vault.clear(); if (root != null) root.delete(); }
        result.putString("stream", report.toString() + "\n"); finish(-1, result);
    }
    private static byte[] gzip(byte[] input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (GZIPOutputStream out = new GZIPOutputStream(bytes)) { out.write(input); } return bytes.toByteArray();
    }
}
