package app.animix.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app theme. Ice and Coal are level rewards: chosen once they are open, and shown as the
 * system theme while the level is not reached (an older build reading them falls back to System).
 */
@Serializable
enum class ThemeMode(val label: String, val unlockLevel: Int = 0) {
    System("Как в системе"),
    Dusk("Horizon Dusk"),
    Dawn("Horizon Dawn"),
    Ice("Horizon Ice", 5),
    Coal("Horizon Coal", 10),
}

/** The launcher and window icon, one for each theme; turquoise ones open with their themes. */
@Serializable
enum class AppIcon(val label: String, val unlockLevel: Int = 0) {
    Classic("Классика"),
    Dawn("Светлая"),
    Ice("Лёд", 5),
    Coal("Уголь", 10),
}

/** «Производительность → Вид плеера»: everything of «Плеер+», or a plain, lighter player. */
@Serializable
enum class PlayerMode(val label: String) {
    Plus("Плеер+"),
    Simple("Упрощённый"),
}

@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.System,
    // Off for new installs: the viewer turns them on (and earns the settings achievements).
    // Existing preferences are stored explicitly, so changing these defaults never flips them.
    val skipOpenings: Boolean = false,
    val skipEndings: Boolean = false,
    val autoNextEpisode: Boolean = false,
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
    /** The bars around a picture of another shape glow with its colours (YouTube's ambient mode). */
    val ambientGlow: Boolean = true,
    /** Quality for downloads; asked on the first download, changeable in settings. */
    val downloadQuality: String? = null,
    /** «Экспериментальные функции»: the AniPulse weekly digest, off until the viewer opts in. */
    val experimentalPulse: Boolean = false,
    val reviewMusicEnabled: Boolean = true,
    /** «Экспериментальные функции»: «Подобрано для вас» on the home screen. */
    val experimentalPicks: Boolean = false,
    /** The update version the viewer put off with «Позже»; a newer one is offered again. */
    val dismissedUpdate: String? = null,
    /** «Плеер+» (glass, glow, speed, gestures, picture-in-picture) or the simplified player. */
    val playerMode: PlayerMode = PlayerMode.Plus,
    /** Playback speed of «Плеер+», kept for the next episode; the simplified player always plays at 1×. */
    val playbackSpeed: Float = 1f,
    /** «Уведомления»: a new episode of an ongoing title in «Смотрю». */
    val notifyNewEpisodes: Boolean = true,
    /** «Уведомления»: the new episode is out in the dubbing chosen for that title. */
    val notifyDubbing: Boolean = true,
    /** «Экспериментальные функции»: the «Подборки» tab with own lists and smart picks. */
    val experimentalCollections: Boolean = false,
    /** The icon of AniMix on the home screen of the phone and in the Windows taskbar. */
    val appIcon: AppIcon = AppIcon.Classic,
    /** Last dubbing chosen per anime (anime key -> dubbing key). */
    val dubbingByAnime: Map<String, String> = emptyMap(),
) {
    val animationsAllowed: Boolean get() = animationsEnabled && !batterySaver
}

/** Optimistic UI state backed by ordered, transactional DataStore edits. */
class AppSettings(private val store: DiskStore, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()
    private val dataStore by lazy { settingsDataStore(store) }
    private val loadLock = Mutex()
    private val guard = SettingsGuard()
    private var loaded = false
    private var base = Settings()
    private var sequence = 0L
    private data class Change(val id: Long, val transform: (Settings) -> Settings)
    private val pending = mutableListOf<Change>()
    private data class Save(val through: Long, val done: kotlinx.coroutines.CompletableDeferred<Unit>? = null)
    private val saves = kotlinx.coroutines.channels.Channel<Save>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    init {
        scope.launch {
            for (save in saves) {
                try {
                    load()
                    persistThrough(save.through)
                    save.done?.complete(Unit)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    save.done?.completeExceptionally(cancel)
                    throw cancel
                } catch (error: Exception) {
                    // Keep pending changes for retry; a failed write must never roll the UI back.
                    AppLog.failure("Settings", "preferences_save", error)
                    save.done?.completeExceptionally(error)
                }
            }
        }
    }

    suspend fun load() = loadLock.withLock {
        if (guard.locked { loaded }) return@withLock
        try {
            val saved = SettingsPreferences.decode(dataStore.data.first())
            guard.locked {
                base = saved
                _state.value = pending.fold(base) { value, change -> change.transform(value) }
                loaded = true
            }
        } catch (error: Exception) {
            if (error !is kotlinx.coroutines.CancellationException) AppLog.failure("Settings", "preferences_load", error)
            throw error
        }
    }

    fun update(transform: (Settings) -> Settings) {
        guard.locked {
            val updated = transform(_state.value)
            val change = Change(++sequence, transform)
            pending += change
            _state.value = updated
            saves.trySend(Save(change.id))
        }
    }

    /** Wait for all edits submitted before this call, including edits made during startup. */
    suspend fun flush() {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        guard.locked { saves.trySend(Save(sequence, done)) }
        done.await()
    }

    private suspend fun persistThrough(sequence: Long) {
        while (true) {
            val change = guard.locked { pending.firstOrNull()?.takeIf { it.id <= sequence } } ?: return
            val stored = dataStore.updateData { preferences ->
                SettingsPreferences.encode(change.transform(SettingsPreferences.decode(preferences)), preferences)
            }
            val committed = SettingsPreferences.decode(stored)
            guard.locked {
                pending.removeAll { it.id == change.id }
                base = committed
                _state.value = pending.fold(base) { value, edit -> edit.transform(value) }
            }
        }
    }
}
