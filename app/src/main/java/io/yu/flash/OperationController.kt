package io.yu.flash

import android.net.Uri
import io.yu.flash.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal sealed interface Operation {
    data class Read(val target: Partition, val location: BackupLocation) : Operation
    data class Import(val target: Partition, val uri: Uri) : Operation
    data class Flash(val request: ConfirmedWrite) : Operation
}
internal data class OperationState(val busy: Boolean = false, val phase: String = "就绪", val bytes: Long = 0,
    val imported: ImportedImage? = null, val importTarget: Partition? = null, val error: String? = null)
internal class OperationController(private val graph: AppGraph) {
    private val pending = AtomicReference<Operation?>(null)
    private val state = MutableStateFlow(OperationState())
    val flow = state.asStateFlow()
    @Synchronized fun enqueue(operation: Operation) {
        requireSafe(!state.value.busy && pending.get() == null, "已有任务运行，拒绝重复点击")
        pending.set(operation); state.value = OperationState(busy = true, phase = "等待前台服务")
    }
    fun take(): Operation? = pending.getAndSet(null)
    @Synchronized fun rejectPending(operation: Operation, message: String) {
        if (pending.compareAndSet(operation, null)) state.value = OperationState(error = message, phase = "未启动")
    }
    fun finished() { state.value = state.value.copy(busy = false) }
    fun rejected(message: String) { pending.set(null); state.value = OperationState(error = message, phase = "未启动") }
    fun clearImport() { if (!state.value.busy) state.value = OperationState() }
    suspend fun execute(operation: Operation, phase: (String) -> Unit) {
        val id = UUID.randomUUID().toString()
        val target = when (operation) { is Operation.Read -> operation.target; is Operation.Import -> operation.target; is Operation.Flash -> operation.request.target }
        suspend fun stage(s: Stage, message: String) {
            graph.journal.append(Event(id, s, target.name, target.slot, summary = message))
            state.value = state.value.copy(phase = message); phase(message)
        }
        try {
            graph.recovered.await()
            when (operation) {
                is Operation.Read -> graph.gate.exclusive {
                    stage(Stage.TARGET_CHECK, "检查备份目标与空间")
                    stage(Stage.BACKUP, "读取分区 · 无可靠百分比")
                    val backup = graph.device.backup(target, operation.location, id)
                    stage(Stage.BACKUP_VERIFY, "重新计算备份 SHA-256")
                    graph.device.verifyBackup(backup)
                    graph.journal.append(Event(id, Stage.SUCCESS, target.name, target.slot, backup.bytes, 0,
                        "备份验证完成\n${backup.path}\nSHA-256 ${backup.sha256}\n不保证在线一致性或一定可恢复"))
                    state.value = OperationState(busy = true, phase = "备份验证完成", bytes = backup.bytes)
                }
                is Operation.Import -> graph.gate.exclusive {
                    stage(Stage.IMPORTING, "从文档提供器导入私有暂存")
                    val image = graph.importer.import(operation.uri, target.bytes) { actual -> state.value = state.value.copy(bytes = actual) }
                    stage(Stage.IMAGE_CHECK, "检查签名、实际长度和 SHA-256")
                    graph.journal.append(Event(id, Stage.SUCCESS, target.name, target.slot, image.bytes, 0, "导入检查完成；不代表镜像兼容"))
                    state.value = OperationState(busy = true, phase = "镜像检查完成", bytes = image.bytes, imported = image, importTarget = target)
                }
                is Operation.Flash -> {
                    FlashTransaction(graph.device, graph.journal, NoQualifiedDevices, graph.gate).execute(operation.request)
                    state.value = OperationState(busy = true, phase = "读回校验结束，不代表可启动")
                }
            }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                runCatching { graph.journal.append(Event(id, if (e is CancellationException) Stage.INTERRUPTED else Stage.FAILED,
                    target.name, target.slot, state.value.bytes, summary = e.message ?: "未知错误")) }
                state.value = OperationState(busy = true, phase = "任务未完成", error = e.message ?: "任务中断")
            }
            if (e is CancellationException) throw e
        }
    }
}
