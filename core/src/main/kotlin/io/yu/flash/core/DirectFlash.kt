package io.yu.flash.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Bytes copied from the picker, not a format/compatibility validated image. */
data class SelectedImage(val file: File, val bytes: Long)
data class DirectWriteRequest(val target: Partition, val image: SelectedImage, val acknowledged: Boolean,
    val backupPath: String = "/sdcard/download")
data class CommandOutcome(val exitCode: Int, val error: String = "")
data class ByteDigest(val bytes: Long, val sha256: String)
interface DirectWriter {
    suspend fun backup(target: Partition, path: String, taskId: String): Backup
    suspend fun verifyBackup(backup: Backup)
    suspend fun hashImage(image: SelectedImage): ByteDigest
    suspend fun copy(target: Partition, image: SelectedImage): CommandOutcome
    suspend fun sync(): CommandOutcome
    suspend fun readBack(target: Partition, bytes: Long): ByteDigest
}

/** Fixed commands only. Quoting is not a flash compatibility policy. */
object DirectDdCommand {
    fun copy(source: String, target: String): String {
        requireSafe(source.startsWith("/") && !source.contains('\u0000') && !source.contains('\n') && !source.contains('\r'), "私有暂存路径无效")
        requireSafe(target.startsWith("/dev/block/") && !target.endsWith('/') && !target.contains("//") && Regex("/[A-Za-z0-9_./:-]+").matches(target) &&
            target.split('/').none { it == "." || it == ".." }, "目标不是已发现的块设备路径")
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        return "/system/bin/toybox dd if=${quote(source)} of=${quote(target)} bs=1048576"
    }
    const val SYNC = "/system/bin/toybox sync"
}

/** Direct dd, with mandatory backup and byte integrity checks, NOT legacy write compatibility policy. */
class DirectFlash(private val writer: DirectWriter, private val audit: AuditLog, private val gate: TransactionGate) {
    suspend fun execute(request: DirectWriteRequest) = gate.exclusive {
        val id = UUID.randomUUID().toString()
        var exit: Int? = null
        var copied = false
        var saved: Backup? = null
        suspend fun record(stage: Stage, text: String) = audit.append(Event(id, stage, request.target.name,
            request.target.slot, if (copied) request.image.bytes else 0L, exit, text))
        fun valid(digest: ByteDigest) = digest.bytes >= 0 && Regex("[a-f0-9]{64}").matches(digest.sha256)
        try {
            requireSafe(request.acknowledged, "必须先阅读警告并确认直接写入")
            record(Stage.CONFIRMED, "已确认直接 dd；强制完整备份及读回检测，不验证格式、机型或 AVB 兼容性")
            record(Stage.BACKUP, "写前完整备份至 ${request.backupPath}；失败即停止，不可跳过")
            val backup = writer.backup(request.target, request.backupPath, id)
            saved = backup
            requireSafe(request.target.bytes > 0 && backup.bytes == request.target.bytes &&
                backup.path.isNotBlank() && backup.metadataPath.isNotBlank() &&
                Regex("[a-f0-9]{64}").matches(backup.sha256), "完整备份结果无效，不执行写入")
            record(Stage.BACKUP_VERIFY, "完整备份：${backup.path}\n元数据：${backup.metadataPath}\n${backup.bytes} 字节 · SHA-256 ${backup.sha256}")
            writer.verifyBackup(backup)
            record(Stage.RECHECK, "计算私有文件实际长度与 SHA-256；不检查文件格式或设备兼容性")
            val before = writer.hashImage(request.image)
            requireSafe(valid(before) && before.bytes == request.image.bytes, "暂存文件长度或哈希无效，不执行写入")
            record(Stage.WRITING, "dd 写入中；中断可能留下部分改写，不能安全取消")
            val result = writer.copy(request.target, request.image)
            exit = result.exitCode
            requireSafe(result.exitCode == 0, "dd 失败 (exit=${result.exitCode}): ${result.error.take(240)}；可能已经部分写入")
            copied = true
            record(Stage.SYNCING, "dd 返回 0；正在执行 sync，尚未读回验证")
            exit = null
            val synced = writer.sync()
            exit = synced.exitCode
            requireSafe(synced.exitCode == 0, "sync 失败 (exit=${synced.exitCode}): ${synced.error.take(240)}；目标状态未验证")
            exit = null
            record(Stage.READBACK, "读回目标前 ${before.bytes} 字节并比较 SHA-256；仅检测该范围，未检测尾部，不保证尾部未被外部变化影响")
            val readback = writer.readBack(request.target, before.bytes)
            requireSafe(valid(readback) && readback == before, "读回长度或 SHA-256 不一致；写入验证失败，禁止自动重试")
            requireSafe(writer.hashImage(request.image) == before, "暂存文件在操作期间改变；不报告写入成功")
            record(Stage.SUCCESS, "完整备份：${backup.path}\n备份 SHA-256 ${backup.sha256}\n读回 ${readback.bytes} 字节 SHA-256 ${readback.sha256} 一致；不保证可启动、可恢复或断电持久性")
        } catch (e: Exception) {
            withContext(NonCancellable) {
                runCatching { record(if (e is CancellationException) Stage.INTERRUPTED else Stage.FAILED,
                    (e.message ?: "直接写入未完成；dd 子进程状态可能未知") +
                        (saved?.let { "\n保留备份及元数据：${it.path} · ${it.metadataPath}" } ?: "")) }
            }
            throw e
        }
    }
}
