package com.lifemoments.reader;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** Desktop fixtures exercise the same primitives packaged in Android, without real credentials or networking. */
public final class TransferCryptoTest {
    private static int checks;
    private interface Attempt { void run() throws Exception; }
    private static void check(boolean value, String label) { checks++; if(!value)throw new AssertionError(label); }
    private static void reject(Attempt attempt, String label) throws Exception {
        boolean rejected=false;try{attempt.run();}catch(Exception expected){rejected=true;}check(rejected,label);
    }
    public static void main(String[] args) throws Exception {
        AtomicLong time = new AtomicLong(100);
        TransferCrypto.Hello old = new TransferCrypto.Hello(), fresh = new TransferCrypto.Hello();
        check(!Arrays.equals(old.commitment(), fresh.commitment()), "Ephemeral hello differs per device");
        check(TransferCrypto.committed(old.commitment(), old.encoded()), "Committed hello verified");
        byte[] changed = old.encoded(); changed[changed.length-1]^=1;
        check(!TransferCrypto.committed(old.commitment(), changed), "Changed reveal rejected");
        try (TransferCrypto.Session sender = TransferCrypto.establish(old, fresh.encoded(), true, 200, time::get);
             TransferCrypto.Session receiver = TransferCrypto.establish(fresh, old.encoded(), false, 200, time::get)) {
            check(sender.code.equals(receiver.code) && sender.code.matches("[0-9]{6}"), "Both screens have same six digits");
            byte[] secret = "fixture token, Chinese directory /照片".getBytes(StandardCharsets.UTF_8);
            byte[] first = sender.seal(secret);
            check(!new String(first, StandardCharsets.ISO_8859_1).contains("fixture token"), "Payload ciphertext hides token");
            check(Arrays.equals(receiver.open(first), secret), "Sender payload roundtrip");
            reject(() -> receiver.open(first), "Replayed sequence rejected");
            byte[] second = sender.seal(new byte[]{2,3}); byte[] damaged = second.clone(); damaged[damaged.length-1]^=1;
            reject(() -> receiver.open(damaged), "GCM tag rejects mutation");
            check(Arrays.equals(receiver.open(second), new byte[]{2,3}), "Rejected tag does not consume sequence");
            byte[] third = sender.seal(new byte[]{4}), fourth=sender.seal(new byte[]{5});
            reject(() -> receiver.open(fourth), "Skipped frame rejected");
            check(receiver.open(third)[0]==4 && receiver.open(fourth)[0]==5, "Ordered stream continues");
            byte[] response=receiver.seal(new byte[]{1});check(sender.open(response)[0]==1, "Receiver approval direction roundtrip");
            reject(() -> receiver.open(response), "Reflected direction rejected");
            reject(() -> sender.seal(new byte[TransferCrypto.MAX_FRAME+1]), "Oversized plaintext rejected");
            reject(() -> receiver.open(new byte[23]), "Short frame rejected");
            time.set(200);reject(() -> sender.seal(new byte[]{0}), "Expired session cannot send");
            reject(() -> receiver.open(fourth), "Expired session cannot receive");
        }
        time.set(100);
        TransferCrypto.Session disposed = TransferCrypto.establish(old,fresh.encoded(),true,200,time::get);
        disposed.close(); reject(() -> disposed.seal(new byte[]{1}), "Closed session cannot send");
        reject(() -> TransferCrypto.establish(old,new byte[8],true,200,time::get), "Malformed key rejected");
        System.out.println("PASS: "+checks+" transfer crypto checks");
    }
}
