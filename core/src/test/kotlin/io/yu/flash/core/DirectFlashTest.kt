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

    private fun request(
        target: Partition = partition,
        selected: SelectedImage = image,
        acknowledged: Boolean = true
    ) = DirectWriteRequest(target, selected, acknowledged)

    private class FakeWriter(private val trace: MutableList<String>) : DirectWriter {
        val copies = mutableListOf<Pair<Partition, SelectedImage>>()
        var syncCalls = 0
        var copyOutcome = CommandOutcome(0)
        var syncOutcome = CommandOutcome(0)
        var onCopy: suspend () -> Unit = {}
        var onSync: suspend () -> Unit = {}

        override suspend fun copy(target: Partition, image: SelectedImage): CommandOutcome {
            copies += target to image
            trace += "copy"
            onCopy()
            return copyOutcome
        }

        override suspend fun sync(): CommandOutcome {
            syncCalls++
            trace += "sync"
            onSync()
            return syncOutcome
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

    private fun assertNoSuccess(audit: FakeAudit) {
        assertFalse(audit.events.any { it.stage == Stage.SUCCESS })
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
        assertTrue(f.writer.copies.isEmpty())
        assertEquals(0, f.writer.syncCalls)
        assertStages(f.audit, Stage.FAILED)
        assertEquals(0L, f.audit.events.single().bytes)
        assertNull(f.audit.events.single().exitCode)
        assertGateReleased(f.gate)
    }

    @Test fun successfulWriteOrdersAuditAndWriterCallsWithoutLegacyStages() = runBlocking {
        val f = Fixture()
        f.flash.execute(request())

        assertEquals(listOf("CONFIRMED", "WRITING", "copy", "SYNCING", "sync", "SUCCESS"), f.trace)
        assertStages(f.audit, Stage.CONFIRMED, Stage.WRITING, Stage.SYNCING, Stage.SUCCESS)
        assertEquals(listOf(partition to image), f.writer.copies)
        assertEquals(1, f.writer.syncCalls)
        val legacyStages = setOf(
            Stage.SELECTED, Stage.IMPORTING, Stage.IMAGE_CHECK, Stage.TARGET_CHECK,
            Stage.BACKUP, Stage.BACKUP_VERIFY, Stage.NORMALIZE, Stage.RECHECK, Stage.READBACK
        )
        assertFalse(f.audit.attempts.any { it.stage in legacyStages })
        assertGateReleased(f.gate)
    }

    @Test fun auditKeepsOneTaskIdentityAndReportsBytesOnlyAfterCopyReturnsZero() = runBlocking {
        val f = Fixture()
        f.flash.execute(request())

        val id = f.audit.events.first().taskId
        assertTrue(id.isNotBlank())
        assertTrue(f.audit.events.all {
            it.taskId == id && it.partition == partition.name && it.slot == partition.slot
        })
        assertEquals(listOf(0L, 0L, image.bytes, image.bytes), f.audit.events.map { it.bytes })
        assertEquals(listOf(null, null, 0, 0), f.audit.events.map { it.exitCode })
    }

    @Test fun sequentialWritesReleaseGateAndUseDifferentTaskIds() = runBlocking {
        val f = Fixture()
        f.flash.execute(request())
        f.flash.execute(request())

        assertEquals(2, f.writer.copies.size)
        assertEquals(2, f.writer.syncCalls)
        val groups = f.audit.events.groupBy { it.taskId }
        assertEquals(2, groups.size)
        groups.values.forEach { events ->
            assertEquals(listOf(Stage.CONFIRMED, Stage.WRITING, Stage.SYNCING, Stage.SUCCESS), events.map { it.stage })
        }
    }

    @Test fun nonzeroCopyDoesNotSyncRetryOrSucceedAndPreservesExitCode() = runBlocking {
        for (exit in listOf(1, 23, 137, -1)) {
            val f = Fixture()
            f.writer.copyOutcome = CommandOutcome(exit, "injected partial copy")
            val failure = failureOf { f.flash.execute(request()) }

            assertTrue(failure is SafetyException)
            assertTrue(failure.message.orEmpty().contains("exit=$exit"))
            assertTrue(failure.message.orEmpty().contains("injected partial copy"))
            assertEquals(1, f.writer.copies.size)
            assertEquals(0, f.writer.syncCalls)
            assertStages(f.audit, Stage.CONFIRMED, Stage.WRITING, Stage.FAILED)
            assertEquals(exit, f.audit.events.last().exitCode)
            assertEquals(failure.message, f.audit.events.last().summary)
            assertEquals(0L, f.audit.events.last().bytes)
            assertNoSuccess(f.audit)
            assertGateReleased(f.gate)
        }
    }

    @Test fun nonzeroSyncDoesNotRetryOrSucceedAndPreservesItsExitCode() = runBlocking {
        for (exit in listOf(1, 7, 137, -1)) {
            val f = Fixture()
            f.writer.syncOutcome = CommandOutcome(exit, "injected sync failure")
            val failure = failureOf { f.flash.execute(request()) }

            assertTrue(failure is SafetyException)
            assertTrue(failure.message.orEmpty().contains("exit=$exit"))
            assertTrue(failure.message.orEmpty().contains("injected sync failure"))
            assertEquals(1, f.writer.copies.size)
            assertEquals(1, f.writer.syncCalls)
            assertStages(f.audit, Stage.CONFIRMED, Stage.WRITING, Stage.SYNCING, Stage.FAILED)
            assertEquals(exit, f.audit.events.last().exitCode)
            assertEquals(failure.message, f.audit.events.last().summary)
            assertEquals(image.bytes, f.audit.events.last().bytes)
            assertNoSuccess(f.audit)
            assertGateReleased(f.gate)
        }
    }

    @Test fun exceptionAfterCopyLaunchIsFailedWithoutSyncOrRetryAndReleasesGate() = runBlocking {
        val f = Fixture()
        val problem = IllegalStateException("copy launched, result unknown")
        f.writer.onCopy = { throw problem }

        assertSame(problem, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(0, f.writer.syncCalls)
        assertStages(f.audit, Stage.CONFIRMED, Stage.WRITING, Stage.FAILED)
        assertEquals(problem.message, f.audit.events.last().summary)
        assertNull(f.audit.events.last().exitCode)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun exceptionAfterSyncLaunchIsFailedAndDoesNotReuseCopyExitCode() = runBlocking {
        val f = Fixture()
        val problem = IllegalStateException("sync launched, result unknown")
        f.writer.onSync = { throw problem }

        assertSame(problem, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(1, f.writer.syncCalls)
        assertStages(f.audit, Stage.CONFIRMED, Stage.WRITING, Stage.SYNCING, Stage.FAILED)
        assertNull(f.audit.events.last().exitCode)
        assertEquals(image.bytes, f.audit.events.last().bytes)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun cancellationDuringCopyLogsInterruptedInNonCancellableContextAndReleasesGate() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        f.writer.onCopy = { entered.complete(Unit); awaitCancellation() }
        // A cancellable suspension in the audit proves interruption logging is shielded.
        f.audit.beforeAppend = { event -> if (event.stage == Stage.INTERRUPTED) yield() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { f.flash.execute(request()) }
        try {
            assertTrue(entered.isCompleted)
            job.cancel(CancellationException("cancelled after copy launch"))
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(1, f.writer.copies.size)
            assertEquals(0, f.writer.syncCalls)
            assertStages(f.audit, Stage.CONFIRMED, Stage.WRITING, Stage.INTERRUPTED)
            assertTrue(f.audit.events.last().summary.contains("cancelled after copy launch"))
            assertNull(f.audit.events.last().exitCode)
            assertNoSuccess(f.audit)
            assertGateReleased(f.gate)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun cancellationDuringSyncLogsInterruptedAndReleasesGate() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        f.writer.onSync = { entered.complete(Unit); awaitCancellation() }
        f.audit.beforeAppend = { event -> if (event.stage == Stage.INTERRUPTED) yield() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { f.flash.execute(request()) }
        try {
            assertTrue(entered.isCompleted)
            job.cancel(CancellationException("cancelled after sync launch"))
            job.join()

            assertEquals(1, f.writer.copies.size)
            assertEquals(1, f.writer.syncCalls)
            assertStages(f.audit, Stage.CONFIRMED, Stage.WRITING, Stage.SYNCING, Stage.INTERRUPTED)
            assertTrue(f.audit.events.last().summary.contains("cancelled after sync launch"))
            assertNull(f.audit.events.last().exitCode)
            assertEquals(image.bytes, f.audit.events.last().bytes)
            assertNoSuccess(f.audit)
            assertGateReleased(f.gate)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun concurrentRequestSharingGateIsRejectedImmediatelyNotQueued() = runBlocking {
        val gate = TransactionGate()
        val first = Fixture(gate)
        val second = Fixture(gate)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        first.writer.onCopy = { entered.complete(Unit); release.await() }
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) { first.flash.execute(request()) }
        var rejected: Throwable? = null
        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) {
            rejected = runCatching { second.flash.execute(request()) }.exceptionOrNull()
        }
        try {
            assertTrue(entered.isCompleted)
            assertFalse(firstJob.isCompleted)
            // A queued implementation would suspend here; do not wait for it to finish.
            assertTrue("second request must finish before the first is released", secondJob.isCompleted)
            assertTrue(rejected is SafetyException)
            assertTrue(second.writer.copies.isEmpty())
            assertEquals(0, second.writer.syncCalls)
            assertTrue(second.audit.attempts.isEmpty())

            release.complete(Unit)
            firstJob.join()
            yield()
            assertTrue("rejected request must not execute later", second.writer.copies.isEmpty())
            assertStages(first.audit, Stage.CONFIRMED, Stage.WRITING, Stage.SYNCING, Stage.SUCCESS)
            second.flash.execute(request())
            assertEquals(1, second.writer.copies.size)
            assertStages(second.audit, Stage.CONFIRMED, Stage.WRITING, Stage.SYNCING, Stage.SUCCESS)
        } finally {
            secondJob.cancelAndJoin()
            firstJob.cancelAndJoin()
        }
    }

    private suspend fun assertPrewriteAuditFailure(stage: Stage) {
        val f = Fixture()
        f.audit.failingStages += stage
        assertSame(f.audit.failure, failureOf { f.flash.execute(request()) })
        assertTrue(f.writer.copies.isEmpty())
        assertEquals(0, f.writer.syncCalls)
        val expected = if (stage == Stage.CONFIRMED) listOf(Stage.FAILED) else listOf(Stage.CONFIRMED, Stage.FAILED)
        assertEquals(expected, f.audit.events.map { it.stage })
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun auditFailureAtConfirmationNeverWrites() = runBlocking {
        assertPrewriteAuditFailure(Stage.CONFIRMED)
    }

    @Test fun auditFailureAtWritingNeverWrites() = runBlocking {
        assertPrewriteAuditFailure(Stage.WRITING)
    }

    @Test fun entirelyBrokenAuditNeverWritesAndStillReleasesGate() = runBlocking {
        val f = Fixture()
        f.audit.failingStages.addAll(Stage.entries)

        assertSame(f.audit.failure, failureOf { f.flash.execute(request()) })
        assertTrue(f.writer.copies.isEmpty())
        assertEquals(0, f.writer.syncCalls)
        assertEquals(listOf(Stage.CONFIRMED, Stage.FAILED), f.audit.attempts.map { it.stage })
        assertTrue(f.audit.events.isEmpty())
        assertGateReleased(f.gate)
    }

    @Test fun auditFailureAfterCopyCannotReportSuccess() = runBlocking {
        val f = Fixture()
        f.audit.failingStages += Stage.SYNCING

        assertSame(f.audit.failure, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(0, f.writer.syncCalls)
        assertStages(f.audit, Stage.CONFIRMED, Stage.WRITING, Stage.FAILED)
        assertEquals(image.bytes, f.audit.events.last().bytes)
        assertEquals(0, f.audit.events.last().exitCode)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun auditFailureRecordingSuccessFailsDespiteSuccessfulCopyAndSync() = runBlocking {
        val f = Fixture()
        f.audit.failingStages += Stage.SUCCESS

        assertSame(f.audit.failure, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(1, f.writer.syncCalls)
        assertStages(f.audit, Stage.CONFIRMED, Stage.WRITING, Stage.SYNCING, Stage.FAILED)
        assertEquals(listOf(Stage.CONFIRMED, Stage.WRITING, Stage.SYNCING, Stage.SUCCESS, Stage.FAILED),
            f.audit.attempts.map { it.stage })
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
        f.writer.onCopy = { throw original }
        f.audit.failingStages += Stage.INTERRUPTED

        assertSame(original, failureOf { f.flash.execute(request()) })
        assertEquals(1, f.writer.copies.size)
        assertEquals(0, f.writer.syncCalls)
        assertEquals(Stage.INTERRUPTED, f.audit.attempts.last().stage)
        assertNoSuccess(f.audit)
        assertGateReleased(f.gate)
    }

    @Test fun dataMountedMappedAndActiveSlotTargetsAreDeliberatelyNotRiskChecked() = runBlocking {
        // Pretend slot a is active. DirectWriteRequest has no Environment or inactive-slot policy.
        val targets = listOf(
            partition.copy(risk = Risk.DATA),
            partition.copy(mounted = Truth.YES),
            partition.copy(mapped = Truth.YES),
            partition.copy(slot = "a"),
            partition.copy(
                name = "userdata_a", risk = Risk.DATA, mounted = Truth.YES, mapped = Truth.YES,
                slot = "a", physical = Truth.NO, writable = Truth.NO, kind = ImageKind.UNKNOWN
            )
        )
        for (target in targets) {
            val f = Fixture()
            f.flash.execute(request(target = target))
            assertEquals(listOf(target to image), f.writer.copies)
            assertEquals(1, f.writer.syncCalls)
            assertStages(f.audit, Stage.CONFIRMED, Stage.WRITING, Stage.SYNCING, Stage.SUCCESS)
        }
    }

    @Test fun arbitraryImageLengthsAreForwardedWithoutSizeChecks() = runBlocking {
        for (bytes in listOf(Long.MIN_VALUE, -1L, 0L, 1L, 2048L, 4097L, Long.MAX_VALUE)) {
            val selected = image.copy(bytes = bytes)
            val f = Fixture()
            f.flash.execute(request(selected = selected))

            assertEquals(listOf(partition to selected), f.writer.copies)
            assertEquals(1, f.writer.syncCalls)
            assertStages(f.audit, Stage.CONFIRMED, Stage.WRITING, Stage.SYNCING, Stage.SUCCESS)
            assertEquals(bytes, f.audit.events.last().bytes)
        }
    }

    @Test fun targetKindsAndLengthsDoNotInvokeLegacyCompatibilityPolicy() = runBlocking {
        for (kind in ImageKind.entries) {
            for (bytes in listOf(-1L, 0L, 1L, Long.MAX_VALUE)) {
                val target = partition.copy(kind = kind, bytes = bytes)
                val f = Fixture()
                f.flash.execute(request(target = target))

                assertEquals(listOf(target to image), f.writer.copies)
                assertEquals(1, f.writer.syncCalls)
                assertStages(f.audit, Stage.CONFIRMED, Stage.WRITING, Stage.SYNCING, Stage.SUCCESS)
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
