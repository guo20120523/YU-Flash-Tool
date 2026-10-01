package io.yu.flash

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class TaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private val graph get() = (application as YuApplication).graph
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("operations", "分区任务（运行通知不可关闭）", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(phase: String): Notification {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "operations").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("YU-Flash-Tool").setContentText(phase).setContentIntent(intent)
            .setOngoing(true).setProgress(0, 0, true).setOnlyAlertOnce(true).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        val operation = graph.operations.take() ?: run { stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, 41, notification("准备任务"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) { graph.operations.rejected("前台任务无法启动：${e.message}"); stopSelf(); return START_NOT_STICKY }
        job = scope.launch {
            try {
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YUFlash:operation").apply {
                    setReferenceCounted(false); acquire(5 * 60 * 60 * 1000L)
                }
                // Local hard budget less than Android 15 dataSync six-hour aggregate budget.
                withTimeout(5 * 60 * 60 * 1000L) {
                    graph.operations.execute(operation) { phase ->
                        // FGS admission above is mandatory; optional notification refresh must not
                        // fail a backup when notification permission is denied or revoked.
                        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                                this@TaskService, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                            runCatching { getSystemService(NotificationManager::class.java).notify(41, notification(phase)) }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) graph.operations.rejected("任务启动或执行失败：${e.message}")
            } finally {
                if (wake?.isHeld == true) wake?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                withContext(NonCancellable) {
                    runCatching {
                        val state = graph.operations.flow.value
                        val allowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@TaskService, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        if (allowed && graph.settings.flow.first().completionNotice) {
                            getSystemService(NotificationManager::class.java).notify(42,
                                NotificationCompat.Builder(this@TaskService, "operations")
                                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                                    .setContentTitle("YU-Flash-Tool · 任务结束")
                                    .setContentText(state.error ?: state.phase).setAutoCancel(true).build())
                        }
                    }
                }
                withContext(NonCancellable + Dispatchers.Main) {
                    job = null
                    graph.operations.finished()
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY // never re-deliver a flash transaction
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel(CancellationException("Android 前台服务额度耗尽；任务中断，禁止自动续写"))
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        scope.cancel(); if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }
}
