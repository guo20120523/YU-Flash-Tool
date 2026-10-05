package io.yu.flash.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import io.yu.flash.core.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal class ImageImporter(private val resolver: ContentResolver, private val directory: File) {
    suspend fun import(uri: Uri, progress: (Long) -> Unit): SelectedImage = withContext(Dispatchers.IO) {
        requireSafe(uri.scheme == "content", "仅接受系统文档选择器 content:// URI")
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
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { source ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = source.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            actual = Math.addExact(actual, n.toLong())
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
        requireSafe(partial.length() == actual, "导入复制未完成")
        val complete = File(task, "selected.bin")
        requireSafe(partial.renameTo(complete), "暂存提交失败")
        // Deliberately no format, signature, hash, empty-file or target-capacity policy.
        SelectedImage(complete, actual)
    }
}
