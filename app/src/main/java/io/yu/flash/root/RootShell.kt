package io.yu.flash.root

import io.yu.flash.core.*
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Not a terminal API: only this package's audited templates may call run. No UI accepts shell input. */
internal interface RootExecutor { suspend fun run(script: String, timeoutSeconds: Long = 20): CommandResult }
internal data class CommandResult(val code: Int, val output: String, val error: String) {
    fun checked(): String {
        requireSafe(code == 0, "Root 命令失败 (exit=$code): ${error.take(240)}")
        return output.trim()
    }
}
internal class SuExecutor(private val directory: java.io.File) : RootExecutor {
    override suspend fun run(script: String, timeoutSeconds: Long): CommandResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        // Redirect to app-owned regular files: no blocking pipe-drain child can prevent cancellation.
        val out = java.io.File.createTempFile("root-out-", ".tmp", directory)
        val err = java.io.File.createTempFile("root-err-", ".tmp", directory)
        var process: Process? = null
        try {
            val running = try { ProcessBuilder("su", "-c", script).redirectOutput(out).redirectError(err).start() }
            catch (e: IOException) { throw RootFailure(RootState.MISSING, "没有可启动的 su") }
            process = running
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (true) {
                currentCoroutineContext().ensureActive()
                requireSafe(out.length() <= 1048576 && err.length() <= 1048576, "Root 诊断输出超限")
                if (running.waitFor(200, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() > end) throw RootFailure(RootState.TIMEOUT, "Root 授权或命令超时；子进程状态不能保证")
            }
            requireSafe(out.length() <= 1048576 && err.length() <= 1048576, "Root 诊断输出超限")
            CommandResult(running.exitValue(), out.readText(), err.readText())
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly() }
            out.delete(); err.delete()
        }
    }
}
internal class RootFailure(val state: RootState, message: String) : IOException(message)
internal object ShellArg {
    // Only OS-provided installed APK path may contain package manager's ~ and = characters.
    fun installedApk(value: String): String {
        requireSafe(value.startsWith("/") && !value.contains('\u0000') && value.endsWith(".apk"), "安装 APK 路径无效")
        return "'" + value.replace("'", "'\\''") + "'"
    }
    fun path(value: String): String {
        requireSafe(value.startsWith("/") && value.length <= 512 &&
            Regex("/[A-Za-z0-9_./:-]+").matches(value) && value.split('/').none { it == ".." }, "路径不在受控字符集内")
        return "'$value'"
    }
    fun name(value: String): String {
        requireSafe(Regex("[A-Za-z0-9_.-]{1,96}").matches(value) && value != "." && value != "..", "非法分区名")
        return value
    }
}
