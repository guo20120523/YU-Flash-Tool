package io.yu.flash.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import androidx.window.layout.FoldingFeature
import io.yu.flash.core.*

@Composable
internal fun YuApp(vm: MainViewModel, fold: FoldingFeature?) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val operation by vm.operation.collectAsStateWithLifecycle()
    val prompt by vm.backupPrompt.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val writePrompt by vm.writePrompt.collectAsStateWithLifecycle()
    val checkingWrite by vm.checkingWrite.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.route ?: "主页"
    val snackbar = remember { SnackbarHostState() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    val pages = listOf("主页", "任务", "设置", "关于")
    val pageSymbols = listOf(Symbol.Home, Symbol.Tasks, Symbol.Settings, Symbol.Info)
    val density = LocalDensity.current
    // Use a single usable pane for separating folds; never put an action underneath the hinge.
    val foldModifier = if (fold == null) Modifier else with(density) {
        if (fold.orientation == FoldingFeature.Orientation.VERTICAL) Modifier.width(fold.bounds.left.toDp().coerceAtLeast(1.dp))
        else Modifier.height(fold.bounds.top.toDp().coerceAtLeast(1.dp))
    }
    BoxWithConstraints(foldModifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Scaffold(
            topBar = { Surface(tonalElevation = 2.dp) {
                Column(Modifier.statusBarsPadding().fillMaxWidth().padding(Spacing.medium)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        SymbolIcon(Symbol.Flash, tint = MaterialTheme.colorScheme.primary)
                        Text("YU-Flash-Tool", style = MaterialTheme.typography.titleLarge)
                    }
                    Text("本地分区工具 · 安全预览版", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } },
            bottomBar = { if (!wide) NavigationBar { pages.forEachIndexed { index, page ->
                NavigationBarItem(selected = route == page, onClick = { nav.navigate(page) { launchSingleTop = true; popUpTo(nav.graph.startDestinationId) { saveState = true }; restoreState = true } },
                    icon = { SymbolIcon(pageSymbols[index], selected = route == page) }, label = { Text(page) })
            } } },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { insets ->
            Row(Modifier.padding(insets).imePadding().fillMaxSize()) {
                if (wide) NavigationRail { pages.forEachIndexed { index, page ->
                    NavigationRailItem(selected = route == page, onClick = { nav.navigate(page) { launchSingleTop = true } },
                        icon = { SymbolIcon(pageSymbols[index], selected = route == page) }, label = { Text(page) })
                } }
                Column(Modifier.weight(1f).widthIn(max = 1040.dp).fillMaxHeight()) {
                    if (operation.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("${operation.phase} · 已处理 ${operation.bytes} 字节", Modifier.padding(Spacing.small))
                    }
                    operation.error?.let { WarningCard(it) }
                    NavHost(navController = nav, startDestination = "主页", modifier = Modifier.weight(1f)) {
                        composable("主页") { HomeScreen(vm, home, settings, operation.busy) }
                        composable("任务") { TasksScreen(vm) }
                        composable("设置") { SettingsScreen(vm, settings, operation.busy) }
                        composable("关于") { AboutScreen() }
                    }
                }
            }
        }
    }
    prompt?.let { (partition, location) ->
        AlertDialog(onDismissRequest = { vm.backupPrompt.value = null }, icon = { SymbolIcon(Symbol.Download) }, title = { Text("读取前确认 · ${partition.name}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text("设备：${partition.device}\n容量：${partition.bytes} 字节\n保存目录：${location.path}/YU-Flash-Tool/设备/时间-任务ID/\n可用空间：${location.available} 字节")
                WarningCard("在线块级读取不保证文件系统一致性。备份不保证可以恢复。下载目录不是私有存储，备份可能含身份信息、密钥或个人数据；请避免分享及意外云同步。")
                Text("使用独立目录避免覆盖；通常保留 .partial，提交中断也可能留下未完成的 .img，须以验证记录及配套元数据为准。任务使用前台通知，系统仍可能终止进程。")
            } }, confirmButton = { Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.startBackup()
            }) { ButtonSymbol(Symbol.Download); Text("理解风险并备份") } }, dismissButton = { TextButton(onClick = { vm.backupPrompt.value = null }) { Text("取消") } })
    }
    operation.imported?.takeIf { writePrompt == null }?.let { image ->
        AlertDialog(onDismissRequest = { if (!checkingWrite) vm.dismissImport() }, icon = { SymbolIcon(Symbol.Description) }, title = { Text("镜像检查 · 尚未写入") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text("目标：${operation.importTarget?.name}\n槽位：${operation.importTarget?.slot ?: "未知"}\n设备：${operation.importTarget?.device}\n容量：${operation.importTarget?.bytes} 字节")
                Text("文档名：${image.displayName}\n实际长度：${image.bytes} 字节\n类型：${image.kind}\nSHA-256：${image.sha256}")
                Text("强制备份目录：${settings.backupPath}")
                WarningCard("仅支持未挂载、非动态/快照的普通物理分区与等容量 raw 镜像。AVB、回滚和设备兼容性未验证；通过检查也可能无法启动、数据丢失或变砖。")
                Text("规范命名不会改变内容或兼容性。用户原始文档未修改；可在设置中清理私有暂存。")
            }
        }, confirmButton = { Button(onClick = vm::prepareWrite, enabled = !operation.busy && !checkingWrite) { ButtonSymbol(Symbol.Check); Text(if (checkingWrite) "检查中…" else "检查写入条件") } },
            dismissButton = { TextButton(onClick = vm::dismissImport, enabled = !checkingWrite) { Text("关闭（不写入）") } })
    }
    writePrompt?.let { request ->
        var typedName by remember(request) { mutableStateOf("") }
        var accepted by remember(request) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = vm::cancelWrite, icon = { SymbolIcon(Symbol.Warning, tint = MaterialTheme.colorScheme.error) }, title = { Text("最终确认 · 真实覆盖分区") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text("目标：${request.target.name}\n槽位：${request.target.slot ?: "非 A/B"}\n块设备：${request.target.device}\n身份：${request.target.identity}\n容量：${request.target.bytes} 字节")
                Text("镜像：${request.image.kind} · ${request.image.bytes} 字节\nSHA-256：${request.image.sha256}\n强制备份目录：${request.location.path}\n剩余空间：${request.location.available} 字节")
                WarningCard("写入后无法安全取消。失败可能留下部分改写的分区；不会自动重试、回滚、切槽、重启或关闭 AVB。备份和读回一致均不保证可启动或可恢复。")
                Row {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text("我接受 AVB、回滚索引及设备兼容性未验证的风险，已准备外部救援方式。", Modifier.padding(top = 12.dp))
                }
                OutlinedTextField(value = typedName, onValueChange = { typedName = it }, singleLine = true,
                    label = { Text("完整输入 ${request.target.name}（区分大小写）") }, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = { Button(enabled = accepted && typedName == request.target.name && !operation.busy,
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.confirmWrite(typedName, accepted)
            }) { ButtonSymbol(Symbol.Upload); Text("备份并真实写入") } }, dismissButton = { TextButton(onClick = vm::cancelWrite) { Text("取消，不写入") } })
    }
}

@Composable
internal fun WarningCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(Spacing.small)) {
        Row(Modifier.padding(Spacing.medium), horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            SymbolIcon(Symbol.Warning, contentDescription = "警告", tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
