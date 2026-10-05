package io.yu.flash.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** In-memory writer/audit only: File and /dev paths are descriptors, never opened or executed. */
class DirectFlashTest {
    private val partition = Partition(
        "boot_a", "/dev/block/by-name/boot_a", "/dev/block/sda2", "8:2", 4096,
        ImageKind.BOOT, "a", Risk.BOOT_CHAIN, Truth.NO, Truth.NO,
        physical = Truth.YES, writable = Truth.YES
    )
    private val image = SelectedImage(File("not-created/direct-flash.img"), 4096)
    private val successfulStages = listOf(
        Stage.CONFIRMED, Stage.BACKUP, Stage.BACKUP_VERIFY, Stage.RECHECK,
        Stage.WRITING, Stage.SYNCING, Stage.READBACK, Stage.SUCCESS
    )

    private fun request(
        target: Partition = partition,
        selected: SelectedImage = image,
        acknowledged: Boolean = true,
        backupPath: String = "/sdcard/download"
    ) = DirectWriteRequest(target, selected, acknowledged, backupPath)

    private class FakeWriter(private val trace: MutableList<String>) : DirectWriter {
        val calls = mutableListOf<String>()
        val backups = mutableListOf<Triple<Partition, String, String>>()
        val backupResults = mutableListOf<Backup>()
        val verified = mutableListOf<Backup>()
        val hashed = mutableListOf<SelectedImage>()
        val copies = mutableListOf<Pair<Partition, SelectedImage>>()
        val readbacks = mutableListOf<Pair<Partition, Long>>()
        var syncCalls = 0
        var backupResult: Backup? = null
        var firstDigest: ByteDigest? = null
        var secondDigest: ByteDigest? = null
        var readbackDigest: ByteDigest? = null
        var copyOutcome = CommandOutcome(0)
        var syncOutcome = CommandOutcome(0)
        var onBackup: suspend () -> Unit = {}
        var onVerify: suspend () -> Unit = {}
        var onHash: suspend (Int) -> Unit = {}
        var onCopy: suspend () -> Unit = {}
        var onSync: suspend () -> Unit = {}
        var onReadback: suspend () -> Unit = {}

        private fun called(name: String) {
            calls += name
            trace += name
        }

        override suspend fun backup(target: Partition, path: String, taskId: String): Backup {
            backups += Triple(target, path, taskId)
            called("backup")
            onBackup()
            return (backupResult ?: Backup(
                "$path/$taskId-${target.name}.img", target.bytes, BACKUP_HASH,
                "$path/$taskId-${target.name}.json"
            )).also { backupResults += it }
        }

        override suspend fun verifyBackup(backup: Backup) {
            verified += backup
            called("verifyBackup")
            onVerify()
        }

        override suspend fun hashImage(image: SelectedImage): ByteDigest {
            hashed += image
            called("hashImage")
            onHash(hashed.size)
            return (if (hashed.size == 1) firstDigest else secondDigest) ?: ByteDigest(image.bytes, IMAGE_HASH)
        }

        override suspend fun copy(target: Partition, image: SelectedImage): CommandOutcome {
            copies += target to image
            called("copy")
            onCopy()
            return copyOutcome
        }

        override suspend fun sync(): CommandOutcome {
            syncCalls++
            called("sync")
            onSync()
            return syncOutcome
        }

        override suspend fun readBack(target: Partition, bytes: Long): ByteDigest {
            readbacks += target to bytes
            called("readBack")
            onReadback()
            return readbackDigest ?: ByteDigest(bytes, IMAGE_HASH)
        }

        companion object {
            val IMAGE_HASH = "a".repeat(64)
            val BACKUP_HASH = "b".repeat(64)
            val OTHER_HASH = "c".repeat(64)
        }
    }

    private class FakeAudit(private val trace: MutableList<String>) : AuditLog {
        val attempts = mutableListOf<Event>()
        val events = mutableListOf<Event>()
        val failingStages = mutableSetOf<Stage>()
        val failure = IllegalStateException("injected audit failure")
        var beforeAppend: suspend (Event) -> Unit = {}

        override suspend fun append(event: Event) {
            attempts += event
            beforeAppend(event)
            if (event.stage in failingStages) throw failure
            events += event
            trace += event.stage.name
        }
    }

    private class Fixture(val gate: TransactionGate = TransactionGate()) {
        val trace = mutableListOf<String>()
        val writer = FakeWriter(trace)
        val audit = FakeAudit(trace)
        val flash = DirectFlash(writer, audit, gate)
    }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable {
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull("operation must fail", failure)
        return checkNotNull(failure)
    }

