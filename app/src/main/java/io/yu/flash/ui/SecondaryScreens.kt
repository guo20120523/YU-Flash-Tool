package io.yu.flash.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.yu.flash.BuildConfig
import io.yu.flash.storage.AppSettings

@Composable
internal fun TasksScreen(vm: MainViewModel) {
    val events by vm.events.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(vm::export) }
    Column(Modifier.fillMaxSize().padding(Spacing.medium)) {
        Text("任务记录", style = MaterialTheme.typography.headlineMedium)
        Text("记录操作阶段，不将命令成功等同于可启动。进程中断后不会自动恢复写入。", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { preview = true }, enabled = events.isNotEmpty()) { Text("导出脱敏记录") }
        if (events.isEmpty()) Text("暂无任务。备份、导入检查和受限事务的记录将在此显示。", Modifier.padding(vertical = Spacing.large))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            items(events) { event -> OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text("${event.partition} · ${event.stage}", style = MaterialTheme.typography.titleMedium)
                    Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.time))}\n任务 ${event.taskId}\n槽位 ${event.slot ?: "未知"} · ${event.bytes} 字节 · exit ${event.exitCode ?: "n/a"}", style = MaterialTheme.typography.labelMedium)
                    Text(event.summary, style = MaterialTheme.typography.bodyMedium)
                }
            } }
        }
    }
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text("导出前隐私说明") }, text = {
        Text("导出内容：时间、任务ID、分区名、槽位、阶段、字节数、退出码。不会导出设备指纹、文件路径、镜像内容或错误原文。分区名及时间仍可能透露使用情况，请谨慎分享。")
    }, confirmButton = { Button(onClick = { preview = false; export.launch("YU-Flash-Tool-redacted-log.txt") }) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { preview = false }) { Text("取消") } })
}
@Composable
internal fun SettingsScreen(vm: MainViewModel, settings: AppSettings, busy: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Text("外观", style = MaterialTheme.typography.titleLarge)
        ChoiceMenu("主题：${mapOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")[settings.theme]}", listOf("跟随系统", "浅色", "深色")) {
            vm.setText("theme", mapOf("跟随系统" to "system", "浅色" to "light", "深色" to "dark").getValue(it))
        }
        Toggle("动态取色（Android 12+；低版本静态回退）", settings.dynamic) { vm.setFlag("dynamic", it) }
        Toggle("紧凑列表（触控区域不缩小）", settings.compact) { vm.setFlag("compact", it) }
        Toggle("二进制容量单位 MiB / GiB", settings.binaryUnits) { vm.setFlag("binaryUnits", it) }
        HorizontalDivider()
        Text("备份与安全", style = MaterialTheme.typography.titleLarge)
        ChoiceMenu("备份路径：${settings.backupPath}", listOf("/sdcard/download", "/sdcard/Download")) { vm.setText("backupPath", it) }
        Text("选择即为明确配置该大小写路径；实际读取前另行确认。首版不支持任意目录或静默替换。\n目录规则：YU-Flash-Tool/设备标识/时间戳-任务ID/分区名.img")
        Text("✓ 写入前强制备份：固定开启\n✓ SHA-256 完整性校验：不可关闭\n✓ 未知风险、快照及 AVB 状态：拒绝写入", color = MaterialTheme.colorScheme.primary)
        ChoiceMenu("最低电量：${settings.minBattery}%", listOf("50", "60", "70", "80", "90", "100")) { vm.setBattery(it.toInt()) }
        Text("电量至少 50%、温度 0–42°C 是本应用保守策略，不是 Android 官方统一标准。")
        Toggle("写入要求连接电源", settings.requireCharging) { vm.setFlag("requireCharging", it) }
        Toggle("只读任务完成通知", settings.completionNotice) { vm.setFlag("completionNotice", it) }
        Text("真实刷写与成功后自动清理暂存尚未开放；可手动清理，永不自动删除备份。")
        Toggle("显示高风险 / 未知分区", settings.showHighRisk) { vm.setFlag("showHighRisk", it) }
        Toggle("显示更多诊断信息（不会绕过安全检查）", settings.verbose) { vm.setFlag("verbose", it) }
        OutlinedButton(onClick = vm::cleanup, enabled = !busy) { Text("清理所有私有暂存镜像（保留备份）") }
        WarningCard("首版未启用真实分区写入。无专家绕过、批量刷写、任意 Shell 或后台自动刷写。")
    }
}
@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = androidx.compose.ui.unit.Dp(56f)), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChange)
    }
}
@Composable
internal fun AboutScreen() {
    val context = LocalContext.current
    var showLicense by remember { mutableStateOf(false) }
    val licenseText = remember { context.assets.open("GPL-3.0.txt").bufferedReader().use { it.readText() } }
    if (showLicense) AlertDialog(onDismissRequest = { showLicense = false },
        title = { Text("GNU GPL version 3") },
        text = { Text(licenseText, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showLicense = false }) { Text("关闭") } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Text("YU-Flash-Tool", style = MaterialTheme.typography.headlineLarge)
        Text("开发者：昱yu\nQQ：3895958954", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("开发者 QQ", "3895958954"))
        }) { Text("复制 QQ 3895958954") }
        Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n构建类型 ${BuildConfig.BUILD_TYPE}\nAndroid API 26–35 设计目标；尚未真机验证")
        Text("用途：本地分区发现、镜像检查与受限备份。Root 仅用于主动授权后的设备探测、读取和工具能力检测。Root 不等于 Bootloader 解锁。")
        WarningCard("错误镜像或错误分区可能导致设备无法启动、数据丢失或变砖。备份不代表一定能够恢复；命令成功或哈希一致不代表设备能正常启动。")
        Text("隐私：核心功能离线工作，无网络权限、广告或分析 SDK。镜像留在本机；暂存位于私有目录，备份位于下载目录，可能被其他应用或云同步访问。卸载会清除私有任务记录及暂存，不会清除下载目录备份。")
        Text("兼容边界：仅在实际 Toybox 工具自检通过时检测和备份。拒绝 userdata、已挂载/映射对象。无合格设备配置，真实写入硬件适配器关闭。非 A/B、动态分区、Virtual A/B、AVB 和回滚保护未提供完整适配。")
        Text("Copyright (C) 昱yu · GPL-3.0-only。允许按 GPL v3 复制、修改及再分发；本程序不提供任何担保。\n源码：https://github.com/guo20120523/YU-Flash-Tool")
        OutlinedButton(onClick = { showLicense = true }) { Text("查看 GPL v3 许可证全文") }
        Text("开源组件：AndroidX / Compose / Material3 / Lifecycle / Navigation / DataStore / WindowManager（Apache-2.0），Kotlin 与 kotlinx.coroutines（Apache-2.0）。构建侧 JUnit 4（EPL-1.0）。依赖与许可证类型见工程 THIRD_PARTY_NOTICES.md；该声明不等于完整上游许可证，发布前需补齐。反馈请使用上述源码仓库 Issues。")
    }
}
