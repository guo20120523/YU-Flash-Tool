package io.yu.flash.core

import java.io.File

/** Unknown is deliberately NOT equivalent to false. */
enum class Truth { YES, NO, UNKNOWN }
enum class RootState { NOT_REQUESTED, MISSING, DENIED, TIMEOUT, INACCESSIBLE, READY }
enum class ImageKind { BOOT, VENDOR_BOOT, AVB, EXT4, F2FS, EROFS, SPARSE, ARCHIVE, UNKNOWN }
enum class Risk { BOOT_CHAIN, FILESYSTEM, DATA, DYNAMIC, CRITICAL, UNKNOWN }
data class Partition(
    val name: String, val alias: String, val device: String, val identity: String,
    val bytes: Long, val kind: ImageKind, val slot: String?, val risk: Risk,
    val mounted: Truth = Truth.UNKNOWN, val mapped: Truth = Truth.UNKNOWN,
    val aliases: List<String> = listOf(alias),
    val physical: Truth = Truth.UNKNOWN, val writable: Truth = Truth.UNKNOWN
)
data class Environment(
    val fingerprint: String, val slot: String?, val unlocked: Truth,
    val snapshotSafe: Truth, val avbCompatible: Truth,
    val battery: Int?, val temperatureC: Double?, val charging: Boolean?,
    val toolsReady: Boolean
)
data class SafetySettings(val minBattery: Int = 50, val requireCharging: Boolean = true)
data class ImportedImage(val file: File, val displayName: String, val bytes: Long, val sha256: String, val kind: ImageKind)
data class Backup(val path: String, val bytes: Long, val sha256: String, val metadataPath: String)
data class BackupLocation(val path: String, val available: Long, val requested: String, val diagnostics: String = "")
enum class Stage {
    SELECTED, IMPORTING, IMAGE_CHECK, TARGET_CHECK, CONFIRMED, BACKUP, BACKUP_VERIFY,
    NORMALIZE, RECHECK, WRITING, SYNCING, READBACK, SUCCESS, FAILED, INTERRUPTED
}
data class Event(val taskId: String, val stage: Stage, val partition: String, val slot: String?,
    val bytes: Long = 0, val exitCode: Int? = null, val summary: String = "", val time: Long = System.currentTimeMillis())
interface AuditLog { suspend fun append(event: Event) }
interface DeviceAccess {
    suspend fun refresh(target: Partition): Pair<Partition, Environment>
    suspend fun backup(target: Partition, location: BackupLocation, taskId: String): Backup
    suspend fun verifyBackup(backup: Backup)
    /** Production implementation pins one exclusive target FD through write/fsync/readback. */
    suspend fun writeAndVerify(target: Partition, image: ImportedImage, backup: Backup,
        stage: suspend (Stage, Long) -> Unit): WriteReceipt
}
data class WriteReceipt(val identity: String, val bytes: Long, val sha256: String)
class SafetyException(message: String) : IllegalStateException(message)
fun requireSafe(value: Boolean, message: String) { if (!value) throw SafetyException(message) }
