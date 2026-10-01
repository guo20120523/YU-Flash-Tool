package io.yu.flash.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SafetyTest {
    private val partition = Partition("boot_b", "/dev/block/by-name/boot_b", "/dev/block/sda2", "8:2", 4096,
        ImageKind.BOOT, "b", Risk.BOOT_CHAIN, Truth.NO, Truth.NO)
    private val env = Environment("test-device", "a", Truth.YES, Truth.YES, Truth.YES, 80, 28.0, true, true)
    private fun image(dir: File): ImportedImage {
        val bytes = ByteArray(4096)
        "ANDROID!".toByteArray().copyInto(bytes)
        bytes[40] = 4 // boot v4 header with empty kernel/ramdisk (init_boot-compatible structural test only)
        val file = File(dir, "import.raw").apply { writeBytes(bytes) }
        return ImageInspector.inspect(file, "user-supplied.img")
    }
    private fun registry() = object : ProfileRegistry {
        override fun find(target: Partition, image: ImportedImage, env: Environment) =
            WriteProfile(env.fingerprint, target.name, target.identity, image.kind, image.sha256, true)
    }
    private class FakeDevice(val dir: File, var target: Partition, var environment: Environment) : DeviceAccess {
        val block = File(dir, "virtual-block.bin").apply { writeBytes(ByteArray(4096) { 0x5a }) }
        var fail: Stage? = null; var writes = 0; var refreshes = 0; var changeOnSecond = false
        override suspend fun refresh(target: Partition): Pair<Partition, Environment> {
            refreshes++
            return (if (changeOnSecond && refreshes > 1) this.target.copy(identity = "8:3") else this.target) to environment
        }
        override suspend fun backup(target: Partition, location: BackupLocation, taskId: String): Backup {
            if (fail == Stage.BACKUP) error("injected backup failure")
            val file = File(dir, "original.partial"); block.copyTo(file)
            return Backup(file.path, file.length(), ImageInspector.sha256(file), "mock-metadata")
        }
        override suspend fun verifyBackup(backup: Backup) {
            if (fail == Stage.BACKUP_VERIFY) error("injected verification failure")
            requireSafe(File(backup.path).length() == backup.bytes && ImageInspector.sha256(File(backup.path)) == backup.sha256, "bad backup")
        }
        override suspend fun write(target: Partition, image: ImportedImage) {
            writes++; if (fail == Stage.WRITING) error("injected write failure")
            image.file.copyTo(block, overwrite = true)
        }
        override suspend fun sync() { if (fail == Stage.SYNCING) error("injected sync failure") }
        override suspend fun hashRange(target: Partition, bytes: Long) = if (fail == Stage.READBACK) "bad" else ImageInspector.sha256(block)
    }
    private fun fixture(block: suspend (File, ImportedImage, FakeDevice, MutableList<Event>, ConfirmedWrite) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("yu-flash-test").toFile()
        try {
            val image = image(dir); val device = FakeDevice(dir, partition, env); val log = mutableListOf<Event>()
            block(dir, image, device, log, ConfirmedWrite(partition, image, env, BackupLocation(dir.path, Long.MAX_VALUE, dir.path), "boot_b", SafetySettings(), true))
        } finally { dir.deleteRecursively() }
    }
    private fun transaction(device: DeviceAccess, log: MutableList<Event>, registry: ProfileRegistry = registry(), gate: TransactionGate = TransactionGate()) =
        FlashTransaction(device, object : AuditLog { override suspend fun append(event: Event) { log += event } }, registry, gate)

    @Test fun successfulFileSimulationIsOrderedAndBackedUp() = fixture { _, image, device, log, request ->
        val original = device.block.readBytes()
        transaction(device, log).execute(request)
        assertEquals(1, device.writes)
        assertArrayEquals(original, File(request.image.file.parentFile, "original.partial").readBytes())
        assertEquals(image.sha256, ImageInspector.sha256(device.block))
        assertEquals(listOf(Stage.IMAGE_CHECK, Stage.TARGET_CHECK, Stage.CONFIRMED, Stage.BACKUP, Stage.BACKUP_VERIFY,
            Stage.NORMALIZE, Stage.RECHECK, Stage.WRITING, Stage.SYNCING, Stage.READBACK, Stage.SUCCESS), log.map { it.stage })
    }
    @Test fun noQualifiedDeviceCanNeverWrite() = fixture { _, _, d, log, r ->
        assertTrue(runCatching { transaction(d, log, NoQualifiedDevices).execute(r) }.isFailure); assertEquals(0, d.writes)
    }
    @Test fun backupFailureStopsWrite() = fixture { _, _, d, log, r ->
        d.fail = Stage.BACKUP; assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure); assertEquals(0, d.writes)
    }
    @Test fun backupVerificationFailureStopsWrite() = fixture { _, _, d, log, r ->
        d.fail = Stage.BACKUP_VERIFY; assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure); assertEquals(0, d.writes)
    }
    @Test fun cancellationConfirmationDoesNotWrite() = fixture { _, _, d, log, r ->
        assertTrue(runCatching { transaction(d, log).execute(r.copy(confirmed = false)) }.isFailure); assertEquals(0, d.writes)
    }
    @Test fun typedNameMustMatchExactly() = fixture { _, _, d, log, r ->
        assertTrue(runCatching { transaction(d, log).execute(r.copy(typedName = "boot_a")) }.isFailure); assertEquals(0, d.writes)
    }
    @Test fun changedTargetBeforeWriteStopsTransaction() = fixture { _, _, d, log, r ->
        d.changeOnSecond = true; assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure); assertEquals(0, d.writes)
    }
    @Test fun changedSlotStopsTransaction() = fixture { _, _, d, log, r ->
        d.environment = env.copy(slot = "b"); assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure); assertEquals(0, d.writes)
    }
    @Test fun changedStagingStopsTransaction() = fixture { _, i, d, log, r ->
        i.file.appendBytes(byteArrayOf(1)); assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure); assertEquals(0, d.writes)
    }
    @Test fun writeErrorDoesNotRetry() = fixture { _, _, d, log, r ->
        d.fail = Stage.WRITING; assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure)
        assertEquals(1, d.writes); assertEquals(Stage.FAILED, log.last().stage); assertFalse(log.any { it.stage == Stage.SUCCESS })
    }
    @Test fun syncErrorIsFailure() = fixture { _, _, d, log, r ->
        d.fail = Stage.SYNCING; assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure); assertEquals(Stage.FAILED, log.last().stage)
    }
    @Test fun readbackMismatchIsFailure() = fixture { _, _, d, log, r ->
        d.fail = Stage.READBACK; assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure); assertEquals(Stage.FAILED, log.last().stage)
    }
    @Test fun unknownAndDangerousEnvironmentsFailClosed() = fixture { _, i, _, _, _ ->
        listOf(env.copy(slot = null), env.copy(unlocked = Truth.UNKNOWN), env.copy(snapshotSafe = Truth.UNKNOWN),
            env.copy(avbCompatible = Truth.UNKNOWN), env.copy(battery = 49), env.copy(temperatureC = 43.0),
            env.copy(charging = false), env.copy(toolsReady = false)).forEach {
            assertTrue(runCatching { SafetyPolicy.write(partition, i, it, SafetySettings(), registry()) }.isFailure)
        }
    }
    @Test fun mountedMappedDataAndUnknownTargetsFailClosed() = fixture { _, i, _, _, _ ->
        listOf(partition.copy(mounted = Truth.YES), partition.copy(mounted = Truth.UNKNOWN), partition.copy(mapped = Truth.YES),
            partition.copy(risk = Risk.DATA), partition.copy(risk = Risk.UNKNOWN), partition.copy(slot = "a"),
            partition.copy(bytes = 0), partition.copy(device = "/tmp/evil")).forEach {
            assertTrue(runCatching { SafetyPolicy.write(it, i, env, SafetySettings(), registry()) }.isFailure)
        }
    }
    @Test fun wrongLengthAndTypeAreRejected() = fixture { _, i, _, _, _ ->
        listOf(i.copy(bytes = 4097), i.copy(bytes = 2048), i.copy(kind = ImageKind.EXT4)).forEach {
            assertTrue(runCatching { SafetyPolicy.write(partition, it, env, SafetySettings(), registry()) }.isFailure)
        }
    }
    @Test fun doubleTapDoesNotQueueSecondTask() = runBlocking {
        val gate = TransactionGate(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val job = launch { gate.exclusive { entered.complete(Unit); release.await() } }
        entered.await(); assertTrue(runCatching { gate.exclusive { error("must not execute") } }.exceptionOrNull() is SafetyException)
        release.complete(Unit); job.join(); gate.exclusive { assertTrue(true) }
    }
    @Test fun imageSignaturesAreContentBased() {
        assertEquals(ImageKind.SPARSE, ImageInspector.detect(byteArrayOf(0x3a, 0xff.toByte(), 0x26, 0xed.toByte())))
        assertEquals(ImageKind.ARCHIVE, ImageInspector.detect(byteArrayOf(0x50, 0x4b)))
        assertEquals(ImageKind.UNKNOWN, ImageInspector.detect("fake.img".toByteArray()))
        val ext4 = ByteArray(4096); ext4[1080] = 0x53; ext4[1081] = 0xef.toByte()
        assertEquals(ImageKind.EXT4, ImageInspector.detect(ext4))
    }
    @Test fun mergedDisplayAliasesDoNotChangeStableTarget() {
        assertTrue(SafetyPolicy.sameTarget(partition, partition.copy(aliases = listOf(partition.alias, "/dev/block/bootdevice/by-name/boot_b"))))
        assertFalse(SafetyPolicy.sameTarget(partition, partition.copy(identity = "8:4")))
        assertFalse(SafetyPolicy.sameTarget(partition, partition.copy(device = "/dev/block/sda4")))
    }
    @Test fun auditFailureNeverPermitsWritingAndReleasesGate() = fixture { _, _, d, _, r ->
        val gate = TransactionGate()
        val brokenAudit = object : AuditLog { override suspend fun append(event: Event) { error("injected disk-full journal") } }
        assertTrue(runCatching { FlashTransaction(d, brokenAudit, registry(), gate).execute(r) }.isFailure)
        assertEquals(0, d.writes)
        gate.exclusive { assertTrue(true) }
    }
    @Test fun cancellationWhileBackingUpNeverWrites() = fixture { _, _, d, log, r ->
        val entered = CompletableDeferred<Unit>()
        val adapter = object : DeviceAccess by d {
            override suspend fun backup(target: Partition, location: BackupLocation, taskId: String): Backup {
                entered.complete(Unit); awaitCancellation()
            }
        }
        coroutineScope {
            val task = launch { transaction(adapter, log).execute(r) }
            entered.await(); task.cancelAndJoin()
        }
        assertEquals(0, d.writes); assertEquals(Stage.INTERRUPTED, log.last().stage)
    }
    @Test fun existingNormalizedImageIsNotOverwritten() = fixture { dir, _, d, log, r ->
        val existing = File(dir, "boot_b.img").apply { writeText("do not overwrite") }
        assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure)
        assertEquals("do not overwrite", existing.readText()); assertEquals(0, d.writes)
    }
    @Test fun emptyTruncatedSparseAndDisguisedImagesFail() {
        val dir = Files.createTempDirectory("yu-image-test").toFile()
        try {
            listOf(byteArrayOf(), "ANDROID!".toByteArray(), byteArrayOf(0x50, 0x4b),
                byteArrayOf(0x3a, 0xff.toByte(), 0x26, 0xed.toByte()), "not an image".toByteArray()).forEachIndexed { n, bytes ->
                val file = File(dir, "$n.img").apply { writeBytes(bytes) }
                assertTrue(runCatching { ImageInspector.inspect(file, file.name) }.isFailure)
            }
        } finally { dir.deleteRecursively() }
    }
}
