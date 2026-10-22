package app.animix.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val preferenceStores = mutableMapOf<String, DataStore<Preferences>>()

internal actual fun settingsDataStore(store: DiskStore): DataStore<Preferences> = synchronized(preferenceStores) {
    val file = File(store.filePath("settings.preferences_pb")).canonicalFile
    preferenceStores.getOrPut(file.path) {
        PreferenceDataStoreFactory.create(
            produceFile = { file },
            migrations = listOf(LegacySettingsMigration(store)),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}

internal actual class SettingsGuard actual constructor() {
    private val monitor = Any()
    actual fun <T> locked(block: () -> T): T = synchronized(monitor, block)
}