    private fun assertStages(audit: FakeAudit, vararg expected: Stage) {
        assertEquals(expected.toList(), audit.events.map { it.stage })
    }

    private fun assertSuccess(f: Fixture) {
        assertEquals(successfulStages, f.audit.events.map { it.stage })
        assertEquals(1, f.writer.backups.size)
        assertEquals(1, f.writer.verified.size)
        assertEquals(2, f.writer.hashed.size)
        assertEquals(1, f.writer.copies.size)
        assertEquals(1, f.writer.syncCalls)
        assertEquals(1, f.writer.readbacks.size)
    }

    private fun assertNoSuccess(audit: FakeAudit) {
        assertFalse(audit.events.any { it.stage == Stage.SUCCESS })
    }

    private fun assertNoWrite(f: Fixture) {
        assertTrue(f.writer.copies.isEmpty())
        assertEquals(0, f.writer.syncCalls)
        assertTrue(f.writer.readbacks.isEmpty())
        assertNoSuccess(f.audit)
    }

    private suspend fun assertGateReleased(gate: TransactionGate) {
        var entered = false
        gate.exclusive { entered = true }
        assertTrue("gate must be reusable", entered)
    }

    @Test fun acknowledgementIsRequiredBeforeAnyWriterCall() = runBlocking {
        val f = Fixture()
        val failure = failureOf { f.flash.execute(request(acknowledged = false)) }

        assertTrue(failure is SafetyException)
        assertTrue(f.writer.calls.isEmpty())
        assertStages(f.audit, Stage.FAILED)
        assertEquals(0L, f.audit.events.single().bytes)
        assertNull(f.audit.events.single().exitCode)
        assertGateReleased(f.gate)
    }

    @Test fun successfulWriteRequiresBackupVerificationAndBothImageHashesInOrder() = runBlocking {
        val f = Fixture()
        f.flash.execute(request())

        assertEquals(listOf(
            "CONFIRMED", "BACKUP", "backup", "BACKUP_VERIFY", "verifyBackup", "RECHECK", "hashImage",
            "WRITING", "copy", "SYNCING", "sync", "READBACK", "readBack", "hashImage", "SUCCESS"
        ), f.trace)
        assertSuccess(f)
        assertEquals(listOf(partition to image), f.writer.copies)
        assertEquals(listOf(partition to image.bytes), f.writer.readbacks)
        assertEquals(listOf(image, image), f.writer.hashed)
        assertSame(f.writer.backupResults.single(), f.writer.verified.single())
        assertGateReleased(f.gate)
    }

    @Test fun backupPathDefaultsToDownloadAndExplicitPathIsForwardedUnchanged() = runBlocking {
        assertEquals("/sdcard/download", DirectWriteRequest(partition, image, true).backupPath)
        for (path in listOf("/sdcard/download", "/storage/emulated/0/custom backup's")) {
            val f = Fixture()
            f.flash.execute(request(backupPath = path))
            val call = f.writer.backups.single()
            assertEquals(partition, call.first)
            assertEquals(path, call.second)
            assertEquals(f.audit.events.first().taskId, call.third)
            assertSuccess(f)
        }
    }

