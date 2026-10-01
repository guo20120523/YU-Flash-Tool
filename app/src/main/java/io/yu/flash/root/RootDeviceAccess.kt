package io.yu.flash.root

import android.content.Context
import android.os.Build
import io.yu.flash.core.*
import org.json.JSONObject
import java.io.File

internal class RootDeviceAccess(private val context: Context, private val repository: PartitionRepository) : DeviceAccess {
    private val shell get() = repository.shell
    suspend fun location(requested: String): BackupLocation {
        requireSafe(requested in setOf("/sdcard/download", "/sdcard/Download"), "首版仅支持明确选择的两个下载目录；不静默回退")
        val q = ShellArg.path(requested)
        val result = shell.run("[ -d $q ] && [ -w $q ] && /system/bin/toybox df -Pk $q")
        if (result.code != 0) {
            val alternate = if (requested.endsWith("download")) "/sdcard/Download" else "/sdcard/download"
            throw SafetyException("$requested 不存在或不可写。可在设置中明确选择 $alternate 后重新检查，不会自动替换")
        }
        val fields = result.output.trim().lines().last().trim().split(Regex("\\s+"))
        val available = fields.getOrNull(3)?.toLongOrNull()?.let { Math.multiplyExact(it, 1024L) }
            ?: throw SafetyException("无法解析剩余空间")
        return BackupLocation(requested, available, requested, "Root 检测；实际创建任务目录时再次验证")
    }
    override suspend fun refresh(target: Partition) = repository.inspect(target.alias) to repository.environment()
    override suspend fun backup(target: Partition, location: BackupLocation, taskId: String): Backup {
        SafetyPolicy.backup(target)
        requireSafe(Regex("[a-f0-9-]{36}").matches(taskId), "非法任务 ID")
        val (fresh, _) = refresh(target)
        requireSafe(SafetyPolicy.sameTarget(target, fresh), "备份前目标改变")
        SafetyPolicy.backup(fresh)
        val checked = location(location.path)
        requireSafe(checked.available >= Math.addExact(target.bytes, 64L * 1024 * 1024), "空间不足，保留至少 64 MiB 余量")
        val deviceTag = Build.DEVICE.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "device" }
        val parent = "${checked.path}/YU-Flash-Tool/$deviceTag"
        val directory = "$parent/${System.currentTimeMillis()}-$taskId"
        val partial = "$directory/${ShellArg.name(target.name)}.img.partial"
        val final = partial.removeSuffix(".partial")
        // mkdir (not -p) for task leaf provides no-overwrite for app-created tasks.
        // Shared-storage interference is not fully preventable; metadata is the commit marker.
        shell.run("/system/bin/toybox mkdir -p ${ShellArg.path(parent)} && /system/bin/toybox mkdir ${ShellArg.path(directory)}").checked()
        val result = shell.run("""
            set -e
            [ "${'$'}(/system/bin/toybox readlink -f ${ShellArg.path(target.alias)})" = ${ShellArg.path(target.device)} ]
            [ -b ${ShellArg.path(target.device)} ]
            /system/bin/toybox dd if=${ShellArg.path(target.device)} of=${ShellArg.path(partial)} bs=1048576
            /system/bin/toybox sync
            /system/bin/toybox stat -c %s ${ShellArg.path(partial)}
            /system/bin/toybox sha256sum ${ShellArg.path(partial)}
        """.trimIndent(), 18000)
        val lines = result.checked().lines()
        val length = lines.firstOrNull()?.trim()?.toLongOrNull()
        requireSafe(length == target.bytes, "备份长度不匹配；保留 .partial")
        val hash = lines.last().substringBefore(' ')
        requireSafe(hash.matches(Regex("[a-f0-9]{64}")), "备份 SHA-256 输出无效")
        val (after, _) = refresh(target)
        requireSafe(SafetyPolicy.sameTarget(target, after), "读取期间目标身份变化")
        // Re-hash persisted file; a valid sidecar is the commit marker. No complete .img on failed checks.
        requireSafe(hashFile(partial) == hash, "备份二次校验失败")
        val metadata = JSONObject().put("taskId", taskId).put("partition", target.name).put("slot", target.slot)
            .put("device", target.device).put("identity", target.identity).put("bytes", length).put("sha256", hash)
            .put("fingerprint", Build.FINGERPRINT).put("timestamp", System.currentTimeMillis())
            .put("onlineReadWarning", "在线读取不是文件系统一致性或可恢复保证").put("state", "VERIFIED")
        val local = File(context.filesDir, "metadata-$taskId.json")
        try {
            local.outputStream().use { it.write(metadata.toString(2).toByteArray()); it.fd.sync() }
            shell.run("""
                set -e
                [ ! -e ${ShellArg.path(final)} ]
                /system/bin/toybox cp ${ShellArg.path(local.path)} ${ShellArg.path("$directory/metadata.json.partial")}
                /system/bin/toybox sync
                /system/bin/toybox mv ${ShellArg.path(partial)} ${ShellArg.path(final)}
                /system/bin/toybox mv ${ShellArg.path("$directory/metadata.json.partial")} ${ShellArg.path("$directory/metadata.json")}
                /system/bin/toybox sync
            """.trimIndent()).checked()
        } catch (e: Exception) {
            // Best-effort demotion only: process death or lost root may leave an orphan .img.
            // An image alone is NOT a committed backup.
            runCatching { shell.run("[ ! -f ${ShellArg.path(final)} ] || /system/bin/toybox mv ${ShellArg.path(final)} ${ShellArg.path(partial)}").checked() }
            throw e
        } finally { local.delete() }
        return Backup(final, target.bytes, hash, "$directory/metadata.json")
    }
    private suspend fun hashFile(path: String): String = shell.run("/system/bin/toybox sha256sum ${ShellArg.path(path)}", 18000).checked().substringBefore(' ')
    override suspend fun verifyBackup(backup: Backup) {
        val size = shell.run("/system/bin/toybox stat -c %s ${ShellArg.path(backup.path)}").checked().toLongOrNull()
        requireSafe(size == backup.bytes && hashFile(backup.path) == backup.sha256, "原分区备份复核失败")
        val metadata = JSONObject(shell.run("/system/bin/toybox cat ${ShellArg.path(backup.metadataPath)}").checked())
        requireSafe(metadata.optString("state") == "VERIFIED" && metadata.optLong("bytes", -1) == backup.bytes &&
            metadata.optString("sha256") == backup.sha256, "备份提交元数据缺失或不匹配")
    }
    override suspend fun write(target: Partition, image: ImportedImage) {
        // Deliberate hardware interlock independent of UI/core: requires a future reviewed device adapter.
        // Do not replace with dd without a target-fd-bound identity check and qualified AVB/snapshot evidence.
        throw SafetyException("真实写入适配器未启用：没有经过真机验证的设备配置；未执行任何块设备写入")
    }
    override suspend fun sync() { shell.run("/system/bin/toybox sync").checked() }
    override suspend fun hashRange(target: Partition, bytes: Long): String {
        requireSafe(bytes == target.bytes, "首版硬件读回接口仅支持完整等长分区")
        return hashFile(target.device)
    }
}
