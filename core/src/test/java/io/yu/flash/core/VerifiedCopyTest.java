package io.yu.flash.core;

import org.junit.Test;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Pure in-memory endpoints: no block devices, native helpers, or destructive I/O. */
public class VerifiedCopyTest {
    private static final int NO_FAULT = Integer.MIN_VALUE;

    private static final class MemoryEndpoint implements VerifiedCopy.Endpoint {
        final byte[] data;
        final String name;
        final List<String> operations;
        int position;
        int rewinds;
        int reads;
        int readsInPass;
        int writes;
        int syncs;
        int bytesWritten;
        int maxRead = Integer.MAX_VALUE;
        int maxWrite = Integer.MAX_VALUE;
        int readFaultPass;
        int readFaultCall = 1;
        int readResult = NO_FAULT;
        IOException readError;
        int writeFaultCall;
        int writeResult = NO_FAULT;
        IOException writeError;
        int rewindFaultPass;
        IOException syncError;
        Consumer<MemoryEndpoint> afterRewind = endpoint -> {};
        Consumer<MemoryEndpoint> beforeRead = endpoint -> {};
        Consumer<MemoryEndpoint> afterSync = endpoint -> {};

        MemoryEndpoint(String name, byte[] data, List<String> operations) {
            this.name = name;
            this.data = data.clone();
            this.operations = operations;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            assertTrue("read must be positive and bounded", length > 0 && offset >= 0 && offset + length <= buffer.length);
            reads++;
            readsInPass++;
            operations.add(name + ":read:" + rewinds);
            beforeRead.accept(this);
            if (rewinds == readFaultPass && readsInPass == readFaultCall) {
                if (readError != null) throw readError;
                if (readResult != NO_FAULT) return readResult;
            }
            if (position == data.length) return -1;
            int count = Math.min(length, Math.min(maxRead, data.length - position));
            System.arraycopy(data, position, buffer, offset, count);
            position += count;
            return count;
        }

        @Override public int write(byte[] buffer, int offset, int length) throws IOException {
            assertTrue("write must be positive and bounded", length > 0 && offset >= 0 && offset + length <= buffer.length);
            writes++;
            operations.add(name + ":write");
            if (writes == writeFaultCall) {
                if (writeError != null) throw writeError;
                if (writeResult != NO_FAULT) return writeResult;
            }
            int count = Math.min(length, Math.min(maxWrite, data.length - position));
            if (count == 0) return 0;
            System.arraycopy(buffer, offset, data, position, count);
            position += count;
            bytesWritten += count;
            return count;
        }

        @Override public void rewind() throws IOException {
            rewinds++;
            operations.add(name + ":rewind:" + rewinds);
            if (rewinds == rewindFaultPass) throw new IOException(name + " rewind failure");
            position = 0;
            readsInPass = 0;
            afterRewind.accept(this);
        }

        @Override public void sync() throws IOException {
            syncs++;
            operations.add(name + ":sync");
            if (syncError != null) throw syncError;
            afterSync.accept(this);
        }
    }

    private static final class Fixture {
        final List<String> operations = new ArrayList<>();
        final List<String> phases = new ArrayList<>();
        final byte[] image;
        final byte[] original;
        final MemoryEndpoint source;
        final MemoryEndpoint target;
        final String imageHash;
        final String backupHash;
        int guards;
        int failGuard;
        String failPhase;
        Consumer<Fixture> afterGuard = fixture -> {};

