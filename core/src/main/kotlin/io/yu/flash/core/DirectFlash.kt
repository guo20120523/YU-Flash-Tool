package io.yu.flash.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Bytes copied from the picker, not a validated image. No signature/hash/size compatibility claim. */
data class SelectedImage(val file: File, val bytes: Long)
data class DirectWriteRequest(val target: Partition, val image: SelectedImage, val acknowledged: Boolean)
data class CommandOutcome(val exitCode: Int, val error: String = "")
interface DirectWriter {
    suspend fun copy(target: Partition, image: SelectedImage): CommandOutcome
    suspend fun sync(): CommandOutcome
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

/** Warning-only direct dd route. Deliberately does NOT call the legacy SafetyPolicy/FlashTransaction. */
class DirectFlash(private val writer: DirectWriter, private val audit: AuditLog, private val gate: TransactionGate) {
    suspend fun execute(request: DirectWriteRequest) = gate.exclusive {
        val id = UUID.randomUUID().toString()
        var exit: Int? = null
        var copied = false
        suspend fun record(stage: Stage, text: String) = audit.append(Event(id, stage, request.target.name,
            request.target.slot, if (copied) request.image.bytes else 0L, exit, text))
        try {
            requireSafe(request.acknowledged, "必须先阅读警告并确认直接写入")
            record(Stage.CONFIRMED, "已确认直接 dd：无自动备份、镜像/目标风险检查或读回校验")
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
            record(Stage.SUCCESS, "dd 与 sync 返回 0；未校验写入内容、可启动性或可恢复性")
        } catch (e: Exception) {
            withContext(NonCancellable) {
                runCatching { record(if (e is CancellationException) Stage.INTERRUPTED else Stage.FAILED,
                    e.message ?: "直接写入未完成；dd 子进程状态可能未知") }
            }
            throw e
        }
    }
}
