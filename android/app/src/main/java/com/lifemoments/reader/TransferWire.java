package com.lifemoments.reader;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.LongSupplier;

/** Actual socket protocol; only keys/nonces are cleartext, every approval/data/ack frame is authenticated. */
final class TransferWire {
    private static final long MAGIC = 0x4c4d5452414e5331L;
    static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    static boolean samePrefix(byte[] first, byte[] second, int bits) {
        if (first.length != second.length || bits < 1 || bits > first.length * 8) return false;
        int whole = bits / 8, partial = bits % 8;
        for (int i = 0; i < whole; i++) if (first[i] != second[i]) return false;
        return partial == 0 || ((first[whole] ^ second[whole]) & (0xff << (8 - partial))) == 0;
    }
    static TransferCrypto.Session handshake(DataInputStream in, DataOutputStream out, boolean sender,
                                           long expiresAt, LongSupplier clock) throws Exception {
        TransferCrypto.Hello hello = new TransferCrypto.Hello(); byte[] peerCommit;
        if (sender) {
            if (in.readLong() != MAGIC) throw new IOException("设备版本不兼容。"); peerCommit = fixed(in, 32);
            out.writeLong(MAGIC); out.write(hello.commitment()); out.flush();
        } else {
            out.writeLong(MAGIC); out.write(hello.commitment()); out.flush();
            if (in.readLong() != MAGIC) throw new IOException("设备版本不兼容。"); peerCommit = fixed(in, 32);
        }
        byte[] peerReveal;
        if (sender) { peerReveal = readSized(in, 292); writeSized(out, hello.encoded()); }
        else { writeSized(out, hello.encoded()); peerReveal = readSized(in, 292); }
        if (!TransferCrypto.committed(peerCommit, peerReveal)) throw new IOException("配对校验失败，请重新开始。");
        return TransferCrypto.establish(hello, peerReveal, sender, expiresAt, clock);
    }
    /** The caller must obtain this device's visible confirmation before calling; waits for the other approval. */
    static void confirm(DataInputStream in, DataOutputStream out, TransferCrypto.Session session) throws Exception {
        sendFrame(out, session, new byte[]{1}); byte[] answer = readFrame(in, session);
        if (answer.length != 1 || answer[0] != 1) throw new IOException("另一台设备未确认，设置未发送。");
    }
    static void sendPayload(DataOutputStream out, TransferCrypto.Session session, byte[] bytes) throws Exception {
        if (bytes.length < 1 || bytes.length > MAX_PAYLOAD) throw new IOException("迁移数据超过大小限制。");
        sendFrame(out, session, ByteBuffer.allocate(5).put((byte) 2).putInt(bytes.length).array());
        for (int offset = 0; offset < bytes.length; offset += TransferCrypto.MAX_FRAME) {
            byte[] chunk = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + TransferCrypto.MAX_FRAME));
            try { sendFrame(out, session, chunk); } finally { Arrays.fill(chunk, (byte) 0); }
        }
    }
    static byte[] receivePayload(DataInputStream in, TransferCrypto.Session session) throws Exception {
        byte[] header = readFrame(in, session);
        if (header.length != 5 || header[0] != 2) throw new IOException("迁移数据格式无效。");
        int total = ByteBuffer.wrap(header, 1, 4).getInt();
        if (total < 1 || total > MAX_PAYLOAD) throw new IOException("迁移数据超过大小限制。");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(total, 1024 * 1024))) {
            while (bytes.size() < total) {
                byte[] chunk = readFrame(in, session);
                try {
                    if (chunk.length == 0 || chunk.length > total - bytes.size()) throw new IOException("迁移数据大小无效。");
                    bytes.write(chunk);
                } finally { Arrays.fill(chunk, (byte) 0); }
            }
            return bytes.toByteArray();
        }
    }
    static void sendFrame(DataOutputStream out, TransferCrypto.Session session, byte[] bytes) throws Exception { writeSized(out, session.seal(bytes)); }
    static byte[] readFrame(DataInputStream in, TransferCrypto.Session session) throws Exception { return session.open(readSized(in, TransferCrypto.MAX_FRAME + 24)); }
    private static byte[] fixed(DataInputStream in, int size) throws IOException { byte[] bytes = new byte[size]; in.readFully(bytes); return bytes; }
    private static byte[] readSized(DataInputStream in, int max) throws IOException {
        int size = in.readInt(); if (size < 1 || size > max) throw new IOException("迁移数据大小无效。"); return fixed(in, size);
    }
    private static void writeSized(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); out.flush(); }
}
