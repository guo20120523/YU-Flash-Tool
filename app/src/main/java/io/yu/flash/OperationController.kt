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
    data class Flash(val request: DirectWriteRequest) : Operation
}
internal data class OperationState(val busy: Boolean = false, val phase: String = "就绪", val bytes: Long = 0,
    val imported: SelectedImage? = null, val importTarget: Partition? = null, val error: String? = null)
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
        var id = UUID.randomUUID().toString()
        var terminalRecorded = false
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
                    val image = graph.importer.import(operation.uri) { actual -> state.value = state.value.copy(bytes = actual) }
                    graph.journal.append(Event(id, Stage.SUCCESS, target.name, target.slot, image.bytes, 0, "文件复制到暂存完成；未检查格式或兼容性，尚未写入"))
                    state.value = OperationState(busy = true, phase = "文件已暂存，等待警告确认", bytes = image.bytes, imported = image, importTarget = target)
                }
                is Operation.Flash -> {
                    DirectFlash(io.yu.flash.root.DirectDdWriter(graph.partitions.shell), object : AuditLog {
                        override suspend fun append(event: Event) {
                            id = event.taskId // Any controller-level failure belongs to the same transaction.
                            graph.journal.append(event)
                            if (event.stage in setOf(Stage.SUCCESS, Stage.FAILED, Stage.INTERRUPTED)) terminalRecorded = true
                            val label = when(event.stage) {
                                Stage.WRITING -> "dd 写入中 · 中断可能损坏分区"; Stage.SYNCING -> "执行 sync"
                                Stage.SUCCESS -> "dd / sync 返回 0，未验证内容"
                                else -> event.stage.name
                            }
                            state.value = state.value.copy(phase = label, bytes = event.bytes); phase(label)
                        }
                    }, graph.gate).execute(operation.request)
                    state.value = OperationState(busy = true, phase = "dd / sync 返回 0，未验证内容或可启动性", bytes = operation.request.image.bytes)
                }
            }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                if (!terminalRecorded) runCatching { graph.journal.append(Event(id, if (e is CancellationException) Stage.INTERRUPTED else Stage.FAILED,
                    target.name, target.slot, state.value.bytes, summary = e.message ?: "未知错误")) }
                state.value = OperationState(busy = true, phase = "任务未完成", error = e.message ?: "任务中断")
            }
            if (e is CancellationException) throw e
        }
    }
}
