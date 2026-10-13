package com.lifemoments.reader;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Same production socket wire, with isolated fake settings. No NSD advertisement or user connection is opened. */
final class TransferWireFixture {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static int run() throws Exception {
        byte[] expected = new byte[TransferCrypto.MAX_FRAME * 2 + 117];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i % 251);
        byte[] fakeToken = "not-a-real-token-fixture".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(fakeToken, 0, expected, 0, fakeToken.length);
        long expires = System.nanoTime() / 1000000 + 15000;
        CompletableFuture<Boolean> oldApproval = new CompletableFuture<>(), newApproval = new CompletableFuture<>();
        CompletableFuture<String> oldCode = new CompletableFuture<>(), newCode = new CompletableFuture<>();
        CountDownLatch ready = new CountDownLatch(2);
        AtomicBoolean sent = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ByteArrayOutputStream traffic = new ByteArrayOutputStream();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(5000);
            Future<Boolean> source = pool.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    OutputStream wire = socket.getOutputStream();
                    DataOutputStream out = new DataOutputStream(new OutputStream() {
                        @Override public void write(int value) throws java.io.IOException { wire.write(value); traffic.write(value); }
                        @Override public void write(byte[] data, int offset, int size) throws java.io.IOException { wire.write(data, offset, size); traffic.write(data, offset, size); }
                        @Override public void flush() throws java.io.IOException { wire.flush(); }
                    });
                    try (TransferCrypto.Session session = TransferWire.handshake(in, out, true, expires, () -> System.nanoTime() / 1000000)) {
                        oldCode.complete(session.code); ready.countDown();
                        oldApproval.get(5, TimeUnit.SECONDS); TransferWire.confirm(in, out, session);
                        sent.set(true); TransferWire.sendPayload(out, session, expected);
                        byte[] ack = TransferWire.readFrame(in, session); check(ack.length == 1 && ack[0] == 3, "Import ack authenticated");
                        return true;
                    }
                }
            });
            Future<Boolean> destination = pool.submit(() -> {
                try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                    socket.setSoTimeout(5000);
                    DataInputStream in = new DataInputStream(socket.getInputStream()); DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    try (TransferCrypto.Session session = TransferWire.handshake(in, out, false, expires, () -> System.nanoTime() / 1000000)) {
                        newCode.complete(session.code); ready.countDown();
                        newApproval.get(5, TimeUnit.SECONDS); TransferWire.confirm(in, out, session);
                        byte[] actual = TransferWire.receivePayload(in, session);
                        check(Arrays.equals(actual, expected), "Encrypted multi-frame payload byte exact");
                        TransferWire.sendFrame(out, session, new byte[]{3}); return true;
                    }
                }
            });
            check(ready.await(5, TimeUnit.SECONDS), "Both handshakes finished");
            check(oldCode.get().equals(newCode.get()), "Both screen codes match");
            check(!sent.get(), "No payload before visible confirmation");
            oldApproval.complete(true); Thread.sleep(100);
            check(!sent.get(), "Old device confirmation alone cannot send");
            newApproval.complete(true);
            check(destination.get(8, TimeUnit.SECONDS), "Receiver completed"); check(source.get(8, TimeUnit.SECONDS), "Source received success ack");
            check(!contains(traffic.toByteArray(), fakeToken), "No plaintext token on wire");
            check(TransferWire.samePrefix(InetAddress.getByName("192.168.1.3").getAddress(), InetAddress.getByName("192.168.1.9").getAddress(), 24), "IPv4 local prefix");
            check(!TransferWire.samePrefix(InetAddress.getByName("192.168.1.3").getAddress(), InetAddress.getByName("192.168.2.9").getAddress(), 24), "IPv4 foreign prefix");
            check(TransferWire.samePrefix(InetAddress.getByName("2001:db8:1234:5678::1").getAddress(), InetAddress.getByName("2001:db8:1234:5678::2").getAddress(), 64), "IPv6 global local prefix");
            check(!TransferWire.samePrefix(new byte[4], new byte[16], 24) && !TransferWire.samePrefix(new byte[4], new byte[4], 0), "Mixed family and default route rejected");
            return 13;
        } finally { oldApproval.complete(false); newApproval.complete(false); pool.shutdownNow(); }
    }
    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i+j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
}
