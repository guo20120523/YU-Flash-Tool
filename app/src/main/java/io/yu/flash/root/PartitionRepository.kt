package io.yu.flash.root

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import io.yu.flash.core.*
import java.io.File

internal data class Discovery(val partitions: List<Partition>, val environment: Environment, val diagnostics: String)
internal class PartitionRepository(private val context: Context, val shell: RootExecutor) {
    var toolsReady = false
        private set
    suspend fun authorize(): RootState {
        val result = shell.run("/system/bin/id -u", 30)
        if (result.code != 0) throw RootFailure(RootState.DENIED, "su 未授予权限 (exit=${result.code})；可能被管理器拒绝或不可用")
        if (result.output.trim() != "0") throw RootFailure(RootState.DENIED, "su 会话不是 uid 0")
        probeTools()
        return RootState.READY
    }
    private suspend fun probeTools() {
        val probe = File(context.cacheDir, "capability-${java.util.UUID.randomUUID()}")
        val q = ShellArg.path(probe.path)
        // Every output is an app-owned ordinary temporary file, never a block device.
        try {
            val result = shell.run("""
                set -e
                /system/bin/toybox --version
                /system/bin/toybox dd if=/dev/zero of=$q bs=4096 count=1
                /system/bin/toybox stat -c %s $q
                /system/bin/toybox sha256sum $q
                /system/bin/toybox readlink -f $q
                /system/bin/toybox df -Pk ${ShellArg.path(context.cacheDir.path)}
                /system/bin/toybox od -An -tx1 -N16 $q
                /system/bin/toybox sync
            """.trimIndent())
            result.checked()
            requireSafe(probe.length() == 4096L && result.output.lines().any { it.trim() == "4096" } &&
                result.output.contains(ImageInspector.sha256(probe)), "Toybox 实际行为校验失败")
            toolsReady = true
        } finally { probe.delete() }
    }
    suspend fun environment(): Environment {
        val result = shell.run("""
            /system/bin/getprop ro.boot.slot_suffix
            /system/bin/getprop ro.boot.flash.locked
            /system/bin/getprop ro.boot.vbmeta.device_state
            /system/bin/getprop ro.virtual_ab.enabled
            /system/bin/getprop ro.boot.dynamic_partitions
            /system/bin/getprop ro.boot.dynamic_partitions_retrofit
        """.trimIndent())
        result.checked()
        // Preserve an empty first property on non-A/B devices: trim() shifts line positions.
        val props = result.output.trimEnd('\r', '\n').lines().map { it.trim() }
        val suffix = props.getOrNull(0)?.trim()?.removePrefix("_")?.takeIf { it in listOf("a", "b") }
        val unlocked = when {
            props.getOrNull(1) == "0" && props.getOrNull(2) == "unlocked" -> Truth.YES
            props.getOrNull(1) == "1" || props.getOrNull(2) == "locked" -> Truth.NO
            else -> Truth.UNKNOWN
        }
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        // Scope exclusion, not proof an OTA/AVB image is compatible. Virtual A/B is refused wholesale.
        val virtual = props.getOrNull(3).orEmpty()
        val legacy = (virtual == "false" || (virtual.isEmpty() && Build.VERSION.SDK_INT < 29)) &&
            props.getOrNull(4) != "true" && props.getOrNull(5) != "true"
        return Environment(Build.FINGERPRINT, suffix, unlocked, if (legacy) Truth.YES else Truth.UNKNOWN, Truth.UNKNOWN,
            if (level >= 0 && scale > 0) level * 100 / scale else null,
            temperature?.takeIf { it != Int.MIN_VALUE }?.div(10.0), plugged?.takeIf { it >= 0 }?.let { it != 0 }, toolsReady)
    }
    suspend fun discover(): Discovery {
        requireSafe(toolsReady, "请先主动申请 Root 并完成工具检测")
        val paths = shell.run("""
            for d in /dev/block/by-name /dev/block/bootdevice/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name /dev/block/platform/*/*/*/by-name; do
              if [ -d "${'$'}d" ]; then
                for p in "${'$'}d"/*; do [ -L "${'$'}p" ] && printf '%s\n' "${'$'}p"; done
              fi
            done
            exit 0
        """.trimIndent()).checked().lines().filter { it.isNotBlank() }.distinct()
        val diagnostics = mutableListOf<String>()
        val partitions = paths.mapNotNull { path ->
            try { inspect(path) } catch (e: Exception) { diagnostics += "${path.substringAfterLast('/')}: ${e.message}"; null }
        }.groupBy { it.identity }.values.map { entries -> entries.first().copy(aliases = entries.flatMap { it.aliases }.distinct()) }
        val mapped = shell.run("for d in /sys/class/block/dm-*/dm/name; do [ ! -f \"${'$'}d\" ] || /system/bin/toybox cat \"${'$'}d\"; done; exit 0").checked()
        if (mapped.isNotBlank()) diagnostics += "发现独立 device-mapper 设备（不作为可写 by-name 对象）：\n${mapped.take(2048)}"
        if (partitions.isEmpty()) diagnostics += "没有可验证的 by-name 块设备；已限制探测范围为 /dev/block 的常见目录，未扫描整个文件系统"
        diagnostics += "写入在警告确认后先强制完整备份并验证，再执行 dd、sync 和写入范围读回 SHA-256 检测。备份条件仍会拦截；不验证镜像兼容性。RPMB 在内容探测前排除。"
        return Discovery(partitions, environment(), diagnostics.joinToString("\n"))
    }
    suspend fun inspect(alias: String): Partition {
        val name = ShellArg.name(alias.substringAfterLast('/'))
        requireSafe(alias.startsWith("/dev/block/") && alias.contains("/by-name/"), "来源不在 by-name 范围")
        val device = shell.run("/system/bin/toybox readlink -f ${ShellArg.path(alias)}").checked()
        requireSafe(device.startsWith("/dev/block/"), "符号链接越出 /dev/block")
        val block = ShellArg.name(device.substringAfterLast('/'))
        requireSafe(!block.contains("rpmb", true) && !name.contains("rpmb", true), "RPMB 是特殊协议设备，已跳过内容探测")
        val q = ShellArg.path(device)
        val topology = shell.run("[ -f /sys/class/block/$block/partition ] && /system/bin/toybox readlink -f /sys/class/block/$block; /system/bin/toybox cat /sys/class/block/$block/ro").checked().lines()
        val physical = topology.firstOrNull()?.let { it.startsWith("/sys/devices/") && !it.contains("/virtual/") } == true
        val writable = when (topology.lastOrNull()) { "0" -> Truth.YES; "1" -> Truth.NO; else -> Truth.UNKNOWN }
        val info = shell.run("""
            set -e
            [ -b $q ]
            /system/bin/toybox cat /sys/class/block/$block/dev
            /system/bin/toybox cat /sys/class/block/$block/size
            /system/bin/toybox cat /proc/1/mountinfo
        """.trimIndent()).checked().lines()
        val identity = info.firstOrNull()?.takeIf { Regex("[0-9]+:[0-9]+").matches(it) } ?: throw SafetyException("无法读取设备号")
        val sectors = info.getOrNull(1)?.toLongOrNull() ?: throw SafetyException("无法读取容量")
        val bytes = Math.multiplyExact(sectors, 512L)
        requireSafe(bytes > 0, "容量为零")
        val mounts = info.drop(2)
        val mounted = if (mounts.isEmpty()) Truth.UNKNOWN else if (mounts.any { it.split(' ').getOrNull(2) == identity }) Truth.YES else Truth.NO
        val graph = shell.run("""
            if [ -d /sys/class/block/$block/dm ]; then echo mapped; fi
            [ -d /sys/class/block/$block/holders ] || echo unknown
            for p in /sys/class/block/$block/holders/* /sys/class/block/$block/slaves/*; do [ ! -e "${'$'}p" ] || echo holder; done
            exit 0
        """.trimIndent()).checked()
        val hex = shell.run("/system/bin/toybox od -v -An -tx1 -N4096 $q").checked()
        val header = hex.split(Regex("\\s+")).filter { it.matches(Regex("[0-9a-fA-F]{2}")) }.map { it.toInt(16).toByte() }.toByteArray()
        val base = name.removeSuffix("_a").removeSuffix("_b")
        val kind = ImageInspector.detect(header)
        val risk = when {
            base in setOf("userdata", "metadata", "cache") -> Risk.DATA
            graph.isNotEmpty() || base == "super" -> Risk.DYNAMIC
            base in setOf("boot", "init_boot", "vendor_boot", "recovery") && kind in setOf(ImageKind.BOOT, ImageKind.VENDOR_BOOT) -> Risk.BOOT_CHAIN
            base in setOf("system", "vendor", "product", "odm", "system_ext", "vendor_dlkm", "odm_dlkm", "system_dlkm") && kind in setOf(ImageKind.EXT4, ImageKind.F2FS, ImageKind.EROFS) -> Risk.FILESYSTEM
            base in setOf("modem", "persist", "efs", "xbl", "abl", "preloader", "vbmeta", "frp") -> Risk.CRITICAL
            else -> Risk.UNKNOWN
        }
        return Partition(name, alias, device, identity, bytes, kind,
            name.takeLast(2).takeIf { it in listOf("_a", "_b") }?.removePrefix("_"), risk, mounted,
            if (graph.isNotEmpty()) Truth.YES else Truth.NO, physical = if (physical) Truth.YES else Truth.NO, writable = writable)
    }
}
