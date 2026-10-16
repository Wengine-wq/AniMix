package app.animix.data.library

import app.animix.core.AppJson
import app.animix.core.DiskStore
import app.animix.core.nowMillis
import app.animix.model.Anime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** List statuses; [apiValue] matches Shikimori so lists can sync once accounts arrive. */
@Serializable
enum class ListStatus(val label: String, val apiValue: String) {
    Watching("Смотрю", "watching"),
    Planned("Запланировано", "planned"),
    Completed("Просмотрено", "completed"),
    OnHold("Отложено", "on_hold"),
    Dropped("Брошено", "dropped"),
}

@Serializable
data class LibraryEntry(
    val anime: Anime,
    val status: ListStatus? = null,
    val favorite: Boolean = false,
    val episodesWatched: Int = 0,
    val score: Int? = null,
    val episodePositions: Map<String, Long> = emptyMap(),
    val updatedAt: Long = 0,
) {
    val isEmpty: Boolean get() = status == null && !favorite && episodesWatched == 0 && score == null && episodePositions.isEmpty()
}

@Serializable
data class LocalProfile(
    val name: String = "Гость",
    val createdAt: Long = 0,
)

@Serializable
data class LibraryData(
    val profile: LocalProfile = LocalProfile(),
    val entries: Map<String, LibraryEntry> = emptyMap(),
) {
    fun entry(anime: Anime): LibraryEntry? = entries[anime.key]

    fun inStatus(status: ListStatus): List<LibraryEntry> =
        entries.values.filter { it.status == status }.sortedByDescending { it.updatedAt }

    val favorites: List<LibraryEntry>
        get() = entries.values.filter { it.favorite }.sortedByDescending { it.updatedAt }

    val listed: Int get() = entries.values.count { it.status != null }
}

/**
 * The local profile and lists, kept on this device in one JSON file.
 * Accounts come later; entries already carry Shikimori-compatible statuses.
 */
class Library(private val store: DiskStore, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(LibraryData())
    val state: StateFlow<LibraryData> = _state.asStateFlow()
    private val writeLock = Mutex()

    suspend fun load() {
        val text = store.read(FILE)
        val loaded = text?.let { runCatching { AppJson.decodeFromString(LibraryData.serializer(), it) }.getOrNull() }
        _state.value = loaded ?: LibraryData(profile = LocalProfile(createdAt = nowMillis()))
        if (loaded == null) save()
    }

    fun setStatus(anime: Anime, status: ListStatus?) = change(anime) { it.copy(status = status) }

    fun toggleFavorite(anime: Anime) = change(anime) { it.copy(favorite = !it.favorite) }

    fun saveEpisodePosition(anime: Anime, episode: String, positionMs: Long) = change(anime) {
        it.copy(episodePositions = if (positionMs <= 0) it.episodePositions - episode else it.episodePositions + (episode to positionMs))
    }

    fun setEpisodesWatched(anime: Anime, episodes: Int) = change(anime) {
        val watched = episodes.coerceAtLeast(0)
        val done = anime.episodes > 0 && watched >= anime.episodes
        it.copy(
            episodesWatched = watched,
            status = when {
                done -> ListStatus.Completed
                it.status == null && watched > 0 -> ListStatus.Watching
                else -> it.status
            },
        )
    }

    fun setScore(anime: Anime, score: Int?) = change(anime) { it.copy(score = score?.coerceIn(1, 10)) }

    fun renameProfile(name: String) {
        val clean = name.trim().take(32)
        if (clean.isEmpty()) return
        _state.update { it.copy(profile = it.profile.copy(name = clean)) }
        save()
    }

    private fun change(anime: Anime, transform: (LibraryEntry) -> LibraryEntry) {
        _state.update { data ->
            val current = data.entries[anime.key] ?: LibraryEntry(anime)
            // Keep the freshest copy of the anime so lists show current titles and posters.
            val updated = transform(current).copy(anime = anime.takeIf { it.title.isNotEmpty() } ?: current.anime, updatedAt = nowMillis())
            data.copy(entries = if (updated.isEmpty) data.entries - anime.key else data.entries + (anime.key to updated))
        }
        save()
    }

    /** Writes the current state now; call before the app exits. */
    suspend fun flush() {
        writeLock.withLock { store.write(FILE, AppJson.encodeToString(LibraryData.serializer(), _state.value)) }
    }

    private fun save() {
        scope.launch {
            // Always write the latest state, so out-of-order launches can't save an older one.
            writeLock.withLock { store.write(FILE, AppJson.encodeToString(LibraryData.serializer(), _state.value)) }
        }
    }

    private companion object {
        const val FILE = "library.json"
    }
}
