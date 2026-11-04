package app.animix.core

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*

internal expect fun settingsDataStore(store: DiskStore): DataStore<Preferences>

/** Synchronous UI edits and disk acknowledgements share this very short, non-suspending lock. */
internal expect class SettingsGuard() {
    fun <T> locked(block: () -> T): T
}

internal object SettingsPreferences {
    val schema = intPreferencesKey("schema_version")
    private val theme = stringPreferencesKey("themeMode")
    private val dubbings = stringPreferencesKey("dubbingByAnime")
    private val flags = listOf("skipOpenings", "skipEndings", "autoNextEpisode", "autoNextAsked", "favoritesLast",
        "smartConnection", "trafficSaver", "animationsEnabled", "batterySaver", "fillScreen", "ambientGlow", "experimentalPulse", "experimentalPicks", "reviewMusicEnabled",
        "notifyNewEpisodes", "notifyDubbing", "experimentalCollections")
    private val strings = listOf("preferredDubbing", "preferredQuality", "downloadQuality", "dismissedUpdate", "playerMode", "appIcon")
    private val floats = listOf("playbackSpeed")

    fun decode(preferences: Preferences): Settings {
        // Explicit preference types; the nested dubbing map remains a JSON string.
        val json = kotlinx.serialization.json.buildJsonObject {
            preferences[theme]?.let { put("themeMode", kotlinx.serialization.json.JsonPrimitive(it)) }
            for (name in flags) preferences[booleanPreferencesKey(name)]?.let {
                put(name, kotlinx.serialization.json.JsonPrimitive(it))
            }
            for (name in strings) preferences[stringPreferencesKey(name)]?.let {
                put(name, kotlinx.serialization.json.JsonPrimitive(it))
            }
            for (name in floats) preferences[floatPreferencesKey(name)]?.let {
                put(name, kotlinx.serialization.json.JsonPrimitive(it))
            }
            preferences[dubbings]?.let { put("dubbingByAnime", AppJson.parseToJsonElement(it)) }
        }
        return AppJson.decodeFromJsonElement(Settings.serializer(), json)
    }

    fun encode(settings: Settings, base: Preferences = emptyPreferences()): Preferences {
        // Encode defaults too, so changing an app default cannot change an existing preference.
        val json = kotlinx.serialization.json.Json(AppJson) { encodeDefaults = true }
            .encodeToJsonElement(Settings.serializer(), settings) as kotlinx.serialization.json.JsonObject
        return base.toMutablePreferences().apply {
            this[schema] = 1
            this[theme] = settings.themeMode.name
            for (name in flags) this[booleanPreferencesKey(name)] =
                (json.getValue(name) as kotlinx.serialization.json.JsonPrimitive).content.toBooleanStrict()
            for (name in strings) {
                val value = json[name]
                if (value == null || value is kotlinx.serialization.json.JsonNull) remove(stringPreferencesKey(name))
                else this[stringPreferencesKey(name)] = (value as kotlinx.serialization.json.JsonPrimitive).content
            }
            for (name in floats) this[floatPreferencesKey(name)] = (json.getValue(name) as kotlinx.serialization.json.JsonPrimitive).content.toFloat()
            this[dubbings] = AppJson.encodeToString<Map<String, String>>(settings.dubbingByAnime)
        }
    }
}

internal class LegacySettingsMigration(private val store: DiskStore) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[SettingsPreferences.schema] == null
    override suspend fun migrate(currentData: Preferences): Preferences {
        val text = store.readChecked("settings.json")
        val settings = if (text == null) Settings() else try {
            AppJson.decodeFromString(Settings.serializer(), text)
        } catch (error: kotlinx.serialization.SerializationException) {
            // Preserve the original JSON as a recovery copy. Never write defaults over it.
            AppLog.failure("Settings", "legacy_decode", error)
            Settings()
        }
        return SettingsPreferences.encode(settings, currentData)
    }
    override suspend fun cleanUp() {
        // Called after a successful DataStore commit. Keep the JSON as the pre-migration backup.
        AppLog.info("Settings", "preferences_migrated")
    }
}
