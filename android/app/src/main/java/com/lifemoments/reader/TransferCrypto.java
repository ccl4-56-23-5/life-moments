package com.lifemoments.reader;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.LongSupplier;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Ephemeral, committed ECDH pairing. No Android API, so the wire rules have desktop fixtures. */
final class TransferCrypto {
    static final int MAX_FRAME = 64 * 1024;
    static final class Hello {
        final KeyPair keys;
        final byte[] nonce = new byte[32];
        Hello() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            keys = generator.generateKeyPair();
            new SecureRandom().nextBytes(nonce);
        }
        byte[] encoded() throws IOException { return encode(keys.getPublic().getEncoded(), nonce); }
        byte[] commitment() throws Exception { return hash(encoded()); }
    }
    static byte[] encode(byte[] publicKey, byte[] nonce) throws IOException {
        if (publicKey.length < 64 || publicKey.length > 256 || nonce.length != 32)
            throw new IOException("配对数据无效。");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(publicKey.length); out.write(publicKey); out.write(nonce);
        }
        return bytes.toByteArray();
    }
    static byte[] hash(byte[] input) throws Exception { return MessageDigest.getInstance("SHA-256").digest(input); }
    static boolean committed(byte[] commitment, byte[] reveal) throws Exception {
        return commitment.length == 32 && MessageDigest.isEqual(commitment, hash(reveal));
    }
    static Session establish(Hello own, byte[] peerHello, boolean sender, long expiresAt, LongSupplier clock) throws Exception {
        ByteBuffer peer = ByteBuffer.wrap(peerHello);
        if (peer.remaining() < 4) throw new IOException("配对数据无效。");
        int size = peer.getInt();
        if (size < 64 || size > 256 || peer.remaining() != size + 32) throw new IOException("配对数据无效。");
        byte[] publicBytes = new byte[size]; peer.get(publicBytes);
        ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicBytes));
        ECPublicKey expected = (ECPublicKey) own.keys.getPublic();
        if (!key.getParams().getCurve().equals(expected.getParams().getCurve())
                || !key.getParams().getGenerator().equals(expected.getParams().getGenerator())
                || !key.getParams().getOrder().equals(expected.getParams().getOrder())
                || key.getParams().getCofactor() != expected.getParams().getCofactor())
            throw new IOException("配对密钥无效。");
        byte[] local = own.encoded();
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        transcript.write("LifeMoments transfer v1".getBytes(StandardCharsets.US_ASCII));
        transcript.write(sender ? local : peerHello); transcript.write(sender ? peerHello : local);
        byte[] context = hash(transcript.toByteArray());
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(own.keys.getPrivate()); agreement.doPhase(key, true);
        byte[] shared = agreement.generateSecret();
        byte[] secret;
        try { secret = hmac(context, shared); } finally { Arrays.fill(shared, (byte) 0); }
        byte[] s2r = expand(secret, "sender to receiver", context);
        byte[] r2s = expand(secret, "receiver to sender", context);
        byte[] digits = hmac(secret, "screen verification".getBytes(StandardCharsets.US_ASCII));
        long number = ByteBuffer.wrap(digits).getInt() & 0xffffffffL;
        Arrays.fill(secret, (byte) 0);
        return new Session(sender ? s2r : r2s, sender ? r2s : s2r,
                context, sender, String.format(Locale.ROOT, "%06d", number % 1000000), expiresAt, clock);
    }
    private static byte[] hmac(byte[] key, byte[] input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(input);
    }
    private static byte[] expand(byte[] key, String label, byte[] context) throws Exception {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write(label.getBytes(StandardCharsets.US_ASCII)); input.write(context); input.write(1);
        return hmac(key, input.toByteArray());
    }
    static final class Session implements AutoCloseable {
        private final byte[] sendKey, receiveKey, context;
        private final boolean sender;
        private final long expiresAt;
        private final LongSupplier clock;
        final String code;
        private long sent, received;
        private boolean closed;
        Session(byte[] sendKey, byte[] receiveKey, byte[] context, boolean sender, String code, long expiresAt, LongSupplier clock) {
            this.sendKey = sendKey; this.receiveKey = receiveKey; this.context = context;
            this.sender = sender; this.code = code; this.expiresAt = expiresAt; this.clock = clock;
        }
        void active() throws IOException {
            if (closed || clock.getAsLong() >= expiresAt) throw new IOException("配对已过期，请重新开始。");
        }
        synchronized byte[] seal(byte[] plaintext) throws Exception {
            active(); if (plaintext.length > MAX_FRAME || sent == Long.MAX_VALUE) throw new IOException("传输数据过大。");
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, sendKey, sent, sender);
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] frame = new byte[8 + ciphertext.length];
            ByteBuffer.wrap(frame).putLong(sent++).put(ciphertext);
            return frame;
        }
        synchronized byte[] open(byte[] frame) throws Exception {
            active(); if (frame.length < 24 || frame.length > MAX_FRAME + 24) throw new IOException("传输数据无效。");
            ByteBuffer data = ByteBuffer.wrap(frame); long sequence = data.getLong();
            if (sequence != received || received == Long.MAX_VALUE) throw new IOException("传输顺序无效，请重新配对。");
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, receiveKey, sequence, !sender);
            byte[] plaintext = cipher.doFinal(frame, 8, frame.length - 8); received++;
            return plaintext;
        }
        private Cipher cipher(int mode, byte[] key, long sequence, boolean direction) throws Exception {
            // Each direction has its own derived key. Unique counter nonce and authenticated transcript prevent replay.
            byte[] nonce = ByteBuffer.allocate(12).putInt(direction ? 1 : 0).putLong(sequence).array();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context); cipher.updateAAD(new byte[]{(byte) (direction ? 1 : 0)});
            return cipher;
        }
        @Override public synchronized void close() {
            closed = true; Arrays.fill(sendKey, (byte) 0); Arrays.fill(receiveKey, (byte) 0); Arrays.fill(context, (byte) 0);
        }
    }
}
