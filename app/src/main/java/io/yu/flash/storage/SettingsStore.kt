package io.yu.flash.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")
internal data class AppSettings(
    val theme: String = "system", val dynamic: Boolean = true, val compact: Boolean = false,
    val binaryUnits: Boolean = true, val backupPath: String = "/sdcard/download",
    val minBattery: Int = 50, val requireCharging: Boolean = true, val completionNotice: Boolean = true,
    val cleanup: Boolean = true, val showHighRisk: Boolean = true, val verbose: Boolean = false
)
internal class SettingsStore(context: Context) {
    private val store = context.settingsDataStore
    val flow = store.data.map { p ->
        AppSettings(p[stringPreferencesKey("theme")] ?: "system", p[booleanPreferencesKey("dynamic")] ?: true,
            p[booleanPreferencesKey("compact")] ?: false, p[booleanPreferencesKey("binaryUnits")] ?: true,
            p[stringPreferencesKey("backupPath")] ?: "/sdcard/download", p[intPreferencesKey("minBattery")] ?: 50,
            p[booleanPreferencesKey("requireCharging")] ?: true, p[booleanPreferencesKey("completionNotice")] ?: true,
            p[booleanPreferencesKey("cleanup")] ?: true, p[booleanPreferencesKey("showHighRisk")] ?: true,
            p[booleanPreferencesKey("verbose")] ?: false)
    }
    suspend fun text(key: String, value: String) { store.edit { it[stringPreferencesKey(key)] = value } }
    suspend fun flag(key: String, value: Boolean) { store.edit { it[booleanPreferencesKey(key)] = value } }
    suspend fun battery(value: Int) { store.edit { it[intPreferencesKey("minBattery")] = value.coerceIn(50, 100) } }
}
