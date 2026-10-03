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
        OutlinedButton(onClick = { preview = true }, enabled = events.isNotEmpty()) { ButtonSymbol(Symbol.Upload); Text("导出脱敏记录") }
        if (events.isEmpty()) Text("暂无任务。备份、导入检查和受限事务的记录将在此显示。", Modifier.padding(vertical = Spacing.large))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            items(events) { event -> OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        val symbol = when (event.stage) {
                            io.yu.flash.core.Stage.SUCCESS -> Symbol.Check
                            io.yu.flash.core.Stage.FAILED -> Symbol.Error
                            io.yu.flash.core.Stage.INTERRUPTED -> Symbol.Cancel
                            else -> Symbol.Tasks
                        }
                        SymbolIcon(symbol, tint = if (event.stage == io.yu.flash.core.Stage.FAILED || event.stage == io.yu.flash.core.Stage.INTERRUPTED)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${event.partition} · ${event.stage}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    }
                    Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.time))}\n任务 ${event.taskId}\n槽位 ${event.slot ?: "未知"} · ${event.bytes} 字节 · exit ${event.exitCode ?: "n/a"}", style = MaterialTheme.typography.labelMedium)
                    Text(event.summary, style = MaterialTheme.typography.bodyMedium)
                }
            } }
        }
    }
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text("导出前隐私说明") }, text = {
        Text("导出内容：时间、任务ID、分区名、槽位、阶段、字节数、退出码。不会导出设备指纹、文件路径、镜像内容或错误原文。分区名及时间仍可能透露使用情况，请谨慎分享。")
    }, confirmButton = { Button(onClick = { preview = false; export.launch("YU-Flash-Tool-redacted-log.txt") }) { ButtonSymbol(Symbol.Folder); Text("选择保存位置") } },
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
        listOf("写入前强制备份：固定开启", "SHA-256 完整性校验：不可关闭", "动态/快照及未知目标：拒绝写入").forEach { guarantee ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                SymbolIcon(Symbol.Check, tint = MaterialTheme.colorScheme.primary)
                Text(guarantee, Modifier.weight(1f))
            }
        }
        WarningCard("AVB/回滚/机型兼容性未验证：必须显式确认风险")
        ChoiceMenu("最低电量：${settings.minBattery}%", listOf("50", "60", "70", "80", "90", "100")) { vm.setBattery(it.toInt()) }
        Text("电量至少 50%、温度 0–42°C 是本应用保守策略，不是 Android 官方统一标准。")
        Toggle("写入要求连接电源", settings.requireCharging) { vm.setFlag("requireCharging", it) }
        Toggle("任务完成通知", settings.completionNotice) { vm.setFlag("completionNotice", it) }
        Text("成功后不自动清理暂存；无任务且无写入互锁时可手动清理，永不自动删除备份。")
        Toggle("显示高风险 / 未知分区", settings.showHighRisk) { vm.setFlag("showHighRisk", it) }
        Toggle("显示更多诊断信息（不会绕过安全检查）", settings.verbose) { vm.setFlag("verbose", it) }
        OutlinedButton(onClick = vm::cleanup, enabled = !busy) { ButtonSymbol(Symbol.Delete); Text("清理所有私有暂存镜像（保留备份）") }
        WarningCard("真实写入仅限策略允许的普通物理分区与完整等长 raw。无专家绕过、批量刷写、任意 Shell 或后台自动刷写。进程状态不明时设备操作及清理被互锁禁止。")
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
    var licenseAsset by remember { mutableStateOf<String?>(null) }
    licenseAsset?.let { asset ->
        val licenseText = remember(asset) { context.assets.open(asset).bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { licenseAsset = null },
            title = { Text(if (asset == "GPL-3.0.txt") "GNU GPL version 3" else "Material Symbols · Apache 2.0") },
            text = { Text(licenseText, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { licenseAsset = null }) { Text("关闭") } })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
            SymbolIcon(Symbol.Flash, modifier = Modifier.padding(Spacing.medium).size(androidx.compose.ui.unit.Dp(48f)),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("YU-Flash-Tool", style = MaterialTheme.typography.headlineLarge)
        Text("开发者：昱yu\nQQ：3895958954", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("开发者 QQ", "3895958954"))
        }) { ButtonSymbol(Symbol.Copy); Text("复制 QQ 3895958954") }
        Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n构建类型 ${BuildConfig.BUILD_TYPE}\nAndroid API 26–35 设计目标；尚未真机验证")
        Text("用途：本地分区发现、镜像检查、备份与受限 raw 写入。Root 用于主动授权后的设备访问，实际刷写需另外确认完整分区名及兼容性风险。Root 不等于 Bootloader 解锁。")
        WarningCard("错误镜像或错误分区可能导致设备无法启动、数据丢失或变砖。备份不代表一定能够恢复；命令成功或哈希一致不代表设备能正常启动。")
        Text("隐私：核心功能离线工作，无网络权限、广告或分析 SDK。镜像留在本机；暂存位于私有目录，备份位于下载目录，可能被其他应用或云同步访问。卸载会清除私有任务记录及暂存，不会清除下载目录备份。")
        Text("兼容边界：需 Toybox 与 Root 写入组件普通文件自检通过。写入拒绝 userdata、整盘、RPMB、已挂载/映射/动态/Virtual A/B 对象；A/B 仅允许明确非当前槽。AVB、回滚和机型兼容性未验证。真实块设备写入尚未真机验收。")
        Text("Copyright (C) 昱yu · GPL-3.0-only。允许按 GPL v3 复制、修改及再分发；本程序不提供任何担保。\n源码：https://github.com/guo20120523/YU-Flash-Tool")
        OutlinedButton(onClick = { licenseAsset = "GPL-3.0.txt" }) { ButtonSymbol(Symbol.Description); Text("查看 GPL v3 许可证全文") }
        Text("图标：Google Material Symbols Rounded（Apache-2.0），以本地矢量资源离线提供；不是旧版 Material Icons 或字符替代图标。")
        OutlinedButton(onClick = { licenseAsset = "Material-Symbols-NOTICE.txt" }) { ButtonSymbol(Symbol.Info); Text("查看图标来源与许可证") }
        Text("开源组件：AndroidX / Compose / Material3 / Lifecycle / Navigation / DataStore / WindowManager（Apache-2.0），Kotlin 与 kotlinx.coroutines（Apache-2.0）。构建侧 JUnit 4（EPL-1.0）。依赖与许可证类型见工程 THIRD_PARTY_NOTICES.md；该声明不等于完整上游许可证，发布前需补齐。反馈请使用上述源码仓库 Issues。")
    }
}
