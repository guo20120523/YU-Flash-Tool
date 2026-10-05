package io.yu.flash.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.yu.flash.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** SQLite transactions with FULL synchronous durability; no schema/code-generation plugin needed. */
internal class TaskJournal(context: Context) : SQLiteOpenHelper(context, "tasks.db", null, 1), AuditLog {
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events = _events.asStateFlow()
    private val _refreshError = MutableStateFlow<String?>(null)
    val refreshError = _refreshError.asStateFlow()
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, task TEXT NOT NULL, stage TEXT NOT NULL, partition_name TEXT NOT NULL, slot TEXT, bytes INTEGER NOT NULL, exit_code INTEGER, summary TEXT NOT NULL, time INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { error("需要显式数据库迁移，禁止破坏性重建") }
    override suspend fun append(event: Event) {
        currentCoroutineContext().ensureActive()
        // Once admitted, finish this short SQLite commit and return its result without a
        // dispatcher-return cancellation hiding an already persisted terminal event.
        // This does not make the surrounding device operation non-cancellable.
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                val values = android.content.ContentValues().apply {
                    put("task", event.taskId); put("stage", event.stage.name); put("partition_name", event.partition)
                    put("slot", event.slot); put("bytes", event.bytes); put("exit_code", event.exitCode)
                    put("summary", event.summary.take(2048)); put("time", event.time)
                }
                check(writableDatabase.insertOrThrow("events", null, values) > 0)
                // Display refresh is not part of durable append success. Keep its failure
                // visible, but never manufacture FAILED after a committed SUCCESS.
                try { reload() }
                catch (_: Exception) { _refreshError.value = "日志已保存，但列表刷新失败；当前显示可能不完整。请稍后重新打开任务页。" }
            }
        }
    }
    suspend fun reload() = withContext(Dispatchers.IO) {
        val list = mutableListOf<Event>()
        readableDatabase.rawQuery("SELECT task,stage,partition_name,slot,bytes,exit_code,summary,time FROM events ORDER BY id DESC LIMIT 1000", null).use { c ->
            while (c.moveToNext()) list += Event(c.getString(0), Stage.valueOf(c.getString(1)), c.getString(2),
                if (c.isNull(3)) null else c.getString(3), c.getLong(4), if (c.isNull(5)) null else c.getInt(5), c.getString(6), c.getLong(7))
        }
        _events.value = list
        _refreshError.value = null
    }
    suspend fun recoverInterrupted() = withContext(Dispatchers.IO) {
        val unfinished = mutableListOf<Event>()
        readableDatabase.rawQuery("SELECT task,stage,partition_name,slot FROM events WHERE id IN (SELECT MAX(id) FROM events GROUP BY task)", null).use { c ->
            while (c.moveToNext()) if (Stage.valueOf(c.getString(1)) !in setOf(Stage.SUCCESS, Stage.FAILED, Stage.INTERRUPTED))
                unfinished += Event(c.getString(0), Stage.INTERRUPTED, c.getString(2), c.getString(3), summary = "进程在 ${c.getString(1)} 阶段退出。源/目标及 Root 子进程状态须重新检查；禁止自动续写、回滚或重启")
        }
        unfinished.forEach { append(it) }; reload()
    }
    fun redacted(): String = events.value.joinToString("\n") {
        "${it.time} | ${it.taskId} | ${it.partition} | ${it.slot ?: "未知"} | ${it.stage} | ${it.bytes} bytes | exit=${it.exitCode ?: "n/a"}"
    }
}