        Fixture() { this(257); }
        Fixture(int size) {
            image = new byte[size];
            original = new byte[size];
            for (int n = 0; n < size; n++) image[n] = (byte) (n * 31 + 7);
            Arrays.fill(original, (byte) 0x5a);
            source = new MemoryEndpoint("source", image, operations);
            target = new MemoryEndpoint("target", original, operations);
            imageHash = sha256(image);
            backupHash = sha256(original);
        }
        String execute() throws IOException { return execute(image.length, imageHash, backupHash); }
        String execute(long length, String imageDigest, String backupDigest) throws IOException {
            return VerifiedCopy.execute(source, target, length, imageDigest, backupDigest, () -> {
                guards++;
                operations.add("guard:" + guards);
                if (guards == failGuard) throw new IOException("guard failure " + guards);
                afterGuard.accept(this);
            }, (phase, bytes) -> {
                operations.add("phase:" + phase);
                phases.add(phase + ":" + bytes);
                if (phase.equals(failPhase)) throw new IOException("observer failure " + phase);
            });
        }
        void untouched() {
            assertEquals(0, target.writes);
            assertEquals(0, target.syncs);
            assertArrayEquals(original, target.data);
            assertEquals("source must never be written", 0, source.writes);
            assertEquals("source must never be synced", 0, source.syncs);
        }
        void stoppedDuringWrite() {
            assertEquals(2, guards);
            assertEquals(2, source.rewinds);
            assertEquals(2, target.rewinds);
            assertEquals(0, target.syncs);
            assertEquals(Arrays.asList("RECHECK:0", "WRITING:0"), phases);
            assertEquals(0, source.writes);
        }
    }

