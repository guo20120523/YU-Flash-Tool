package io.yu.flash.root

import android.content.Context
import io.yu.flash.core.*
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Direct dd surrounded by mandatory existing backup protocol and streaming read-only verification. */
internal class DirectDdWriter(private val context: Context, private val repository: PartitionRepository,
    private val device: RootDeviceAccess) : DirectWriter {
    private val shell get() = repository.shell
    override suspend fun backup(target: Partition, path: String, taskId: String): Backup =
        device.backup(target, device.location(path), taskId)
    override suspend fun verifyBackup(backup: Backup) = device.verifyBackup(backup)
    override suspend fun hashImage(image: SelectedImage): ByteDigest = withContext(Dispatchers.IO) {
        // Wrapping local reads makes cancellation observable between bounded chunks.
        val ctx = currentCoroutineContext()
        val digest = object : FileInputStream(image.file) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                ctx.ensureActive()
                return super.read(b, off, len)
            }
            override fun read(): Int { ctx.ensureActive(); return super.read() }
        }.use { ExactRangeDigest.full(it) }
        ByteDigest(digest.bytes, digest.sha256)
    }
    override suspend fun copy(target: Partition, image: SelectedImage): CommandOutcome {
        val result = shell.run(DirectDdCommand.copy(image.file.path, target.device), 18000)
        return CommandOutcome(result.code, result.error)
    }
    override suspend fun sync(): CommandOutcome {
        val result = shell.run(DirectDdCommand.SYNC, 18000)
        return CommandOutcome(result.code, result.error)
    }
    override suspend fun readBack(target: Partition, bytes: Long): ByteDigest {
        requireSafe(bytes >= 0 && Regex("[0-9]+:[0-9]+").matches(target.identity), "读回长度或设备身份无效")
        // Validate the discovered block path using the same non-shell-injectable contract as dd.
        DirectDdCommand.copy("/unused", target.device)
        val token = UUID.randomUUID().toString()
        val command = "CLASSPATH=${ShellArg.installedApk(context.applicationInfo.sourceDir)} /system/bin/app_process / io.yu.flash.root.ReadbackDigest " +
            "${ShellArg.path(target.device)} '${target.identity}' $bytes $token"
        val result = shell.run(command, 18000)
        val output = result.checked()
        val prefix = "YU_READBACK $token ${target.identity} $bytes "
        requireSafe(output.startsWith(prefix), "读回结果协议无效")
        val hash = output.removePrefix(prefix)
        requireSafe(Regex("[a-f0-9]{64}").matches(hash), "读回 SHA-256 无效")
        return ByteDigest(bytes, hash)
    }
}
