package io.yu.flash.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.*;

/** In-memory streams only: no filesystem, native commands, or block devices are opened. */
public class ExactRangeDigestTest {
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static final class MemoryStream extends InputStream {
        final byte[] data;
        int position;
        int bulkCalls;
        int singleCalls;
        int maxChunk = Integer.MAX_VALUE;
        boolean zeroBulkReads;
        boolean rejectEofProbe;

        MemoryStream(byte[] data) { this.data = data; }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            assertTrue("positive bounded read required", length > 0 && offset >= 0 && offset + length <= buffer.length);
            bulkCalls++;
            if (zeroBulkReads) return 0;
            if (position == data.length) {
                if (rejectEofProbe) throw new IOException("unexpected read beyond requested prefix");
                return -1;
            }
            int count = Math.min(length, Math.min(maxChunk, data.length - position));
            System.arraycopy(data, position, buffer, offset, count);
            position += count;
            return count;
        }

        @Override public int read() throws IOException {
            singleCalls++;
            if (position == data.length) {
                if (rejectEofProbe) throw new IOException("unexpected read beyond requested prefix");
                return -1;
            }
            return data[position++] & 0xff;
        }
    }

    private interface CheckedOperation { void run() throws IOException; }

    private static IOException expectIOException(CheckedOperation operation) {
        try {
            operation.run();
            fail("operation must fail with IOException");
            throw new AssertionError("unreachable");
        } catch (IOException expected) {
            return expected;
        }
    }

    private static byte[] patternedBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31 + (i >>> 8));
        return bytes;
    }

    private static String expectedHash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void assertDigest(ExactRangeDigest.Result result, long bytes, String hash) {
        assertEquals(bytes, result.bytes);
        assertEquals(hash, result.sha256);
        assertTrue(result.sha256.matches("[0-9a-f]{64}"));
    }

    @Test public void fullEmptyStreamReturnsKnownEmptyDigest() throws Exception {
        assertDigest(ExactRangeDigest.full(new ByteArrayInputStream(new byte[0])), 0, EMPTY_SHA256);
    }

    @Test public void fullAsciiStreamReturnsKnownSha256() throws Exception {
        assertDigest(ExactRangeDigest.full(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))),
                3, ABC_SHA256);
    }

    @Test public void fullConsumesAllChunksUntilEofIncludingFinalShortChunk() throws Exception {
        byte[] bytes = patternedBytes(2 * 1024 * 1024 + 37);
        MemoryStream input = new MemoryStream(bytes);
        input.maxChunk = 8191;
        assertDigest(ExactRangeDigest.full(input), bytes.length, expectedHash(bytes));
        assertEquals(bytes.length, input.position);
        assertTrue(input.bulkCalls > 2);
        assertEquals(0, input.singleCalls);
    }

    @Test public void fullDoesNotInterpretAShortReadAsEof() throws Exception {
        byte[] bytes = patternedBytes(257);
        MemoryStream input = new MemoryStream(bytes);
        input.maxChunk = 1;
        assertDigest(ExactRangeDigest.full(input), bytes.length, expectedHash(bytes));
        assertEquals(bytes.length + 1, input.bulkCalls);
    }

    @Test(timeout = 5000) public void fullRetriesZeroBulkReadsUsingSingleByteReads() throws Exception {
        byte[] bytes = new byte[] {0, 1, 127, (byte) 128, (byte) 255};
        MemoryStream input = new MemoryStream(bytes);
        input.zeroBulkReads = true;
        assertDigest(ExactRangeDigest.full(input), bytes.length, expectedHash(bytes));
        assertEquals(bytes.length + 1, input.singleCalls);
        assertEquals(input.singleCalls, input.bulkCalls);
    }

    @Test(timeout = 5000) public void fullAcceptsEofFromSingleByteFallbackOnEmptyStream() throws Exception {
        MemoryStream input = new MemoryStream(new byte[0]);
        input.zeroBulkReads = true;
        assertDigest(ExactRangeDigest.full(input), 0, EMPTY_SHA256);
        assertEquals(1, input.bulkCalls);
        assertEquals(1, input.singleCalls);
    }

    @Test public void prefixHashesOnlyRequestedBytesAndLeavesSuffixUnread() throws Exception {
        byte[] bytes = "abcDO_NOT_HASH_THIS_SUFFIX".getBytes(StandardCharsets.UTF_8);
        MemoryStream input = new MemoryStream(bytes);
        assertDigest(ExactRangeDigest.prefix(input, 3), 3, ABC_SHA256);
        assertEquals(3, input.position);
        assertEquals('D', input.read());
    }

    @Test public void prefixZeroReturnsEmptyDigestWithoutReadingInput() throws Exception {
        MemoryStream input = new MemoryStream(new byte[0]);
        input.rejectEofProbe = true;
        assertDigest(ExactRangeDigest.prefix(input, 0), 0, EMPTY_SHA256);
        assertEquals(0, input.bulkCalls);
        assertEquals(0, input.singleCalls);
    }

    @Test public void prefixRejectsEveryNegativeLengthBeforeReading() {
        for (long length : new long[] {-1, -2, Long.MIN_VALUE}) {
            MemoryStream input = new MemoryStream(new byte[0]);
            expectIOException(() -> ExactRangeDigest.prefix(input, length));
            assertEquals(0, input.bulkCalls);
            assertEquals(0, input.singleCalls);
        }
    }

    @Test public void prefixExactLengthDoesNotProbeBeyondRequestedRange() throws Exception {
        byte[] bytes = patternedBytes(1024 * 1024 + 17);
        MemoryStream input = new MemoryStream(bytes);
        input.rejectEofProbe = true;
        assertDigest(ExactRangeDigest.prefix(input, bytes.length), bytes.length, expectedHash(bytes));
        assertEquals(bytes.length, input.position);
    }

    @Test public void prefixAccumulatesShortChunksAndCapsFinalReadToRemainingRange() throws Exception {
        byte[] bytes = patternedBytes(2 * 1024 * 1024 + 99);
        int limit = 1024 * 1024 + 7;
        MemoryStream input = new MemoryStream(bytes);
        input.maxChunk = 997;
        assertDigest(ExactRangeDigest.prefix(input, limit), limit, expectedHash(Arrays.copyOf(bytes, limit)));
        assertEquals(limit, input.position);
        assertTrue(input.bulkCalls > 2);
    }

    @Test public void prefixRejectsEmptyAndShortStreamsIncludingHugeRequestedRange() {
        for (long requested : new long[] {1, 3, 4, Long.MAX_VALUE}) {
            MemoryStream input = new MemoryStream(new byte[(int) Math.min(requested - 1, 3)]);
            input.maxChunk = 1;
            expectIOException(() -> ExactRangeDigest.prefix(input, requested));
            assertEquals(input.data.length, input.position);
        }
    }

    @Test(timeout = 5000) public void prefixRetriesZeroBulkReadsAndDoesNotConsumeExtraByte() throws Exception {
        byte[] bytes = new byte[] {0, (byte) 255, 2, 3, 4};
        MemoryStream input = new MemoryStream(bytes);
        input.zeroBulkReads = true;
        assertDigest(ExactRangeDigest.prefix(input, 3), 3, expectedHash(Arrays.copyOf(bytes, 3)));
        assertEquals(3, input.position);
        assertEquals(3, input.bulkCalls);
        assertEquals(3, input.singleCalls);
    }

    @Test(timeout = 5000) public void prefixRejectsPrematureEofFromSingleByteFallback() {
        for (int available : new int[] {0, 2}) {
            MemoryStream input = new MemoryStream(patternedBytes(available));
            input.zeroBulkReads = true;
            expectIOException(() -> ExactRangeDigest.prefix(input, 3));
            assertEquals(available, input.position);
            assertEquals(available + 1, input.singleCalls);
        }
    }

    @Test public void fullAndPrefixPropagateOriginalBulkReadException() {
        IOException failure = new IOException("injected bulk read failure");
        for (boolean prefix : new boolean[] {false, true}) {
            InputStream input = new InputStream() {
                @Override public int read(byte[] buffer, int offset, int length) throws IOException { throw failure; }
                @Override public int read() { fail("must not retry a failed bulk read"); return -1; }
            };
            assertSame(failure, expectIOException(() -> {
                if (prefix) ExactRangeDigest.prefix(input, 1); else ExactRangeDigest.full(input);
            }));
        }
    }

    @Test(timeout = 5000) public void fullAndPrefixPropagateOriginalSingleByteFallbackException() {
        IOException failure = new IOException("injected fallback read failure");
        for (boolean prefix : new boolean[] {false, true}) {
            InputStream input = new InputStream() {
                @Override public int read(byte[] buffer, int offset, int length) { return 0; }
                @Override public int read() throws IOException { throw failure; }
            };
            assertSame(failure, expectIOException(() -> {
                if (prefix) ExactRangeDigest.prefix(input, 1); else ExactRangeDigest.full(input);
            }));
        }
    }

    @Test public void failureAfterPartialReadCannotReturnPartialDigest() {
        IOException failure = new IOException("injected failure after one byte");
        for (boolean prefix : new boolean[] {false, true}) {
            InputStream input = new InputStream() {
                boolean emitted;
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (emitted) throw failure;
                    emitted = true;
                    buffer[offset] = 42;
                    return 1;
                }
                @Override public int read() { fail("no zero-length reads injected"); return -1; }
            };
            assertSame(failure, expectIOException(() -> {
                if (prefix) ExactRangeDigest.prefix(input, 2); else ExactRangeDigest.full(input);
            }));
        }
    }
}
