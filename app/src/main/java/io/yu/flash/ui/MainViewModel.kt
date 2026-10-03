package io.yu.flash.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.yu.flash.*
import io.yu.flash.core.*
import io.yu.flash.root.*
import io.yu.flash.storage.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class HomeState(val root: RootState = RootState.NOT_REQUESTED, val loading: Boolean = false,
    val partitions: List<Partition> = emptyList(), val environment: Environment? = null,
    val diagnostics: String = "Root 用于只读检测、分区读取和受限写入。仅在您主动授权后执行命令。", val error: String? = null)
internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as YuApplication).graph
    val home = MutableStateFlow(HomeState())
    val settings = graph.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val events = graph.journal.events
    val operation = graph.operations.flow
    val backupPrompt = MutableStateFlow<Pair<Partition, BackupLocation>?>(null)
    val message = MutableStateFlow<String?>(null)
    val writePrompt = MutableStateFlow<ConfirmedWrite?>(null)
    val checkingWrite = MutableStateFlow(false)
    fun prepareWrite() {
        if (checkingWrite.value || operation.value.busy) return
        val image = operation.value.imported ?: return
        val target = operation.value.importTarget ?: return
        checkingWrite.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try { graph.gate.exclusive {
                graph.device.interlock.check()
                val (fresh, environment) = graph.device.refresh(target)
                requireSafe(SafetyPolicy.sameTarget(target, fresh), "目标改变，请重新检测并导入")
                val preferences = settings.value
                val safety = SafetySettings(preferences.minBattery, preferences.requireCharging)
                SafetyPolicy.write(fresh, image, environment, safety)
                val location = graph.device.location(preferences.backupPath)
                requireSafe(location.available >= Math.addExact(fresh.bytes, 64L * 1024 * 1024), "备份空间不足")
                writePrompt.value = ConfirmedWrite(fresh, image, environment, location, "", safety, false)
            } } catch (e: Exception) { message.value = e.message }
            finally { checkingWrite.value = false }
        }
    }
    fun confirmWrite(name: String, accepted: Boolean) {
        val prompt = writePrompt.value ?: return
        if (!accepted || name != prompt.target.name || operation.value.busy) return
        writePrompt.value = null
        start(Operation.Flash(prompt.copy(typedName = name, confirmed = true, compatibilityRiskAccepted = accepted)))
    }
    fun cancelWrite() { writePrompt.value = null }
    fun refresh() {
        if (home.value.loading || operation.value.busy) return
        home.value = home.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try { graph.gate.exclusive {
                graph.device.interlock.check()
                if (home.value.root != RootState.READY) graph.partitions.authorize()
                val result = graph.partitions.discover()
                home.value = HomeState(RootState.READY, partitions = result.partitions, environment = result.environment, diagnostics = result.diagnostics)
            } } catch (e: Exception) {
                home.value = home.value.copy(loading = false, root = (e as? RootFailure)?.state ?: RootState.INACCESSIBLE,
                    error = e.message ?: "检测失败")
            }
        }
    }
    fun prepareBackup(partition: Partition) = viewModelScope.launch(Dispatchers.IO) {
        try { graph.gate.exclusive {
            graph.device.interlock.check()
            SafetyPolicy.backup(partition)
            backupPrompt.value = partition to graph.device.location(settings.value.backupPath)
        } } catch (e: Exception) { message.value = e.message }
    }
    fun startBackup() {
        val prompt = backupPrompt.value ?: return
        backupPrompt.value = null
        start(Operation.Read(prompt.first, prompt.second))
    }
    fun import(partition: Partition, uri: Uri) = start(Operation.Import(partition, uri))
    private fun start(operation: Operation) {
        try { graph.operations.enqueue(operation) }
        catch (e: Exception) { message.value = e.message; return }
        try {
            ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), TaskService::class.java))
        } catch (e: Exception) { graph.operations.rejectPending(operation, e.message ?: "任务启动失败"); message.value = e.message }
    }
    fun dismissImport() { graph.operations.clearImport() }
    fun setText(key: String, value: String) { viewModelScope.launch { graph.settings.text(key, value) } }
    fun setFlag(key: String, value: Boolean) { viewModelScope.launch { graph.settings.flag(key, value) } }
    fun setBattery(value: Int) { viewModelScope.launch { graph.settings.battery(value) } }
    fun export(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("无法创建导出文档")
            stream.use { it.write(graph.journal.redacted().toByteArray()) }
            message.value = "已导出：不含文件路径、设备指纹、镜像内容和错误原文；仍含分区名及时间"
        } catch (e: Exception) { message.value = "导出失败：${e.message}" }
    }
    fun cleanup() = viewModelScope.launch(Dispatchers.IO) {
        if (operation.value.busy) { message.value = "任务运行时禁止清理"; return@launch }
        try {
            graph.gate.exclusive {
                graph.device.interlock.check()
                val staging = java.io.File(getApplication<Application>().filesDir, "staging")
                val ok = !staging.exists() || staging.deleteRecursively()
                dismissImport(); message.value = if (ok) "私有暂存已清理；未删除任何备份" else "部分暂存清理失败"
            }
        } catch (e: Exception) { message.value = e.message ?: "暂存清理失败" }
    }
}