    @Test fun auditsRetainTaskIdentityAndBackupLocationAndHash() = runBlocking {
        val f = Fixture()
        f.flash.execute(request())

        val id = f.audit.events.first().taskId
        assertTrue(id.isNotBlank())
        assertTrue(f.audit.events.all {
            it.taskId == id && it.partition == partition.name && it.slot == partition.slot
        })
        val summaries = f.audit.events.joinToString("\n") { it.summary }
        val backup = f.writer.backupResults.single()
        assertTrue("audit must identify recoverable backup location", summaries.contains(backup.path))
        assertTrue("audit must retain backup digest", summaries.contains(backup.sha256))
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, image.bytes, image.bytes, image.bytes),
            f.audit.events.map { it.bytes })
        assertTrue(f.audit.events.take(5).all { it.exitCode == null })
        assertEquals(0, f.audit.events.single { it.stage == Stage.SYNCING }.exitCode)
    }

    @Test fun legacyImageTargetAndNormalizationStagesRemainAbsent() = runBlocking {
        val f = Fixture()
        f.flash.execute(request())
        val compatibilityStages = setOf(
            Stage.SELECTED, Stage.IMPORTING, Stage.IMAGE_CHECK, Stage.TARGET_CHECK, Stage.NORMALIZE
        )
        assertFalse(f.audit.attempts.any { it.stage in compatibilityStages })
        assertSuccess(f)
    }

    @Test fun sequentialWritesReleaseGateAndUseDifferentBackupTaskIds() = runBlocking {
        val f = Fixture()
        f.flash.execute(request())
        f.flash.execute(request())

        assertEquals(2, f.writer.copies.size)
        assertEquals(2, f.writer.syncCalls)
        assertEquals(2, f.writer.readbacks.size)
        assertEquals(4, f.writer.hashed.size)
        val groups = f.audit.events.groupBy { it.taskId }
        assertEquals(2, groups.size)
        groups.values.forEach { events -> assertEquals(successfulStages, events.map { it.stage }) }
        assertEquals(groups.keys, f.writer.backups.map { it.third }.toSet())
    }

    @Test fun backupFailureNeverVerifiesHashesOrWritesAndDoesNotRetry() = runBlocking {
        val f = Fixture()
        val original = IllegalStateException("backup storage is full")
        f.writer.onBackup = { throw original }

        assertSame(original, failureOf { f.flash.execute(request()) })
        assertEquals(listOf("backup"), f.writer.calls)
        assertStages(f.audit, Stage.CONFIRMED, Stage.BACKUP, Stage.FAILED)
        assertNull(f.audit.events.last().exitCode)
        assertNoWrite(f)
        assertGateReleased(f.gate)
    }

    @Test fun backupVerificationFailureNeverHashesOrWritesAndDoesNotRetry() = runBlocking {
        val f = Fixture()
        val original = SafetyException("backup contents or metadata do not match")
        f.writer.onVerify = { throw original }

        assertSame(original, failureOf { f.flash.execute(request()) })
        assertEquals(listOf("backup", "verifyBackup"), f.writer.calls)
        assertStages(f.audit, Stage.CONFIRMED, Stage.BACKUP, Stage.BACKUP_VERIFY, Stage.FAILED)
        assertNull(f.audit.events.last().exitCode)
        assertNoWrite(f)
        assertGateReleased(f.gate)
    }

    @Test fun malformedBackupMetadataFailsBeforeVerificationOrAnyWrite() = runBlocking {
        val valid = Backup("/backup/original.img", partition.bytes, FakeWriter.BACKUP_HASH, "/backup/original.json")
        val malformed = listOf(
            valid.copy(bytes = -1), valid.copy(bytes = 0), valid.copy(bytes = partition.bytes - 1),
            valid.copy(bytes = partition.bytes + 1), valid.copy(path = ""), valid.copy(path = " \t"),
            valid.copy(metadataPath = ""), valid.copy(metadataPath = " \t"),
            valid.copy(sha256 = ""), valid.copy(sha256 = "b".repeat(63)),
            valid.copy(sha256 = "b".repeat(65)), valid.copy(sha256 = "g".repeat(64))
        )
        for (backup in malformed) {
            val f = Fixture()
            f.writer.backupResult = backup
            assertTrue("malformed backup must fail: $backup", failureOf { f.flash.execute(request()) } is SafetyException)
            assertEquals(listOf("backup"), f.writer.calls)
            assertEquals(Stage.FAILED, f.audit.events.last().stage)
            assertNoWrite(f)
            assertGateReleased(f.gate)
        }
    }

    @Test fun nonpositivePartitionLengthCannotSatisfyFullBackupContract() = runBlocking {
        for (bytes in listOf(Long.MIN_VALUE, -1L, 0L)) {
            val f = Fixture()
            assertTrue(failureOf { f.flash.execute(request(target = partition.copy(bytes = bytes))) } is SafetyException)
            assertTrue(f.writer.verified.isEmpty())
            assertTrue(f.writer.hashed.isEmpty())
            assertNoWrite(f)
            assertGateReleased(f.gate)
        }
    }

    @Test fun prewriteImageHashExceptionPreventsCopy() = runBlocking {
        val f = Fixture()
        val original = IllegalStateException("source could not be read")
        f.writer.onHash = { throw original }

        assertSame(original, failureOf { f.flash.execute(request()) })
        assertEquals(listOf("backup", "verifyBackup", "hashImage"), f.writer.calls)
        assertStages(f.audit, Stage.CONFIRMED, Stage.BACKUP, Stage.BACKUP_VERIFY, Stage.RECHECK, Stage.FAILED)
        assertNoWrite(f)
        assertGateReleased(f.gate)
    }

    @Test fun negativeSelectedImageLengthIsRejectedBeforeWrite() = runBlocking {
        for (bytes in listOf(Long.MIN_VALUE, -1L)) {
            val f = Fixture()
            assertTrue(failureOf { f.flash.execute(request(selected = image.copy(bytes = bytes))) } is SafetyException)
            assertNoWrite(f)
            assertGateReleased(f.gate)
        }
    }

    @Test fun imageLengthChangedBeforeWriteIsRejected() = runBlocking {
        for (bytes in listOf(-1L, 0L, image.bytes - 1, image.bytes + 1, Long.MAX_VALUE)) {
            val f = Fixture()
            f.writer.firstDigest = ByteDigest(bytes, FakeWriter.IMAGE_HASH)
            assertTrue(failureOf { f.flash.execute(request()) } is SafetyException)
            assertEquals(1, f.writer.hashed.size)
            assertNoWrite(f)
            assertGateReleased(f.gate)
        }
    }

    @Test fun malformedPrewriteImageHashesAreRejected() = runBlocking {
        for (hash in listOf("", "a".repeat(63), "a".repeat(65), "z".repeat(64), " ".repeat(64))) {
            val f = Fixture()
            f.writer.firstDigest = ByteDigest(image.bytes, hash)
            assertTrue(failureOf { f.flash.execute(request()) } is SafetyException)
            assertNoWrite(f)
            assertGateReleased(f.gate)
        }
    }

    @Test fun nonzeroCopyDoesNotSyncReadBackRetryOrSucceedAndPreservesExitCode() = runBlocking {
        for (exit in listOf(1, 23, 137, -1)) {
            val f = Fixture()
            f.writer.copyOutcome = CommandOutcome(exit, "injected partial copy")
            val failure = failureOf { f.flash.execute(request()) }

            assertTrue(failure is SafetyException)
            assertTrue(failure.message.orEmpty().contains("exit=$exit"))
            assertTrue(failure.message.orEmpty().contains("injected partial copy"))
            assertEquals(1, f.writer.copies.size)
            assertEquals(0, f.writer.syncCalls)
            assertTrue(f.writer.readbacks.isEmpty())
            assertEquals(1, f.writer.hashed.size)
            assertEquals(successfulStages.take(5) + Stage.FAILED, f.audit.events.map { it.stage })
            assertEquals(exit, f.audit.events.last().exitCode)
            assertTrue(f.audit.events.last().summary.contains(checkNotNull(failure.message)))
            assertEquals(0L, f.audit.events.last().bytes)
            assertNoSuccess(f.audit)
            assertGateReleased(f.gate)
        }
    }

    @Test fun nonzeroSyncDoesNotReadBackRetryOrSucceedAndPreservesItsExitCode() = runBlocking {
        for (exit in listOf(1, 7, 137, -1)) {
            val f = Fixture()
            f.writer.syncOutcome = CommandOutcome(exit, "injected sync failure")
            val failure = failureOf { f.flash.execute(request()) }

            assertTrue(failure is SafetyException)
            assertTrue(failure.message.orEmpty().contains("exit=$exit"))
            assertTrue(failure.message.orEmpty().contains("injected sync failure"))
            assertEquals(1, f.writer.copies.size)
            assertEquals(1, f.writer.syncCalls)
            assertTrue(f.writer.readbacks.isEmpty())
            assertEquals(1, f.writer.hashed.size)
            assertEquals(successfulStages.take(6) + Stage.FAILED, f.audit.events.map { it.stage })
            assertEquals(exit, f.audit.events.last().exitCode)
            assertTrue(f.audit.events.last().summary.contains(checkNotNull(failure.message)))
            assertEquals(image.bytes, f.audit.events.last().bytes)
            assertNoSuccess(f.audit)
            assertGateReleased(f.gate)
        }
    }

    @Test fun exceptionAfterCopyLaunchIsFailedWithoutSyncOrRetryAndReleasesGate() = runBlocking {
        val f = Fixture()
        val original = IllegalStateException("copy launched, result unknown")
        f.writer.onCopy = { throw original }

        assertSame(original, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(0, f.writer.syncCalls)
        assertTrue(f.writer.readbacks.isEmpty())
        assertEquals(successfulStages.take(5) + Stage.FAILED, f.audit.events.map { it.stage })
        assertNull(f.audit.events.last().exitCode)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun exceptionAfterSyncLaunchIsFailedAndDoesNotReuseCopyExitCode() = runBlocking {
        val f = Fixture()
        val original = IllegalStateException("sync launched, result unknown")
        f.writer.onSync = { throw original }

        assertSame(original, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(1, f.writer.syncCalls)
        assertTrue(f.writer.readbacks.isEmpty())
        assertEquals(successfulStages.take(6) + Stage.FAILED, f.audit.events.map { it.stage })
        assertNull(f.audit.events.last().exitCode)
        assertEquals(image.bytes, f.audit.events.last().bytes)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun readbackExceptionCannotReuseSuccessfulSyncExitCodeOrRetry() = runBlocking {
        val f = Fixture()
        val original = IllegalStateException("readback I/O error")
        f.writer.onReadback = { throw original }

        assertSame(original, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(1, f.writer.syncCalls)
        assertEquals(1, f.writer.readbacks.size)
        assertEquals(1, f.writer.hashed.size)
        assertEquals(successfulStages.take(7) + Stage.FAILED, f.audit.events.map { it.stage })
        assertNull(f.audit.events.last().exitCode)
        assertEquals(image.bytes, f.audit.events.last().bytes)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun readbackMustReportExactlyThePrewriteImageLength() = runBlocking {
        for (bytes in listOf(-1L, 0L, image.bytes - 1, image.bytes + 1, Long.MAX_VALUE)) {
            val f = Fixture()
            f.writer.readbackDigest = ByteDigest(bytes, FakeWriter.IMAGE_HASH)
            assertTrue(failureOf { f.flash.execute(request()) } is SafetyException)
            assertEquals(listOf(partition to image.bytes), f.writer.readbacks)
            assertEquals(1, f.writer.copies.size)
            assertEquals(1, f.writer.syncCalls)
            assertNull(f.audit.events.last().exitCode)
            assertNoSuccess(f.audit)
            assertGateReleased(f.gate)
        }
    }

    @Test fun readbackHashMismatchOrMalformedHashCannotSucceed() = runBlocking {
        for (hash in listOf(FakeWriter.OTHER_HASH, "", "a".repeat(63), "a".repeat(65), "g".repeat(64))) {
            val f = Fixture()
            f.writer.readbackDigest = ByteDigest(image.bytes, hash)
            assertTrue(failureOf { f.flash.execute(request()) } is SafetyException)
            assertEquals(1, f.writer.readbacks.size)
            assertEquals(1, f.writer.copies.size)
            assertNull(f.audit.events.last().exitCode)
            assertNoSuccess(f.audit)
            assertGateReleased(f.gate)
        }
    }

    @Test fun sourceHashChangedAfterCopyFailsEvenWhenReadbackMatchesOriginal() = runBlocking {
        val f = Fixture()
        f.writer.secondDigest = ByteDigest(image.bytes, FakeWriter.OTHER_HASH)
        assertTrue(failureOf { f.flash.execute(request()) } is SafetyException)
        assertEquals(2, f.writer.hashed.size)
        assertEquals(1, f.writer.readbacks.size)
        assertEquals(1, f.writer.copies.size)
        assertNull(f.audit.events.last().exitCode)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun changedSourceAndMatchingChangedReadbackCannotReplacePrewriteBaseline() = runBlocking {
        val f = Fixture()
        f.writer.secondDigest = ByteDigest(image.bytes, FakeWriter.OTHER_HASH)
        f.writer.readbackDigest = ByteDigest(image.bytes, FakeWriter.OTHER_HASH)
        assertTrue(failureOf { f.flash.execute(request()) } is SafetyException)
        assertEquals(1, f.writer.copies.size)
        assertEquals(1, f.writer.readbacks.size)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun sourceLengthChangedAfterCopyFailsEvenWhenReadbackMatchesOriginal() = runBlocking {
        for (bytes in listOf(-1L, 0L, image.bytes - 1, image.bytes + 1)) {
            val f = Fixture()
            f.writer.secondDigest = ByteDigest(bytes, FakeWriter.IMAGE_HASH)
            assertTrue(failureOf { f.flash.execute(request()) } is SafetyException)
            assertEquals(2, f.writer.hashed.size)
            assertEquals(1, f.writer.readbacks.size)
            assertNull(f.audit.events.last().exitCode)
            assertNoSuccess(f.audit)
            assertGateReleased(f.gate)
        }
    }

    @Test fun malformedPostwriteSourceDigestFails() = runBlocking {
        for (hash in listOf("", "a".repeat(63), "a".repeat(65), "g".repeat(64))) {
            val f = Fixture()
            f.writer.secondDigest = ByteDigest(image.bytes, hash)
            assertTrue(failureOf { f.flash.execute(request()) } is SafetyException)
            assertEquals(2, f.writer.hashed.size)
            assertNull(f.audit.events.last().exitCode)
            assertNoSuccess(f.audit)
            assertGateReleased(f.gate)
        }
    }

    @Test fun postwriteSourceHashExceptionFailsWithoutRetry() = runBlocking {
        val f = Fixture()
        val original = IllegalStateException("source disappeared after copy")
        f.writer.onHash = { count -> if (count == 2) throw original }
        assertSame(original, failureOf { f.flash.execute(request()) })
        assertEquals(2, f.writer.hashed.size)
        assertEquals(1, f.writer.copies.size)
        assertEquals(1, f.writer.readbacks.size)
        assertEquals(successfulStages.take(7) + Stage.FAILED, f.audit.events.map { it.stage })
        assertNull(f.audit.events.last().exitCode)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    private fun pauseAt(writer: FakeWriter, operation: String, pause: suspend () -> Unit) {
        when (operation) {
            "backup" -> writer.onBackup = pause
            "verifyBackup" -> writer.onVerify = pause
            "prehash" -> writer.onHash = { count -> if (count == 1) pause() }
            "copy" -> writer.onCopy = pause
            "sync" -> writer.onSync = pause
            "readBack" -> writer.onReadback = pause
            "posthash" -> writer.onHash = { count -> if (count == 2) pause() }
            else -> error("unknown operation: $operation")
        }
    }

    @Test fun cancellationAtEveryWriterStageStopsFurtherWorkAndLogsInterruptionNonCancellably() = runBlocking {
        for (operation in listOf("backup", "verifyBackup", "prehash", "copy", "sync", "readBack", "posthash")) {
            val f = Fixture()
            val entered = CompletableDeferred<Unit>()
            pauseAt(f.writer, operation) { entered.complete(Unit); awaitCancellation() }
            // A cancellable suspension proves interruption logging is shielded.
            f.audit.beforeAppend = { event -> if (event.stage == Stage.INTERRUPTED) yield() }
            val job = launch(start = CoroutineStart.UNDISPATCHED) { f.flash.execute(request()) }
            try {
                assertTrue("must enter $operation", entered.isCompleted)
                val callsAtCancellation = f.writer.calls.toList()
                job.cancel(CancellationException("cancelled during $operation"))
                job.join()

                assertTrue(job.isCancelled)
                assertEquals(callsAtCancellation, f.writer.calls)
                assertEquals(Stage.INTERRUPTED, f.audit.events.last().stage)
                assertTrue(f.audit.events.last().summary.contains("cancelled during $operation"))
                assertNull(f.audit.events.last().exitCode)
                assertEquals(if (operation in listOf("sync", "readBack", "posthash")) image.bytes else 0L,
                    f.audit.events.last().bytes)
                assertNoSuccess(f.audit)
                assertGateReleased(f.gate)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test fun sharedGateRejectsConcurrentRequestsThroughoutBackupWriteAndVerification() = runBlocking {
        for (operation in listOf("backup", "verifyBackup", "prehash", "copy", "sync", "readBack", "posthash")) {
            val gate = TransactionGate()
            val first = Fixture(gate)
            val second = Fixture(gate)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            pauseAt(first.writer, operation) { entered.complete(Unit); release.await() }
            val firstJob = launch(start = CoroutineStart.UNDISPATCHED) { first.flash.execute(request()) }
            var rejected: Throwable? = null
            val secondJob = launch(start = CoroutineStart.UNDISPATCHED) {
                rejected = runCatching { second.flash.execute(request()) }.exceptionOrNull()
            }
            try {
                assertTrue(entered.isCompleted)
                assertFalse(firstJob.isCompleted)
                assertTrue("concurrent request must not queue during $operation", secondJob.isCompleted)
                assertTrue(rejected is SafetyException)
                assertTrue(second.writer.calls.isEmpty())
                assertTrue(second.audit.attempts.isEmpty())

                release.complete(Unit)
                firstJob.join()
                yield()
                assertTrue("rejected request must not run later", second.writer.calls.isEmpty())
                assertSuccess(first)
                second.flash.execute(request())
                assertSuccess(second)
            } finally {
                secondJob.cancelAndJoin()
                firstJob.cancelAndJoin()
            }
        }
    }

    @Test fun auditFailureAtAnyPrewriteStageNeverWrites() = runBlocking {
        for (stage in successfulStages.take(5)) {
            val f = Fixture()
            f.audit.failingStages += stage
            assertSame(f.audit.failure, failureOf { f.flash.execute(request()) })
            assertNoWrite(f)
            assertEquals(successfulStages.takeWhile { it != stage } + Stage.FAILED, f.audit.events.map { it.stage })
            assertGateReleased(f.gate)
        }
    }

    @Test fun entirelyBrokenAuditNeverCallsWriterAndStillReleasesGate() = runBlocking {
        val f = Fixture()
        f.audit.failingStages.addAll(Stage.entries)

        assertSame(f.audit.failure, failureOf { f.flash.execute(request()) })
        assertTrue(f.writer.calls.isEmpty())
        assertEquals(listOf(Stage.CONFIRMED, Stage.FAILED), f.audit.attempts.map { it.stage })
        assertTrue(f.audit.events.isEmpty())
        assertGateReleased(f.gate)
    }

    @Test fun auditFailureAfterCopyCannotSyncOrReportSuccess() = runBlocking {
        val f = Fixture()
        f.audit.failingStages += Stage.SYNCING

        assertSame(f.audit.failure, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(0, f.writer.syncCalls)
        assertTrue(f.writer.readbacks.isEmpty())
        assertEquals(successfulStages.take(5) + Stage.FAILED, f.audit.events.map { it.stage })
        assertEquals(image.bytes, f.audit.events.last().bytes)
        assertEquals(0, f.audit.events.last().exitCode)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun auditFailureAtReadbackCannotReadOrReportSuccess() = runBlocking {
        val f = Fixture()
        f.audit.failingStages += Stage.READBACK

        assertSame(f.audit.failure, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(1, f.writer.syncCalls)
        assertTrue(f.writer.readbacks.isEmpty())
        assertEquals(1, f.writer.hashed.size)
        assertEquals(successfulStages.take(6) + Stage.FAILED, f.audit.events.map { it.stage })
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun auditFailureRecordingSuccessFailsDespiteMatchingReadback() = runBlocking {
        val f = Fixture()
        f.audit.failingStages += Stage.SUCCESS

        assertSame(f.audit.failure, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(1, f.writer.syncCalls)
        assertEquals(1, f.writer.readbacks.size)
        assertEquals(2, f.writer.hashed.size)
        assertEquals(successfulStages.take(7) + Stage.FAILED, f.audit.events.map { it.stage })
        assertEquals(successfulStages + Stage.FAILED, f.audit.attempts.map { it.stage })
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun failedAuditDoesNotMaskOriginalWriterExceptionOrKeepGateLocked() = runBlocking {
        val f = Fixture()
        val original = IllegalStateException("original copy failure")
        f.writer.onCopy = { throw original }
        f.audit.failingStages += Stage.FAILED

        assertSame(original, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(0, f.writer.syncCalls)
        assertEquals(Stage.FAILED, f.audit.attempts.last().stage)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun failedInterruptionAuditStillPropagatesCancellationAndReleasesGate() = runBlocking {
        val f = Fixture()
        val original = CancellationException("writer cancelled after launch")
        f.writer.onReadback = { throw original }
        f.audit.failingStages += Stage.INTERRUPTED

        assertSame(original, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(1, f.writer.readbacks.size)
        assertEquals(Stage.INTERRUPTED, f.audit.attempts.last().stage)
        assertNull(f.audit.attempts.last().exitCode)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun coordinatorDoesNotReintroduceTargetRiskSlotOrCompatibilityPreflight() = runBlocking {
        // Fake backup permits these descriptors. The real adapter still enforces SafetyPolicy.backup.
        val targets = listOf(
            partition.copy(risk = Risk.DATA), partition.copy(mounted = Truth.YES),
            partition.copy(mapped = Truth.YES), partition.copy(slot = "a"),
            partition.copy(
                name = "userdata_a", risk = Risk.DATA, mounted = Truth.YES, mapped = Truth.YES,
                slot = "a", physical = Truth.NO, writable = Truth.NO, kind = ImageKind.UNKNOWN
            )
        )
        for (target in targets) {
            val f = Fixture()
            f.flash.execute(request(target = target))
            assertEquals(listOf(target to image), f.writer.copies)
            assertEquals(listOf(target to image.bytes), f.writer.readbacks)
            assertSuccess(f)
        }
    }

    @Test fun nonnegativeImageLengthsAreComparedForIntegrityNotTargetCapacity() = runBlocking {
        for (bytes in listOf(0L, 1L, 2048L, 4097L, Long.MAX_VALUE)) {
            val selected = image.copy(bytes = bytes)
            val f = Fixture()
            f.flash.execute(request(selected = selected))
            assertEquals(listOf(partition to selected), f.writer.copies)
            assertEquals(listOf(partition to bytes), f.writer.readbacks)
            assertEquals(bytes, f.audit.events.last().bytes)
            assertSuccess(f)
        }
    }

    @Test fun targetKindsAndPositiveLengthsDoNotInvokeLegacyCompatibilityPolicy() = runBlocking {
        for (kind in ImageKind.entries) {
            for (bytes in listOf(1L, 4096L, Long.MAX_VALUE)) {
                val target = partition.copy(kind = kind, bytes = bytes)
                val f = Fixture()
                f.flash.execute(request(target = target))
                assertEquals(bytes, f.writer.verified.single().bytes)
                assertEquals(listOf(target to image), f.writer.copies)
                assertSuccess(f)
            }
        }
    }

    @Test fun copyCommandUsesFixedExecutableBlockSizeAndQuotedPaths() {
        val source = "/data/user/0/io.yu.flash/files/private image.img"
        for (target in listOf(
            "/dev/block/sda2", "/dev/block/by-name/boot_a", "/dev/block/dm-0",
            "/dev/block/platform/soc/by-name/vendor_a", "/dev/block/8:2"
        )) {
            assertEquals(
                "/system/bin/toybox dd if='/data/user/0/io.yu.flash/files/private image.img' of='$target' bs=1048576",
                DirectDdCommand.copy(source, target)
            )
        }
    }

    @Test fun privateSourceApostrophesAndShellMetacharactersRemainSingleQuotedData() {
        val source = "/data/user/0/io.yu.flash/files/private image's;\$(id)`whoami`&|<>*?[]{}!.img"
        assertEquals(
            "/system/bin/toybox dd if='/data/user/0/io.yu.flash/files/private image'\\''s;\$(id)`whoami`&|<>*?[]{}!.img' of='/dev/block/by-name/boot_a' bs=1048576",
            DirectDdCommand.copy(source, "/dev/block/by-name/boot_a")
        )
    }

    private fun assertInvalidTargets(targets: List<String>) {
        for (target in targets) {
            val failure = runCatching {
                DirectDdCommand.copy("/data/user/0/io.yu.flash/files/private.img", target)
            }.exceptionOrNull()
            assertTrue("target must be rejected: ${target.toCharArray().contentToString()}", failure is SafetyException)
        }
    }

    @Test fun relativeAndNonBlockTargetsAreRejected() {
        assertInvalidTargets(listOf(
            "", "dev/block/sda2", "sda2", "./dev/block/sda2", "../dev/block/sda2",
            "/tmp/sda2", "/dev/sda2", "/dev/block", "/dev/blockevil/sda2",
            "/dev/block/", "/dev/block//", "/dev/block//sda2", "/dev/block/sda2/"
        ))
    }

    @Test fun dotAndParentTraversalTargetsAreRejected() {
        assertInvalidTargets(listOf(
            "/dev/block/../sda2", "/dev/block/./sda2", "/dev/block/by-name/../../sda2",
            "/dev/block/by-name/.", "/dev/block/by-name/..", "/dev/block//../sda2",
            "/dev/block/%2e%2e/sda2"
        ))
    }

    @Test fun injectionAndControlCharactersInTargetAreRejected() {
        assertInvalidTargets(listOf(
            "/dev/block/sda2;id", "/dev/block/sda2 && id", "/dev/block/\$(id)",
            "/dev/block/`id`", "/dev/block/sda2|id", "/dev/block/sda2>out",
            "/dev/block/boot'a", "/dev/block/boot\"a", "/dev/block/boot a",
            "/dev/block/boot\\a", "/dev/block/*", "/dev/block/sda2\u0000suffix",
            "/dev/block/sda2\nid", "/dev/block/sda2\r", "/dev/block/sda2\t"
        ))
    }

    @Test fun relativeAndControlCharacterSourcePathsAreRejected() {
        for (source in listOf(
            "", "private.img", "./private.img", "../private.img",
            "/data/private\u0000.img", "/data/private\n.img", "/data/private\r.img"
        )) {
            val failure = runCatching { DirectDdCommand.copy(source, "/dev/block/sda2") }.exceptionOrNull()
            assertTrue("source must be rejected: ${source.toCharArray().contentToString()}", failure is SafetyException)
        }
    }

    @Test fun syncCommandIsAFixedLiteralWithoutUserControlledArguments() {
        assertEquals("/system/bin/toybox sync", DirectDdCommand.SYNC)
    }
}
