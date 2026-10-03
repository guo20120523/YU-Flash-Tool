package io.yu.flash.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** All writes in this suite are to temporary ordinary files, never the descriptive /dev paths. */
class SafetyTest {
    private val partition = Partition("boot_b", "/dev/block/by-name/boot_b", "/dev/block/sda2", "8:2", 4096,
        ImageKind.BOOT, "b", Risk.BOOT_CHAIN, Truth.NO, Truth.NO, physical = Truth.YES, writable = Truth.YES)
    private val env = Environment("test-device", "a", Truth.YES, Truth.YES, Truth.UNKNOWN, 80, 28.0, true, true)
    private fun image(dir: File): ImportedImage {
        val bytes = ByteArray(4096)
        "ANDROID!".toByteArray().copyInto(bytes)
        bytes[40] = 4 // boot v4 header with empty kernel/ramdisk (structural test, not a bootability claim)
        val file = File(dir, "import.raw").apply { writeBytes(bytes) }
        return ImageInspector.inspect(file, "user-supplied.img")
    }
    private class FakeDevice(val dir: File, var target: Partition, var environment: Environment) : DeviceAccess {
        val block = File(dir, "virtual-block.bin").apply { writeBytes(ByteArray(4096) { 0x5a }) }
        var fail: Stage? = null
        var writes = 0
        var refreshes = 0
        var backupChecks = 0
        var changeOnSecond = false
        var secondTarget: Partition? = null
        var secondEnvironment: Environment? = null
        var reportedBackupBytes: Long? = null
        var receiptOverride: WriteReceipt? = null
        var failSecondBackupCheck = false
        override suspend fun refresh(target: Partition): Pair<Partition, Environment> {
            refreshes++
            val fresh = if (refreshes > 1) {
                secondTarget ?: if (changeOnSecond) this.target.copy(identity = "8:3") else this.target
            } else this.target
            val currentEnvironment = if (refreshes > 1) secondEnvironment ?: environment else environment
            return fresh to currentEnvironment
        }
        override suspend fun backup(target: Partition, location: BackupLocation, taskId: String): Backup {
            if (fail == Stage.BACKUP) error("injected backup failure")
            val file = File(dir, "original.partial"); block.copyTo(file)
            return Backup(file.path, reportedBackupBytes ?: file.length(), ImageInspector.sha256(file), "mock-metadata")
        }
        override suspend fun verifyBackup(backup: Backup) {
            backupChecks++
            if (fail == Stage.BACKUP_VERIFY || (failSecondBackupCheck && backupChecks == 2)) error("injected verification failure")
            requireSafe(File(backup.path).length() == backup.bytes && ImageInspector.sha256(File(backup.path)) == backup.sha256, "bad backup")
        }
        override suspend fun writeAndVerify(target: Partition, image: ImportedImage, backup: Backup,
            stage: suspend (Stage, Long) -> Unit): WriteReceipt {
            writes++
            if (fail == Stage.WRITING) error("injected write failure")
            image.file.copyTo(block, overwrite = true)
            stage(Stage.SYNCING, image.bytes)
            if (fail == Stage.SYNCING) error("injected sync failure")
            stage(Stage.READBACK, 0)
            val hash = if (fail == Stage.READBACK) "bad" else ImageInspector.sha256(block)
            return receiptOverride ?: WriteReceipt(target.identity, image.bytes, hash)
        }
    }
    private fun fixture(block: suspend (File, ImportedImage, FakeDevice, MutableList<Event>, ConfirmedWrite) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("yu-flash-test").toFile()
        try {
            val image = image(dir); val device = FakeDevice(dir, partition, env); val log = mutableListOf<Event>()
            block(dir, image, device, log, ConfirmedWrite(partition, image, env,
                BackupLocation(dir.path, Long.MAX_VALUE, dir.path), "boot_b", SafetySettings(),
                confirmed = true, compatibilityRiskAccepted = true))
        } finally { dir.deleteRecursively() }
    }
    private fun transaction(device: DeviceAccess, log: MutableList<Event>, gate: TransactionGate = TransactionGate()) =
        FlashTransaction(device, object : AuditLog { override suspend fun append(event: Event) { log += event } }, gate)
    private suspend fun rejectedBeforeWrite(device: FakeDevice, log: MutableList<Event>, request: ConfirmedWrite) {
        val original = device.block.readBytes()
        assertTrue(runCatching { transaction(device, log).execute(request) }.isFailure)
        assertEquals(0, device.writes)
        assertArrayEquals(original, device.block.readBytes())
        assertEquals(Stage.FAILED, log.last().stage)
        assertFalse(log.any { it.stage == Stage.SUCCESS })
    }

