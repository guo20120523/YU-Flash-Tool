package io.yu.flash.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import io.yu.flash.core.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal class ImageImporter(private val resolver: ContentResolver, private val directory: File) {
    suspend fun import(uri: Uri, maximum: Long, progress: (Long) -> Unit): ImportedImage = withContext(Dispatchers.IO) {
        requireSafe(uri.scheme == "content", "仅接受系统文档选择器 content:// URI")
        requireSafe(maximum > 0, "目标容量未知")
        // Never use a provider's display name as a path or trust its declared length.
        val task = File(directory, UUID.randomUUID().toString())
        requireSafe(task.mkdirs(), "无法创建应用私有暂存目录")
        val partial = File(task, "import.partial")
        val signal = CancellationSignal()
        val descriptor = AtomicReference<ParcelFileDescriptor?>(null)
        var actual = 0L
        coroutineScope {
            // A sibling can close the descriptor while this worker is in a blocking read.
            // A broken provider may ignore cancellation during open; no safe-cancel promise is made.
            val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { signal.cancel(); runCatching { descriptor.getAndSet(null)?.close() } }
            }
            try {
                val fd = resolver.openFileDescriptor(uri, "r", signal)
                    ?: throw SafetyException("文档提供器无法打开文件，请先下载到本地")
                descriptor.set(fd)
                currentCoroutineContext().ensureActive()
                requireSafe(OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode), "仅导入本地普通文件；不接受云端管道或流设备，请先保存到本地")
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { source ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = source.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            actual = Math.addExact(actual, n.toLong())
                            requireSafe(actual <= maximum, "实际导入字节数超过分区容量")
                            requireSafe(task.usableSpace >= n + 64L * 1024 * 1024, "暂存空间不足")
                            output.write(buffer, 0, n); progress(actual)
                        }
                        output.flush(); output.fd.sync()
                    }
                }
            } finally {
                closer.cancel()
                runCatching { descriptor.getAndSet(null)?.close() }
            }
        }
        currentCoroutineContext().ensureActive()
        requireSafe(actual > 0 && partial.length() == actual, "文件为空或导入长度不匹配")
        val complete = File(task, "import.raw")
        requireSafe(partial.renameTo(complete), "暂存提交失败")
        try { ImageInspector.inspect(complete, "系统文档选择器镜像") }
        catch (e: Exception) { complete.renameTo(partial); throw e }
    }
}
