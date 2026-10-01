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
    val nav = rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.route ?: "主页"
    val snackbar = remember { SnackbarHostState() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    val pages = listOf("主页", "任务", "设置", "关于")
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
                    Text("YU-Flash-Tool", style = MaterialTheme.typography.titleLarge)
                    Text("本地分区工具 · 安全预览版", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } },
            bottomBar = { if (!wide) NavigationBar { pages.forEachIndexed { index, page ->
                NavigationBarItem(selected = route == page, onClick = { nav.navigate(page) { launchSingleTop = true; popUpTo(nav.graph.startDestinationId) { saveState = true }; restoreState = true } },
                    icon = { Text(listOf("▤", "◷", "⚙", "ⓘ")[index]) }, label = { Text(page) })
            } } },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { insets ->
            Row(Modifier.padding(insets).imePadding().fillMaxSize()) {
                if (wide) NavigationRail { pages.forEachIndexed { index, page ->
                    NavigationRailItem(selected = route == page, onClick = { nav.navigate(page) { launchSingleTop = true } },
                        icon = { Text(listOf("▤", "◷", "⚙", "ⓘ")[index]) }, label = { Text(page) })
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
        AlertDialog(onDismissRequest = { vm.backupPrompt.value = null }, title = { Text("读取前确认 · ${partition.name}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text("设备：${partition.device}\n容量：${partition.bytes} 字节\n保存目录：${location.path}/YU-Flash-Tool/设备/时间-任务ID/\n可用空间：${location.available} 字节")
                Text("⚠ 在线块级读取不保证文件系统一致性。备份不保证可以恢复。下载目录不是私有存储，备份可能含身份信息、密钥或个人数据；请避免分享及意外云同步。", color = MaterialTheme.colorScheme.error)
                Text("使用独立目录避免覆盖；通常保留 .partial，提交中断也可能留下未完成的 .img，须以验证记录及配套元数据为准。任务使用前台通知，系统仍可能终止进程。")
            } }, confirmButton = { Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.startBackup()
            }) { Text("理解风险并备份") } }, dismissButton = { TextButton(onClick = { vm.backupPrompt.value = null }) { Text("取消") } })
    }
    operation.imported?.let { image ->
        AlertDialog(onDismissRequest = vm::dismissImport, title = { Text("镜像检查 · 不放行刷写") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text("目标：${operation.importTarget?.name}\n槽位：${operation.importTarget?.slot ?: "未知"}\n设备：${operation.importTarget?.device}\n容量：${operation.importTarget?.bytes} 字节")
                Text("文档名：${image.displayName}\n实际长度：${image.bytes} 字节\n类型：${image.kind}\nSHA-256：${image.sha256}")
                Text("强制备份目录：${settings.backupPath}")
                WarningCard("没有经真机验证的适配配置。OTA 快照、AVB 与回滚兼容性未知，拒绝进入写入确认。错误镜像或错误分区可能导致无法启动、数据丢失或变砖。")
                Text("规范命名不会改变内容或兼容性。用户原始文档未修改；可在设置中清理私有暂存。")
            }
        }, confirmButton = { TextButton(onClick = vm::dismissImport) { Text("关闭（不写入）") } })
    }
}

@Composable
internal fun WarningCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(Spacing.small)) {
        Text("⚠ $text", Modifier.padding(Spacing.medium), color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium)
    }
}
