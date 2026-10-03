package io.yu.flash.core;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** A bounded full-image transfer. Implementations must keep both endpoints pinned until return. */
public final class VerifiedCopy {
    private VerifiedCopy() {}
    public interface Endpoint {
        int read(byte[] buffer, int offset, int length) throws IOException;
        int write(byte[] buffer, int offset, int length) throws IOException;
        void rewind() throws IOException;
        void sync() throws IOException;
    }
    public interface Observer { void phase(String phase, long bytes) throws IOException; }
    public interface Guard { void check() throws IOException; }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private static String hash(Endpoint endpoint, long length) throws IOException {
        endpoint.rewind(); MessageDigest hash = digest(); byte[] buffer = new byte[1024 * 1024];
        long done = 0;
        while (done < length) {
            int count = endpoint.read(buffer, 0, (int) Math.min(buffer.length, length - done));
            if (count <= 0) throw new IOException("Short or stalled input during hash");
            hash.update(buffer, 0, count); done += count;
        }
        return hex(hash.digest());
    }
    public static String execute(Endpoint source, Endpoint target, long length, String imageHash,
                                 String backupHash, Guard guard, Observer observer) throws IOException {
        if (length <= 0 || !imageHash.matches("[a-f0-9]{64}") || !backupHash.matches("[a-f0-9]{64}"))
            throw new IOException("Invalid transfer parameters");
        observer.phase("RECHECK", 0);
        guard.check();
        if (!hash(source, length).equals(imageHash)) throw new IOException("Source SHA-256 changed");
        if (!hash(target, length).equals(backupHash)) throw new IOException("Target no longer matches full backup");
        guard.check();
        source.rewind(); target.rewind();
        observer.phase("WRITING", 0); // Must persist BEFORE the first output byte.
        byte[] buffer = new byte[1024 * 1024]; long done = 0;
        MessageDigest written = digest();
        while (done < length) {
            int n = source.read(buffer, 0, (int) Math.min(buffer.length, length - done));
            if (n <= 0) throw new IOException("Source truncated during write; target may be partial");
            int offset = 0;
            while (offset < n) {
                int count = target.write(buffer, offset, n - offset);
                if (count <= 0) throw new IOException("Short or stalled write; target may be partial");
                offset += count;
            }
            written.update(buffer, 0, n); done += n;
        }
        observer.phase("SYNCING", done); target.sync();
        if (!hex(written.digest()).equals(imageHash)) throw new IOException("Source changed during write");
        observer.phase("READBACK", 0);
        String readback = hash(target, length);
        if (!readback.equals(imageHash)) throw new IOException("Readback SHA-256 mismatch");
        return readback; // No automatic retry, rollback, reboot or slot change.
    }
}