    @FunctionalInterface private interface IoAction { void run() throws IOException; }
    private static IOException fails(String message, IoAction action) {
        IOException failure = assertThrows(IOException.class, action::run);
        assertEquals(message, failure.getMessage());
        return failure;
    }
    private static String sha256(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    @Test(timeout = 5000) public void partialReadsAndWritesPreserveEveryByteAndPhaseOrder() throws IOException {
        Fixture f = new Fixture();
        f.source.maxRead = 19;
        f.target.maxRead = 13;
        f.target.maxWrite = 3;
        assertEquals(f.imageHash, f.execute());
        assertArrayEquals(f.image, f.target.data);
        assertArrayEquals(f.image, f.source.data);
        assertEquals(f.image.length, f.target.bytesWritten);
        assertTrue(f.target.writes > f.image.length / 19);
        assertEquals(2, f.guards);
        assertEquals(2, f.source.rewinds);
        assertEquals(3, f.target.rewinds);
        assertEquals(1, f.target.syncs);
        assertEquals(0, f.source.writes);
        assertEquals(0, f.source.syncs);
        assertEquals(Arrays.asList("RECHECK:0", "WRITING:0", "SYNCING:257", "READBACK:0"), f.phases);
        assertTrue(f.operations.indexOf("phase:RECHECK") < f.operations.indexOf("guard:1"));
        assertTrue(f.operations.indexOf("guard:1") < f.operations.indexOf("source:read:1"));
        assertTrue(f.operations.lastIndexOf("target:read:1") < f.operations.indexOf("guard:2"));
        assertTrue(f.operations.indexOf("guard:2") < f.operations.indexOf("phase:WRITING"));
        assertTrue(f.operations.indexOf("phase:WRITING") < f.operations.indexOf("target:write"));
        assertTrue(f.operations.lastIndexOf("target:write") < f.operations.indexOf("phase:SYNCING"));
        assertTrue(f.operations.indexOf("phase:SYNCING") < f.operations.indexOf("target:sync"));
        assertTrue(f.operations.indexOf("target:sync") < f.operations.indexOf("phase:READBACK"));
        assertTrue(f.operations.indexOf("phase:READBACK") < f.operations.indexOf("target:read:3"));
    }

    @Test(timeout = 5000) public void imageCrossingInternalBufferBoundaryCopiesShortFinalChunk() throws IOException {
        Fixture f = new Fixture(2 * 1024 * 1024 + 37);
        f.source.maxRead = 700001;
        f.target.maxRead = 330007;
        f.target.maxWrite = 150001;
        assertEquals(f.imageHash, f.execute());
        assertArrayEquals(f.image, f.target.data);
        assertEquals(f.image.length, f.target.bytesWritten);
        assertEquals(1, f.target.syncs);
    }

    @Test(timeout = 5000) public void oneByteImageIsTransferred() throws IOException {
        Fixture f = new Fixture(1);
        assertEquals(f.imageHash, f.execute());
        assertArrayEquals(f.image, f.target.data);
        assertEquals(1, f.target.writes);
    }

    @Test(timeout = 5000) public void transferNeverReadsOrWritesBeyondDeclaredLength() throws IOException {
        Fixture f = new Fixture();
        int length = 41;
        String imageHash = sha256(Arrays.copyOf(f.image, length));
        String backupHash = sha256(Arrays.copyOf(f.original, length));
        assertEquals(imageHash, f.execute(length, imageHash, backupHash));
        assertArrayEquals(Arrays.copyOf(f.image, length), Arrays.copyOf(f.target.data, length));
        assertArrayEquals(Arrays.copyOfRange(f.original, length, f.original.length),
            Arrays.copyOfRange(f.target.data, length, f.target.data.length));
        assertEquals(length, f.source.position);
        assertEquals(length, f.target.position);
        assertEquals(length, f.target.bytesWritten);
    }

    @Test(timeout = 5000) public void invalidLengthsFailBeforeAnyEndpointOrObserverOperation() {
        for (long length : new long[] {0, -1, Long.MIN_VALUE}) {
            Fixture f = new Fixture();
            fails("Invalid transfer parameters", () -> f.execute(length, f.imageHash, f.backupHash));
            assertTrue(f.operations.isEmpty());
            f.untouched();
        }
    }

    @Test(timeout = 5000) public void malformedHashesFailBeforeAnyEndpointOrObserverOperation() {
        for (String hash : Arrays.asList("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            Fixture sourceHash = new Fixture();
            fails("Invalid transfer parameters", () -> sourceHash.execute(257, hash, sourceHash.backupHash));
            assertTrue(sourceHash.operations.isEmpty());
            sourceHash.untouched();
            Fixture backupHash = new Fixture();
            fails("Invalid transfer parameters", () -> backupHash.execute(257, backupHash.imageHash, hash));
            assertTrue(backupHash.operations.isEmpty());
            backupHash.untouched();
        }
    }

    @Test(timeout = 5000) public void sourceHashMismatchCannotWriteOrEvenReadTarget() {
        Fixture f = new Fixture();
        f.source.data[0] ^= 1;
        fails("Source SHA-256 changed", f::execute);
        f.untouched();
        assertEquals(0, f.target.reads);
        assertEquals(1, f.guards);
    }

    @Test(timeout = 5000) public void changedTargetMustMatchBackupBeforeWriting() {
        Fixture f = new Fixture();
        fails("Target no longer matches full backup", () -> f.execute(257, f.imageHash, f.imageHash));
        f.untouched();
        assertEquals(1, f.guards);
        assertEquals(1, f.target.rewinds);
    }

    @Test(timeout = 5000) public void firstGuardFailureStopsAllEndpointAccess() {
        Fixture f = new Fixture();
        f.failGuard = 1;
        fails("guard failure 1", f::execute);
        f.untouched();
        assertEquals(0, f.source.reads);
        assertEquals(0, f.target.reads);
        assertEquals(Arrays.asList("RECHECK:0"), f.phases);
    }

    @Test(timeout = 5000) public void secondGuardFailureStopsAfterHashesBeforeWriting() {
        Fixture f = new Fixture();
        f.failGuard = 2;
        fails("guard failure 2", f::execute);
        f.untouched();
        assertEquals(1, f.source.rewinds);
        assertEquals(1, f.target.rewinds);
        assertTrue(f.source.reads > 0 && f.target.reads > 0);
        assertEquals(Arrays.asList("RECHECK:0"), f.phases);
    }

    private static void hashReadFailure(boolean source, int result, IOException error) {
        Fixture f = new Fixture();
        MemoryEndpoint endpoint = source ? f.source : f.target;
        endpoint.maxRead = 17;
        endpoint.readFaultPass = 1;
        endpoint.readFaultCall = 2; // A successful short read precedes the fault.
        endpoint.readResult = result;
        endpoint.readError = error;
        IOException actual = fails(error == null ? "Short or stalled input during hash" : error.getMessage(), f::execute);
        if (error != null) assertSame(error, actual);
        f.untouched();
        assertEquals(2, endpoint.reads);
        assertEquals(1, endpoint.rewinds);
        assertEquals(1, f.guards);
    }
    @Test(timeout = 5000) public void zeroSourceHashReadIsNotRetried() { hashReadFailure(true, 0, null); }
    @Test(timeout = 5000) public void eofSourceHashReadIsNotRetried() { hashReadFailure(true, -1, null); }
    @Test(timeout = 5000) public void sourceHashReadExceptionIsNotRetried() { hashReadFailure(true, NO_FAULT, new IOException("source read failure")); }
    @Test(timeout = 5000) public void zeroBackupHashReadIsNotRetried() { hashReadFailure(false, 0, null); }
    @Test(timeout = 5000) public void eofBackupHashReadIsNotRetried() { hashReadFailure(false, -1, null); }
    @Test(timeout = 5000) public void backupHashReadExceptionIsNotRetried() { hashReadFailure(false, NO_FAULT, new IOException("backup read failure")); }

    @Test(timeout = 5000) public void lengthBeyondAvailableSourceFailsBeforeWriting() {
        Fixture f = new Fixture();
        fails("Short or stalled input during hash", () -> f.execute(258, f.imageHash, f.backupHash));
        f.untouched();
        assertEquals(2, f.source.reads);
        assertEquals(0, f.target.reads);
    }

    private static void sourceReadFailureDuringWrite(int result, IOException error) {
        Fixture f = new Fixture();
        f.source.maxRead = 17;
        f.source.readFaultPass = 2;
        f.source.readFaultCall = 2;
        f.source.readResult = result;
        f.source.readError = error;
        IOException actual = fails(error == null ? "Source truncated during write; target may be partial" : error.getMessage(), f::execute);
        if (error != null) assertSame(error, actual);
        f.stoppedDuringWrite();
        assertEquals(2, f.source.readsInPass);
        assertEquals(1, f.target.writes);
        assertEquals(17, f.target.bytesWritten);
        assertArrayEquals(Arrays.copyOf(f.image, 17), Arrays.copyOf(f.target.data, 17));
        assertArrayEquals(Arrays.copyOfRange(f.original, 17, 257), Arrays.copyOfRange(f.target.data, 17, 257));
    }
    @Test(timeout = 5000) public void zeroSourceReadDuringWriteLeavesPartialOutputWithoutRetry() { sourceReadFailureDuringWrite(0, null); }
    @Test(timeout = 5000) public void eofSourceReadDuringWriteLeavesPartialOutputWithoutRetry() { sourceReadFailureDuringWrite(-1, null); }
    @Test(timeout = 5000) public void sourceReadExceptionDuringWriteIsNotRetried() { sourceReadFailureDuringWrite(NO_FAULT, new IOException("source disappeared")); }

    private static void targetWriteFailure(int result, IOException error) {
        Fixture f = new Fixture();
        f.source.maxRead = 17;
        f.target.maxWrite = 4;
        f.target.writeFaultCall = 2;
        f.target.writeResult = result;
        f.target.writeError = error;
        IOException actual = fails(error == null ? "Short or stalled write; target may be partial" : error.getMessage(), f::execute);
        if (error != null) assertSame(error, actual);
        f.stoppedDuringWrite();
        assertEquals(2, f.target.writes);
        assertEquals(4, f.target.bytesWritten);
        assertArrayEquals(Arrays.copyOf(f.image, 4), Arrays.copyOf(f.target.data, 4));
        assertArrayEquals(Arrays.copyOfRange(f.original, 4, 257), Arrays.copyOfRange(f.target.data, 4, 257));
    }
    @Test(timeout = 5000) public void zeroWriteIsNotRetriedOrRolledBack() { targetWriteFailure(0, null); }
    @Test(timeout = 5000) public void negativeWriteIsNotRetriedOrRolledBack() { targetWriteFailure(-1, null); }
    @Test(timeout = 5000) public void writeExceptionIsNotRetriedOrRolledBack() { targetWriteFailure(NO_FAULT, new IOException("target write failure")); }

    @Test(timeout = 5000) public void syncFailureNeverStartsReadbackOrRepeatsWrite() {
        Fixture f = new Fixture();
        f.target.syncError = new IOException("sync failure");
        assertSame(f.target.syncError, fails("sync failure", f::execute));
        assertEquals(1, f.target.syncs);
        assertEquals(1, f.target.writes);
        assertEquals(2, f.target.rewinds);
        assertArrayEquals(f.image, f.target.data);
        assertEquals(Arrays.asList("RECHECK:0", "WRITING:0", "SYNCING:257"), f.phases);
    }

    private static void readbackFailure(int result, IOException error) {
        Fixture f = new Fixture();
        f.target.maxRead = 17;
        f.target.readFaultPass = 3;
        f.target.readFaultCall = 2;
        f.target.readResult = result;
        f.target.readError = error;
        IOException actual = fails(error == null ? "Short or stalled input during hash" : error.getMessage(), f::execute);
        if (error != null) assertSame(error, actual);
        assertEquals(2, f.target.readsInPass);
        assertEquals(3, f.target.rewinds);
        assertEquals(1, f.target.syncs);
        assertEquals(1, f.target.writes);
        assertArrayEquals(f.image, f.target.data);
        assertEquals(Arrays.asList("RECHECK:0", "WRITING:0", "SYNCING:257", "READBACK:0"), f.phases);
    }
    @Test(timeout = 5000) public void zeroReadbackReadIsNotRetried() { readbackFailure(0, null); }
    @Test(timeout = 5000) public void eofReadbackReadIsNotRetried() { readbackFailure(-1, null); }
    @Test(timeout = 5000) public void readbackExceptionIsNotRetried() { readbackFailure(NO_FAULT, new IOException("readback failure")); }

    @Test(timeout = 5000) public void corruptReadbackFailsWithoutRewriteOrRollback() {
        Fixture f = new Fixture();
        f.target.afterSync = endpoint -> endpoint.data[123] ^= 1;
        fails("Readback SHA-256 mismatch", f::execute);
        assertEquals(1, f.target.writes);
        assertEquals(1, f.target.syncs);
        assertEquals(3, f.target.rewinds);
        assertNotEquals(f.imageHash, sha256(f.target.data));
        assertNotEquals(f.backupHash, sha256(f.target.data));
    }

    @Test(timeout = 5000) public void sourceMutationAfterPreflightHashIsDetectedWithoutRetry() {
        Fixture f = new Fixture();
        f.afterGuard = fixture -> { if (fixture.guards == 2) fixture.source.data[0] ^= 1; };
        fails("Source changed during write", f::execute);
        assertEquals(1, f.target.writes);
        assertEquals(1, f.target.syncs);
        assertEquals(2, f.source.rewinds);
        assertEquals(2, f.target.rewinds);
        assertArrayEquals(f.source.data, f.target.data);
        assertEquals(Arrays.asList("RECHECK:0", "WRITING:0", "SYNCING:257"), f.phases);
    }

    @Test(timeout = 5000) public void sourceMutationMidTransferIsDetectedFromBytesActuallyWritten() {
        Fixture f = new Fixture();
        f.source.maxRead = 17;
        f.source.beforeRead = endpoint -> {
            if (endpoint.rewinds == 2 && endpoint.readsInPass == 2) endpoint.data[20] ^= 1;
        };
        fails("Source changed during write", f::execute);
        assertEquals(257, f.target.bytesWritten);
        assertEquals(16, f.target.writes);
        assertEquals(1, f.target.syncs);
        assertEquals(2, f.target.rewinds);
        assertNotEquals(f.imageHash, sha256(f.target.data));
    }

    @Test(timeout = 5000) public void recheckObserverFailurePreventsEvenGuardAndHashAccess() {
        Fixture f = new Fixture();
        f.failPhase = "RECHECK";
        fails("observer failure RECHECK", f::execute);
        f.untouched();
        assertEquals(0, f.guards);
        assertEquals(0, f.source.rewinds);
        assertEquals(0, f.target.rewinds);
    }

    @Test(timeout = 5000) public void writingObserverFailurePreventsFirstOutputByte() {
        Fixture f = new Fixture();
        f.failPhase = "WRITING";
        fails("observer failure WRITING", f::execute);
        f.untouched();
        assertEquals(2, f.guards);
        assertEquals(2, f.source.rewinds);
        assertEquals(2, f.target.rewinds);
    }

    @Test(timeout = 5000) public void syncingObserverFailureDoesNotRetryWriteOrClaimReadback() {
        Fixture f = new Fixture();
        f.failPhase = "SYNCING";
        fails("observer failure SYNCING", f::execute);
        assertEquals(1, f.target.writes);
        assertEquals(0, f.target.syncs);
        assertEquals(2, f.target.rewinds);
        assertArrayEquals(f.image, f.target.data);
        assertEquals(Arrays.asList("RECHECK:0", "WRITING:0", "SYNCING:257"), f.phases);
    }

    @Test(timeout = 5000) public void readbackObserverFailureDoesNotReadOrRetryAfterSync() {
        Fixture f = new Fixture();
        f.failPhase = "READBACK";
        fails("observer failure READBACK", f::execute);
        assertEquals(1, f.target.writes);
        assertEquals(1, f.target.syncs);
        assertEquals(2, f.target.rewinds);
        assertArrayEquals(f.image, f.target.data);
    }

    @Test(timeout = 5000) public void sourcePreflightRewindFailureCannotWrite() {
        Fixture f = new Fixture();
        f.source.rewindFaultPass = 1;
        fails("source rewind failure", f::execute);
        f.untouched();
        assertEquals(0, f.source.reads);
        assertEquals(0, f.target.reads);
    }

    @Test(timeout = 5000) public void targetPreflightRewindFailureCannotWrite() {
        Fixture f = new Fixture();
        f.target.rewindFaultPass = 1;
        fails("target rewind failure", f::execute);
        f.untouched();
        assertEquals(0, f.target.reads);
    }

    @Test(timeout = 5000) public void sourceWriteRewindFailureCannotWrite() {
        Fixture f = new Fixture();
        f.source.rewindFaultPass = 2;
        fails("source rewind failure", f::execute);
        f.untouched();
        assertEquals(1, f.target.rewinds);
    }

    @Test(timeout = 5000) public void targetWriteRewindFailureCannotWrite() {
        Fixture f = new Fixture();
        f.target.rewindFaultPass = 2;
        fails("target rewind failure", f::execute);
        f.untouched();
        assertEquals(2, f.source.rewinds);
        assertEquals(Arrays.asList("RECHECK:0"), f.phases);
    }

    @Test(timeout = 5000) public void readbackRewindFailureDoesNotRetryOrRollback() {
        Fixture f = new Fixture();
        f.target.rewindFaultPass = 3;
        fails("target rewind failure", f::execute);
        assertEquals(1, f.target.writes);
        assertEquals(1, f.target.syncs);
        assertEquals(3, f.target.rewinds);
        assertArrayEquals(f.image, f.target.data);
    }
}
