package io.yu.flash.core;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Streaming SHA-256; never treats a short read or premature EOF as an exact-range match. */
public final class ExactRangeDigest {
    private ExactRangeDigest() { }
    public static final class Result {
        public final long bytes;
        public final String sha256;
        Result(long bytes, String sha256) { this.bytes = bytes; this.sha256 = sha256; }
    }
    public static Result full(InputStream input) throws IOException { return digest(input, -1); }
    public static Result prefix(InputStream input, long bytes) throws IOException {
        if (bytes < 0) throw new IOException("Negative readback length");
        return digest(input, bytes);
    }
    private static Result digest(InputStream input, long limit) throws IOException {
        final MessageDigest hash;
        try { hash = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        byte[] buffer = new byte[1024 * 1024];
        long total = 0;
        while (limit < 0 || total < limit) {
            int size = limit < 0 ? buffer.length : (int) Math.min(buffer.length, limit - total);
            int n = input.read(buffer, 0, size);
            if (n < 0) {
                if (limit >= 0) throw new IOException("Premature EOF in readback: " + total + "/" + limit);
                break;
            }
            if (n == 0) {
                int one = input.read();
                if (one < 0) {
                    if (limit >= 0) throw new IOException("Premature EOF in readback: " + total + "/" + limit);
                    break;
                }
                hash.update((byte) one);
                n = 1;
            } else {
                hash.update(buffer, 0, n);
            }
            if (total > Long.MAX_VALUE - n) throw new IOException("Byte count overflow");
            total += n;
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : hash.digest()) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        return new Result(total, hex.toString());
    }
}