    @Test fun successfulFileSimulationIsOrderedAndBackedUp() = fixture { dir, image, device, log, request ->
        val original = device.block.readBytes()
        val id = transaction(device, log).execute(request)
        assertEquals(1, device.writes)
        assertEquals(2, device.refreshes)
        assertEquals(2, device.backupChecks)
        assertArrayEquals(original, File(dir, "original.partial").readBytes())
        assertEquals(image.sha256, ImageInspector.sha256(device.block))
        assertEquals(listOf(Stage.IMAGE_CHECK, Stage.TARGET_CHECK, Stage.CONFIRMED, Stage.BACKUP, Stage.BACKUP_VERIFY,
            Stage.NORMALIZE, Stage.RECHECK, Stage.WRITING, Stage.SYNCING, Stage.READBACK, Stage.SUCCESS), log.map { it.stage })
        assertTrue(log.all { it.taskId == id && it.partition == partition.name && it.slot == partition.slot })
        assertEquals(image.bytes, log.single { it.stage == Stage.SYNCING }.bytes)
        assertEquals(image.bytes, log.last().bytes)
    }
    // Replaces the obsolete whitelist assertion: eligibility is not a device/AVB guarantee.
    @Test fun explicitCompatibilityRiskAcknowledgementIsRequired() = fixture { _, _, d, log, r ->
        rejectedBeforeWrite(d, log, r.copy(compatibilityRiskAccepted = false))
        assertEquals(0, d.refreshes)
    }
    @Test fun backupFailureStopsWrite() = fixture { _, _, d, log, r ->
        d.fail = Stage.BACKUP; rejectedBeforeWrite(d, log, r)
    }
    @Test fun backupVerificationFailureStopsWrite() = fixture { _, _, d, log, r ->
        d.fail = Stage.BACKUP_VERIFY; rejectedBeforeWrite(d, log, r)
    }
    @Test fun cancellationConfirmationDoesNotWrite() = fixture { _, _, d, log, r ->
        rejectedBeforeWrite(d, log, r.copy(confirmed = false))
    }
    @Test fun typedNameMustMatchExactly() = fixture { _, _, d, log, r ->
        listOf("boot_a", "BOOT_B", "boot_b ", " boot_b").forEach {
            rejectedBeforeWrite(d, log, r.copy(typedName = it))
        }
    }
    @Test fun changedTargetBeforeWriteStopsTransaction() = fixture { _, _, d, log, r ->
        d.changeOnSecond = true; rejectedBeforeWrite(d, log, r)
    }
    @Test fun changedSlotStopsTransaction() = fixture { _, _, d, log, r ->
        d.environment = env.copy(slot = "b"); rejectedBeforeWrite(d, log, r)
    }
    @Test fun changedStagingStopsTransaction() = fixture { _, i, d, log, r ->
        i.file.appendBytes(byteArrayOf(1)); rejectedBeforeWrite(d, log, r)
    }
    @Test fun writeErrorDoesNotRetry() = fixture { _, _, d, log, r ->
        d.fail = Stage.WRITING
        assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure)
        assertEquals(1, d.writes); assertEquals(Stage.FAILED, log.last().stage)
        assertFalse(log.any { it.stage == Stage.SUCCESS || it.stage == Stage.SYNCING })
    }
    @Test fun syncErrorIsFailure() = fixture { _, _, d, log, r ->
        d.fail = Stage.SYNCING
        assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure)
        assertEquals(1, d.writes); assertEquals(Stage.FAILED, log.last().stage)
        assertFalse(log.any { it.stage == Stage.READBACK || it.stage == Stage.SUCCESS })
    }
    @Test fun readbackMismatchIsFailure() = fixture { _, _, d, log, r ->
        d.fail = Stage.READBACK
        assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure)
        assertEquals(1, d.writes); assertEquals(Stage.FAILED, log.last().stage)
        assertFalse(log.any { it.stage == Stage.SUCCESS })
    }
    @Test fun unknownAndDangerousEnvironmentsFailClosed() = fixture { _, i, _, _, _ ->
        listOf(env.copy(slot = null), env.copy(slot = "unknown"), env.copy(unlocked = Truth.UNKNOWN),
            env.copy(unlocked = Truth.NO), env.copy(snapshotSafe = Truth.UNKNOWN), env.copy(snapshotSafe = Truth.NO),
            env.copy(battery = null), env.copy(battery = 49), env.copy(temperatureC = null), env.copy(temperatureC = -1.0),
            env.copy(temperatureC = 43.0), env.copy(temperatureC = Double.NaN), env.copy(temperatureC = Double.POSITIVE_INFINITY),
            env.copy(charging = false), env.copy(charging = null), env.copy(toolsReady = false)).forEach {
            assertTrue("environment must fail closed: $it", runCatching { SafetyPolicy.write(partition, i, it, SafetySettings()) }.exceptionOrNull() is SafetyException)
        }
    }
    @Test fun mountedMappedDataAndUnknownTargetsFailClosed() = fixture { _, i, _, _, _ ->
        listOf(partition.copy(mounted = Truth.YES), partition.copy(mounted = Truth.UNKNOWN), partition.copy(mapped = Truth.YES),
            partition.copy(mapped = Truth.UNKNOWN), partition.copy(risk = Risk.DATA), partition.copy(risk = Risk.UNKNOWN),
            partition.copy(risk = Risk.DYNAMIC), partition.copy(risk = Risk.CRITICAL), partition.copy(slot = "a"),
            partition.copy(slot = null), partition.copy(slot = "unknown"), partition.copy(bytes = 0),
            partition.copy(device = "/tmp/evil"), partition.copy(identity = "unknown"), partition.copy(name = "modem_b")).forEach {
            assertTrue("target must fail closed: $it", runCatching { SafetyPolicy.write(it, i, env, SafetySettings()) }.exceptionOrNull() is SafetyException)
        }
    }
    @Test fun wrongLengthAndTypeAreRejected() = fixture { _, i, _, _, _ ->
        listOf(i.copy(bytes = 4097), i.copy(bytes = 2048), i.copy(bytes = 0), i.copy(bytes = -1), i.copy(kind = ImageKind.EXT4)).forEach {
            assertTrue(runCatching { SafetyPolicy.write(partition, it, env, SafetySettings()) }.exceptionOrNull() is SafetyException)
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
        listOf(partition.copy(name = "boot_a"), partition.copy(alias = "/dev/block/by-name/boot_a"),
            partition.copy(identity = "8:4"), partition.copy(device = "/dev/block/sda4"), partition.copy(bytes = 8192),
            partition.copy(slot = "a"), partition.copy(kind = ImageKind.VENDOR_BOOT), partition.copy(risk = Risk.FILESYSTEM),
            partition.copy(physical = Truth.UNKNOWN)).forEach { assertFalse(SafetyPolicy.sameTarget(partition, it)) }
    }
    @Test fun auditFailureNeverPermitsWritingAndReleasesGate() = fixture { _, _, d, _, r ->
        val gate = TransactionGate()
        val brokenAudit = object : AuditLog { override suspend fun append(event: Event) { error("injected disk-full journal") } }
        assertTrue(runCatching { FlashTransaction(d, brokenAudit, gate).execute(r) }.isFailure)
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
        rejectedBeforeWrite(d, log, r)
        assertEquals("do not overwrite", existing.readText())
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
    @Test fun avbStateAndUnlistedFingerprintDoNotPretendToGuaranteeCompatibility() = fixture { _, i, _, _, _ ->
        Truth.entries.forEach { avb ->
            SafetyPolicy.write(partition, i, env.copy(fingerprint = "never-qualified-device", avbCompatible = avb), SafetySettings())
        }
    }
    @Test fun physicalAndWritableMustBothBeAffirmativelyKnown() = fixture { _, i, _, _, _ ->
        Truth.entries.forEach { physical -> Truth.entries.forEach { writable ->
            val result = runCatching { SafetyPolicy.write(partition.copy(physical = physical, writable = writable), i, env, SafetySettings()) }
            assertEquals("physical=$physical writable=$writable", physical == Truth.YES && writable == Truth.YES, result.isSuccess)
        } }
    }
    @Test fun nonAbRequiresBothDeviceAndTargetToBeNonAb() = fixture { _, i, _, _, _ ->
        val target = partition.copy(name = "recovery", alias = "/dev/block/by-name/recovery", slot = null)
        SafetyPolicy.write(target, i, env.copy(slot = null), SafetySettings())
        assertTrue(runCatching { SafetyPolicy.write(target, i, env, SafetySettings()) }.isFailure)
        assertTrue(runCatching { SafetyPolicy.write(partition, i, env.copy(slot = null), SafetySettings()) }.isFailure)
    }
    @Test fun eitherExplicitInactiveAbSlotIsAllowed() = fixture { _, i, _, _, _ ->
        SafetyPolicy.write(partition, i, env, SafetySettings())
        SafetyPolicy.write(partition.copy(name = "boot_a", alias = "/dev/block/by-name/boot_a", slot = "a"),
            i, env.copy(slot = "b"), SafetySettings())
    }
    @Test fun physicalFilesystemKindsAndNamesAreSupportedWithoutAllowingBootTypeMismatch() = fixture { _, i, _, _, _ ->
        listOf("system", "vendor", "product", "odm", "system_ext", "vendor_dlkm", "odm_dlkm", "system_dlkm").forEach { name ->
            listOf(ImageKind.EXT4, ImageKind.F2FS, ImageKind.EROFS).forEach { kind ->
                val target = partition.copy(name = "${name}_b", kind = kind, risk = Risk.FILESYSTEM)
                SafetyPolicy.write(target, i.copy(kind = kind), env, SafetySettings())
                assertTrue(runCatching { SafetyPolicy.write(target, i, env, SafetySettings()) }.isFailure)
            }
        }
        assertTrue(runCatching { SafetyPolicy.write(partition.copy(name = "userdata_b", kind = ImageKind.EXT4, risk = Risk.FILESYSTEM),
            i.copy(kind = ImageKind.EXT4), env, SafetySettings()) }.isFailure)
    }
    @Test fun settingsCannotRelaxBatteryFloorButCanRequireStricterThreshold() = fixture { _, i, _, _, _ ->
        SafetyPolicy.write(partition, i, env.copy(battery = 50, temperatureC = 42.0), SafetySettings(minBattery = 0))
        assertTrue(runCatching { SafetyPolicy.write(partition, i, env.copy(battery = 49), SafetySettings(minBattery = 0)) }.isFailure)
        assertTrue(runCatching { SafetyPolicy.write(partition, i, env.copy(battery = 79), SafetySettings(minBattery = 80)) }.isFailure)
        SafetyPolicy.write(partition, i, env.copy(battery = 100, temperatureC = 0.0), SafetySettings(minBattery = 101))
        SafetyPolicy.write(partition, i, env.copy(charging = false), SafetySettings(requireCharging = false))
    }
    @Test fun malformedImageHashesAreRejected() = fixture { _, i, _, _, _ ->
        listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64)).forEach {
            assertTrue(runCatching { SafetyPolicy.write(partition, i.copy(sha256 = it), env, SafetySettings()) }.isFailure)
        }
    }
    @Test fun wrongBackupLengthIsRejectedBeforeVerificationOrWriting() = fixture { _, _, d, log, r ->
        d.reportedBackupBytes = partition.bytes - 1
        rejectedBeforeWrite(d, log, r)
        assertEquals(0, d.backupChecks)
    }
    @Test fun secondBackupVerificationFailureStopsWrite() = fixture { _, _, d, log, r ->
        d.failSecondBackupCheck = true
        rejectedBeforeWrite(d, log, r)
        assertEquals(2, d.backupChecks)
    }
    @Test fun writableStateIsRecheckedImmediatelyBeforeWrite() = fixture { _, _, d, log, r ->
        d.secondTarget = partition.copy(writable = Truth.NO)
        rejectedBeforeWrite(d, log, r)
        assertEquals(2, d.refreshes)
    }
    @Test fun environmentIsRecheckedImmediatelyBeforeWrite() = fixture { _, _, d, log, r ->
        d.secondEnvironment = env.copy(battery = 49)
        rejectedBeforeWrite(d, log, r)
        assertEquals(2, d.refreshes)
    }
    @Test fun changedBuildFingerprintStopsTransaction() = fixture { _, _, d, log, r ->
        d.secondEnvironment = env.copy(fingerprint = "changed-build")
        rejectedBeforeWrite(d, log, r)
    }
    @Test fun receiptIdentityMustMatchPinnedTarget() = fixture { _, i, d, log, r ->
        d.receiptOverride = WriteReceipt("8:99", i.bytes, i.sha256)
        assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure)
        assertEquals(1, d.writes); assertEquals(Stage.FAILED, log.last().stage)
        assertFalse(log.any { it.stage == Stage.SUCCESS })
    }
    @Test fun receiptLengthMustMatchFullImage() = fixture { _, i, d, log, r ->
        d.receiptOverride = WriteReceipt(partition.identity, i.bytes - 1, i.sha256)
        assertTrue(runCatching { transaction(d, log).execute(r) }.isFailure)
        assertEquals(1, d.writes); assertEquals(Stage.FAILED, log.last().stage)
        assertFalse(log.any { it.stage == Stage.SUCCESS })
    }
    @Test fun auditMustPersistWritingBeforeAnyOutputBytes() = fixture { _, _, d, log, r ->
        val original = d.block.readBytes()
        val audit = object : AuditLog {
            override suspend fun append(event: Event) {
                if (event.stage == Stage.WRITING) error("injected journal failure before write")
                log += event
            }
        }
        val gate = TransactionGate()
        assertTrue(runCatching { FlashTransaction(d, audit, gate).execute(r) }.isFailure)
        assertEquals(0, d.writes); assertArrayEquals(original, d.block.readBytes())
        assertEquals(Stage.FAILED, log.last().stage)
        gate.exclusive { assertTrue(true) }
    }
}
