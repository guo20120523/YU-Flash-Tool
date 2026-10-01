package io.yu.flash.core

import java.io.File

/** Unknown is deliberately NOT equivalent to false. */
enum class Truth { YES, NO, UNKNOWN }
enum class RootState { NOT_REQUESTED, MISSING, DENIED, TIMEOUT, INACCESSIBLE, READY }
enum class ImageKind { BOOT, VENDOR_BOOT, AVB, EXT4, F2FS, EROFS, SPARSE, ARCHIVE, UNKNOWN }
enum class Risk { BOOT_CHAIN, DATA, DYNAMIC, CRITICAL, UNKNOWN }
data class Partition(
    val name: String, val alias: String, val device: String, val identity: String,
    val bytes: Long, val kind: ImageKind, val slot: String?, val risk: Risk,
    val mounted: Truth = Truth.UNKNOWN, val mapped: Truth = Truth.UNKNOWN,
    val aliases: List<String> = listOf(alias)
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
data class WriteProfile(
    val fingerprint: String, val partition: String, val deviceIdentity: String,
    val kind: ImageKind, val imageSha256: String, val rollbackChecked: Boolean,
    val inactiveOnly: Boolean = true
)
interface ProfileRegistry { fun find(target: Partition, image: ImportedImage, env: Environment): WriteProfile? }
/** Shipping registry is intentionally empty: no device has been qualified. No user switch bypasses this. */
object NoQualifiedDevices : ProfileRegistry {
    override fun find(target: Partition, image: ImportedImage, env: Environment): WriteProfile? = null
}
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
    suspend fun write(target: Partition, image: ImportedImage)
    suspend fun sync()
    suspend fun hashRange(target: Partition, bytes: Long): String
}
class SafetyException(message: String) : IllegalStateException(message)
fun requireSafe(value: Boolean, message: String) { if (!value) throw SafetyException(message) }
