package io.yu.flash.root

import android.content.Context
import android.os.Build
import io.yu.flash.core.*
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.launch

internal class RootDeviceAccess(private val context: Context, private val repository: PartitionRepository) : DeviceAccess {
    private val shell get() = repository.shell
    val interlock = WriteInterlock(context.filesDir)
    suspend fun location(requested: String): BackupLocation {
        interlock.check()
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
    override suspend fun refresh(target: Partition): Pair<Partition, Environment> {
        interlock.check()
        return repository.inspect(target.alias) to repository.environment()
    }
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
        interlock.check()
        val size = shell.run("/system/bin/toybox stat -c %s ${ShellArg.path(backup.path)}").checked().toLongOrNull()
        requireSafe(size == backup.bytes && hashFile(backup.path) == backup.sha256, "原分区备份复核失败")
        val metadata = JSONObject(shell.run("/system/bin/toybox cat ${ShellArg.path(backup.metadataPath)}").checked())
        requireSafe(metadata.optString("state") == "VERIFIED" && metadata.optLong("bytes", -1) == backup.bytes &&
            metadata.optString("sha256") == backup.sha256, "备份提交元数据缺失或不匹配")
    }
    override suspend fun writeAndVerify(target: Partition, image: ImportedImage, backup: Backup,
        stage: suspend (Stage, Long) -> Unit): WriteReceipt = kotlinx.coroutines.coroutineScope {
        interlock.check()
        val token = java.util.UUID.randomUUID().toString()
        val request = File(context.filesDir, "write-$token.json")
        val status = File(context.filesDir, "write-$token.status")
        val payload = JSONObject().put("device", target.device).put("alias", target.alias).put("identity", target.identity)
            .put("name", target.name).put("bytes", image.bytes).put("image", image.file.canonicalPath)
            .put("imageHash", image.sha256).put("backupHash", backup.sha256)
        requireSafe(image.file.canonicalPath.startsWith(File(context.filesDir, "staging").canonicalPath + "/"), "镜像不在私有暂存内")
        requireSafe(image.bytes == target.bytes && backup.bytes == target.bytes, "写入/备份长度必须与分区一致")
        verifyBackup(backup)
        request.outputStream().use { it.write(payload.toString().toByteArray()); it.fd.sync() }
        status.outputStream().use { it.fd.sync() }
        // Persist before launching su. Timeout/cancellation/failure NEVER clears the marker.
        interlock.arm(token, target.name)
        val command = "CLASSPATH=${ShellArg.installedApk(context.applicationInfo.sourceDir)} /system/bin/app_process / io.yu.flash.root.RootWriter " +
            listOf(request.path, interlock.marker.path).joinToString(" ") { ShellArg.path(it) } + " $token ${ShellArg.path(status.path)}"
        var consumed = 0
        suspend fun drain() {
            requireSafe(status.length() <= 1024 * 1024, "写入进度文件过大")
            val text = status.readText()
            val end = text.lastIndexOf('\n')
            val lines = if (end < 0) emptyList() else text.substring(0, end).split('\n')
            for (line in lines.drop(consumed)) {
                val parts = line.split(' ')
                requireSafe(parts.size == 2, "写入进度协议字段无效")
                val step = Stage.valueOf(parts[0])
                val bytes = parts[1].toLongOrNull()
                requireSafe(bytes != null && bytes in 0..image.bytes, "写入进度字节数无效")
                requireSafe(step in setOf(Stage.RECHECK, Stage.WRITING, Stage.SYNCING, Stage.READBACK), "写入进度协议无效")
                stage(step, bytes!!)
                consumed++
            }
        }
        val monitor = launch {
            while (true) { kotlinx.coroutines.delay(500); drain() }
        }
        try {
            val result = shell.run(command, 18000)
            monitor.cancel(); monitor.join(); drain()
            val expected = "YU_VERIFIED $token ${target.identity} ${image.bytes} ${image.sha256}"
            requireSafe(result.code == 0 && result.output.trim() == expected, "写入未验证 (exit=${result.code})：${result.error.take(240)}；分区可能已部分改写。保留备份，禁止自动重试。")
            interlock.verified(token)
            request.delete(); status.delete()
            WriteReceipt(target.identity, image.bytes, image.sha256)
        } finally { monitor.cancel() }
    }
}
