package app.animix.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** Whether the viewer has answered the auto-next question at the end of an episode. */
    val autoNextAsked: Boolean = false,
    /** Show «Избранное» after the status lists instead of first. */
    val favoritesLast: Boolean = false,
    /** Умное соединение: next episodes resolved ahead, parallel downloads, warm caches. */
    val smartConnection: Boolean = true,
    /** Экономия трафика on metered (mobile) networks: playback up to 720p, fewer parallel downloads. */
    val trafficSaver: Boolean = true,
    val animationsEnabled: Boolean = true,
    val batterySaver: Boolean = false,
    val preferredDubbing: String? = null,
    /** The quality picked last in the player, used for the next episode too. */
    val preferredQuality: String? = null,
    /** Fullscreen video zoomed to fill the screen, cropping at most a little of the edges. */
    val fillScreen: Boolean = false,
    /** Quality for downloads; asked on the first download, changeable in settings. */
    val downloadQuality: String? = null,
    /** Last dubbing chosen per anime (anime key -> dubbing key). */
    val dubbingByAnime: Map<String, String> = emptyMap(),
) {
    val animationsAllowed: Boolean get() = animationsEnabled && !batterySaver
}

/** User preferences, loaded once at start and saved on every change. */
class AppSettings(private val store: DiskStore, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()
    private val writeLock = Mutex()

    suspend fun load() {
        val text = store.read(FILE) ?: return
        runCatching { AppJson.decodeFromString(Settings.serializer(), text) }
            .onSuccess { _state.value = it }
    }

    fun update(transform: (Settings) -> Settings) {
        _state.update(transform)
        scope.launch { writeLock.withLock { store.write(FILE, AppJson.encodeToString(Settings.serializer(), _state.value)) } }
    }

    suspend fun flush() = writeLock.withLock { store.write(FILE, AppJson.encodeToString(Settings.serializer(), _state.value)) }

    private companion object {
        const val FILE = "settings.json"
    }
}
