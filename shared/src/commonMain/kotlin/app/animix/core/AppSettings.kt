package app.animix.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode(val label: String) {
    System("Как в системе"),
    Dusk("Horizon Dusk"),
    Dawn("Horizon Dawn"),
}

@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.System,
    val skipOpenings: Boolean = true,
    val skipEndings: Boolean = false,
    val autoNextEpisode: Boolean = true,
    val preferredDubbing: String? = null,
)

/** User preferences, loaded once at start and saved on every change. */
class AppSettings(private val store: DiskStore, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    suspend fun load() {
        val text = store.read(FILE) ?: return
        runCatching { AppJson.decodeFromString(Settings.serializer(), text) }
            .onSuccess { _state.value = it }
    }

    fun update(transform: (Settings) -> Settings) {
        _state.update(transform)
        val snapshot = _state.value
        scope.launch { store.write(FILE, AppJson.encodeToString(Settings.serializer(), snapshot)) }
    }

    private companion object {
        const val FILE = "settings.json"
    }
}
