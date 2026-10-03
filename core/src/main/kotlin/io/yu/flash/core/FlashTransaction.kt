package io.yu.flash.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One shared gate per process for backup/import/flash. tryLock rejects queued double taps. */
class TransactionGate {
    private val mutex = Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T {
        requireSafe(mutex.tryLock(), "已有任务正在运行，请勿重复提交")
        try { return block() } finally { mutex.unlock() }
    }
}
data class ConfirmedWrite(
    val target: Partition, val image: ImportedImage, val environment: Environment,
    val location: BackupLocation, val typedName: String, val settings: SafetySettings,
    val confirmed: Boolean, val compatibilityRiskAccepted: Boolean = false
)
class FlashTransaction(
    private val device: DeviceAccess, private val audit: AuditLog,
    private val gate: TransactionGate
) {
    suspend fun execute(request: ConfirmedWrite): String = gate.exclusive {
        val id = UUID.randomUUID().toString()
        val target = request.target
        suspend fun stage(s: Stage, text: String = "", bytes: Long = 0) =
            audit.append(Event(id, s, target.name, target.slot, bytes, summary = text))
        try {
            stage(Stage.IMAGE_CHECK)
            requireSafe(request.confirmed && request.typedName == target.name, "用户取消或分区名称确认不匹配")
            requireSafe(request.compatibilityRiskAccepted, "必须明确确认：AVB/回滚/设备兼容性未验证，可能无法启动")
            val image = ImageInspector.inspect(request.image.file, request.image.displayName)
            requireSafe(image == request.image, "暂存镜像发生变化")
            stage(Stage.TARGET_CHECK)
            val (fresh, environment) = device.refresh(target)
            requireSafe(SafetyPolicy.sameTarget(target, fresh) && environment.slot == request.environment.slot &&
                environment.fingerprint == request.environment.fingerprint, "目标身份、设备构建或槽位发生变化")
            SafetyPolicy.write(fresh, image, environment, request.settings)
            stage(Stage.CONFIRMED)
            stage(Stage.BACKUP)
            val backup = device.backup(fresh, request.location, id)
            requireSafe(backup.bytes == target.bytes, "原分区备份容量不匹配")
            stage(Stage.BACKUP_VERIFY)
            device.verifyBackup(backup)
            stage(Stage.NORMALIZE, "规范命名不改变内容，也不代表兼容")
            requireSafe(Regex("[A-Za-z0-9_.-]{1,96}").matches(target.name), "非法分区名称")
            val normalized = File(image.file.parentFile, "${target.name}.img")
            requireSafe(!normalized.exists() && image.file.renameTo(normalized), "暂存副本规范命名失败或重名")
            val ready = ImageInspector.inspect(normalized, image.displayName)
            requireSafe(ready.sha256 == image.sha256 && ready.bytes == image.bytes, "重命名后的镜像校验失败")
            stage(Stage.RECHECK)
            device.verifyBackup(backup)
            val (last, lastEnvironment) = device.refresh(target)
            requireSafe(SafetyPolicy.sameTarget(fresh, last) && lastEnvironment.slot == environment.slot &&
                lastEnvironment.fingerprint == environment.fingerprint, "写入前目标或槽位改变")
            SafetyPolicy.write(last, ready, lastEnvironment, request.settings)
            stage(Stage.WRITING, "开始后中断可能导致分区损坏；不会自动重试或回滚")
            val receipt = device.writeAndVerify(last, ready, backup) { step, count -> stage(step, bytes = count) }
            requireSafe(receipt == WriteReceipt(last.identity, ready.bytes, ready.sha256), "写入回执身份/长度/SHA-256 不匹配")
            stage(Stage.SUCCESS, "仅验证 ${ready.bytes} 字节与输入一致；不保证兼容或可启动", ready.bytes)
            id
        } catch (e: Exception) {
            withContext(NonCancellable) {
                stage(if (e is CancellationException) Stage.INTERRUPTED else Stage.FAILED,
                    "${e.message ?: e.javaClass.simpleName}；不得自动续写/重试/回滚/重启")
            }
            throw e
        }
    }
}
