package io.yu.flash.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.yu.flash.core.*
import io.yu.flash.storage.AppSettings

internal fun capacity(bytes: Long, binary: Boolean): String {
    if (bytes <= 0) return "未知"
    val unit = if (binary) 1024.0 else 1000.0
    return when {
        bytes >= unit * unit * unit -> "%.2f %s".format(bytes / (unit * unit * unit), if (binary) "GiB" else "GB")
        bytes >= unit * unit -> "%.2f %s".format(bytes / (unit * unit), if (binary) "MiB" else "MB")
        else -> "$bytes B"
    }
}
internal fun ImageKind.label() = when (this) {
    ImageKind.UNKNOWN -> "未知/未识别"; ImageKind.BOOT -> "Android boot 镜像"; ImageKind.VENDOR_BOOT -> "vendor boot 镜像"
    ImageKind.AVB -> "AVB 结构"; else -> name
}
@Composable
internal fun HomeScreen(vm: MainViewModel, home: HomeState, settings: AppSettings, busy: Boolean) {
    var search by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("名称") }
    var slot by rememberSaveable { mutableStateOf("全部") }
    var risk by rememberSaveable { mutableStateOf("全部") }
    var readable by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Partition?>(null) }
    var selectedIdentity by rememberSaveable { mutableStateOf<String?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = home.partitions.firstOrNull { it.identity == selectedIdentity }
        selectedIdentity = null
        if (uri != null) {
            if (target != null) vm.import(target, uri)
            else vm.message.value = "目标状态已丢失，请重新检测分区后选择镜像"
        }
    }
    val horizontal = rememberScrollState()
    val rows = home.partitions.filter { p ->
        p.name.contains(search, true) && (slot == "全部" || (p.slot ?: "无/未知") == slot) &&
            (risk == "全部" || p.risk.name == risk) && (!readable || runCatching { SafetyPolicy.backup(p) }.isSuccess) &&
            (settings.showHighRisk || p.risk == Risk.BOOT_CHAIN)
    }.let { list -> when (sort) { "大小" -> list.sortedByDescending { it.bytes }; "类型" -> list.sortedBy { it.kind.name }; else -> list.sortedBy { it.name } } }
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.medium)) {
        Text("分区", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = Spacing.small))
        Text("${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\nRoot：${home.root} · 当前槽位：${home.environment?.slot ?: "未知"}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Button(onClick = vm::refresh, enabled = !home.loading && !busy) { Text(if (home.root == RootState.NOT_REQUESTED) "了解用途并申请 Root" else "重新检测 / 刷新") }
        }
        if (home.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在只读检查 Root、工具和分区，请等待授权…") }
        home.error?.let { WarningCard(it) }
        if (settings.verbose) Text(home.diagnostics, style = MaterialTheme.typography.bodySmall)
        if (home.root != RootState.READY) {
            Text(home.diagnostics, Modifier.padding(vertical = Spacing.medium))
            Text("授权前不执行 su。若授权超时请检查 Root 管理器；无 Root 时仍可查看设置与历史任务。")
        } else {
            OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("搜索分区名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                ChoiceMenu("排序：$sort", listOf("名称", "大小", "类型")) { sort = it }
                ChoiceMenu("槽位：$slot", listOf("全部", "a", "b", "无/未知")) { slot = it }
                ChoiceMenu("风险：$risk", listOf("全部") + Risk.entries.map { it.name }) { risk = it }
                FilterChip(selected = readable, onClick = { readable = !readable }, label = { Text("仅可读取") })
            }
            if (rows.isEmpty()) Text(if (home.partitions.isEmpty()) "未发现有效分区，请展开诊断。" else "没有匹配筛选条件的分区。")
            // ONE scroll container wraps both header and list, preserving five-column alignment.
            Column(Modifier.weight(1f).horizontalScroll(horizontal).width(840.dp)) {
                TableRow { column -> Text(listOf("分区名称", "分区大小", "文件格式", "读取", "写入")[column], style = MaterialTheme.typography.labelLarge) }
                HorizontalDivider()
                LazyColumn {
                    items(rows, key = { it.identity }) { partition ->
                        TableRow(compact = settings.compact) { column -> when (column) {
                            0 -> TextButton(onClick = { detail = partition }) { Text(partition.name) }
                            1 -> TextButton(onClick = { detail = partition }) { Text(capacity(partition.bytes, settings.binaryUnits)) }
                            2 -> Text(partition.kind.label(), style = MaterialTheme.typography.bodyMedium)
                            3 -> OutlinedButton(onClick = { vm.prepareBackup(partition) }, enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = "读取并备份 ${partition.name}" }) { Text("读取") }
                            4 -> TextButton(onClick = { selectedIdentity = partition.identity; chooser.launch(arrayOf("*/*")) }, enabled = !busy,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.semantics { contentDescription = "为 ${partition.name} 选择镜像，仅检查，真实写入受阻" }) { Text("写入检查") }
                        } }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            TextButton(onClick = { vm.message.value = home.diagnostics }) { Text("查看检测诊断 · 写入受安全限制") }
        }
    }
    detail?.let { p -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(p.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text("容量：${p.bytes} 字节\n类型：${p.kind.label()}\n块设备：${p.device}\n设备号：${p.identity}\n槽位：${p.slot ?: "未知/非 A/B"}\n风险：${p.risk}\n挂载：${p.mounted}\n映射/持有者：${p.mapped}")
            Text("路径来源 / 重复别名：\n${p.aliases.joinToString("\n")}")
            Text("读取策略：${runCatching { SafetyPolicy.backup(p); "允许进入确认，执行前再次检查" }.exceptionOrNull()?.message ?: "允许进入确认"}")
            Text("写入：无已验证设备适配配置。型号、容量和名称均不能单独证明兼容。")
        }
    }, confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }) }
}
@Composable
private fun TableRow(compact: Boolean = false, content: @Composable (Int) -> Unit) {
    Row(Modifier.heightIn(min = if (compact) 56.dp else 72.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        listOf(200, 160, 220, 120, 140).forEachIndexed { i, width -> Box(Modifier.width(width.dp).padding(horizontal = Spacing.small)) { content(i) } }
    }
}
@Composable
internal fun ChoiceMenu(label: String, options: List<String>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { options.forEach { option ->
            DropdownMenuItem(text = { Text(option) }, onClick = { onChoose(option); expanded = false })
        } }
    }
}
