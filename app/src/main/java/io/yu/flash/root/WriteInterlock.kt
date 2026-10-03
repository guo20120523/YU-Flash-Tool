package io.yu.flash.root

import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import io.yu.flash.core.requireSafe
import org.json.JSONObject
import java.io.File

/** Persist launch intent; never delete an uncertainty record on a failing completion path. */
internal class WriteInterlock(private val directory: File) {
    val marker = File(directory, "write-uncertain.json")
    private val atomic = AtomicFile(marker)
    private var verifiedToken: String? = null
    private var armFailed = false
    private fun bootId(): String = File("/proc/sys/kernel/random/boot_id").readText().trim().also {
        requireSafe(Regex("[a-f0-9-]{36}").matches(it), "无法获取内核启动 ID，拒绝写入")
    }
    private fun syncDirectory() {
        // O_DIRECTORY is not exposed by the public Android SDK. Validate the opened FD instead.
        val closeOnExec = if (android.os.Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or closeOnExec, 0)
        try {
            Os.fcntlInt(fd, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
            requireSafe(OsConstants.S_ISDIR(Os.fstat(fd).st_mode), "互锁父目录不是目录，拒绝写入")
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }
    @Synchronized fun check() {
        requireSafe(!armFailed, "写入互锁持久化失败；本进程禁止设备操作和清理")
        if (!marker.exists() && !File(marker.path + ".bak").exists()) return
        val old = runCatching { JSONObject(String(atomic.readFully(), Charsets.UTF_8)) }.getOrNull()
        requireSafe(old != null && (old.optString("bootId").let { it.matches(Regex("[a-f0-9-]{36}")) && it != bootId() } ||
            (verifiedToken != null && old.optString("token") == verifiedToken)),
            "存在本次启动的写入记录且本进程未确认结束；禁止再次读写或清理。保留备份并人工检查。设备下次启动才解除有效记录的进程互锁（不代表恢复，不应为解锁而盲目重启）；损坏记录须人工处理。")
    }
    @Synchronized fun arm(token: String, partition: String) {
        check()
        verifiedToken = null
        armFailed = true
        val payload = JSONObject().put("token", token).put("bootId", bootId()).put("partition", partition).toString().toByteArray()
        val stream = atomic.startWrite()
        try {
            stream.write(payload); stream.fd.sync()
            atomic.finishWrite(stream)
            syncDirectory()
            requireSafe(marker.readBytes().contentEquals(payload), "互锁记录写入核验失败")
            armFailed = false
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    @Synchronized fun verified(token: String) {
        requireSafe(JSONObject(marker.readText()).getString("token") == token, "写入互锁不匹配")
        // No unlink/fsync gap. A cold process intentionally cannot trust a previous in-memory receipt.
        verifiedToken = token
    }
}
