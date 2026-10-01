package io.yu.flash

import android.app.Application
import io.yu.flash.core.TransactionGate
import io.yu.flash.root.*
import io.yu.flash.storage.*
import kotlinx.coroutines.*

internal class AppGraph(val context: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val journal = TaskJournal(context)
    val settings = SettingsStore(context)
    val gate = TransactionGate()
    val partitions = PartitionRepository(context, SuExecutor(context.cacheDir))
    val device = RootDeviceAccess(context, partitions)
    val importer = ImageImporter(context.contentResolver, java.io.File(context.filesDir, "staging"))
    val recovered = scope.async { journal.recoverInterrupted() }
    val operations = OperationController(this)
}
class YuApplication : Application() {
    internal lateinit var graph: AppGraph
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}
