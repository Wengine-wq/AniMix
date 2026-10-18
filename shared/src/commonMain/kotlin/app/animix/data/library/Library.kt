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
    val viewing: Map<String, EpisodeViewing> = emptyMap(),
) {
    val isEmpty: Boolean get() = status == null && !favorite && episodesWatched == 0 && score == null && episodePositions.isEmpty() && viewing.isEmpty()
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
    /** Seconds watched per local day (see [app.animix.core.localDayIndex]), for streaks and the activity chart. */
    val watchDays: Map<Long, Int> = emptyMap(),
    val watchRemainderMs: Int = 0,
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
data class StatusChange(val animeKey: String, val from: ListStatus?, val to: ListStatus?)

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

    /** Every list status change as it happens, so listeners never miss a quick In-between step. */
    val statusChanges = kotlinx.coroutines.flow.MutableSharedFlow<StatusChange>(extraBufferCapacity = 64)

    fun setStatus(anime: Anime, status: ListStatus?) {
        val before = _state.value.entries[anime.key]?.status
        change(anime) { it.copy(status = status) }
        if (before != status) statusChanges.tryEmit(StatusChange(anime.key, before, status))
    }

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

    fun recordPlayback(anime: Anime, number: String, credit: PlaybackCredit, durationMs: Long,
        opening: app.animix.model.SkipRange?, ending: app.animix.model.SkipRange?, reachedEnd: Boolean) {
        if (durationMs <= 0 || (credit.ranges.isEmpty() && !reachedEnd)) return
        val now = nowMillis()
        val key = viewingKey(number)
        _state.update { data ->
            val entry = data.entry(anime) ?: LibraryEntry(anime)
            val before = entry.viewing[key] ?: EpisodeViewing()
            var viewing = before.copy(durationMs = durationMs,
                ranges = mergeViewedRanges(before.ranges + credit.ranges, durationMs),
                opening = opening ?: before.opening, ending = ending ?: before.ending,
                reachedEnd = before.reachedEnd || reachedEnd)
            if (viewing.completedAt == null && viewing.qualifies()) viewing = viewing.copy(completedAt = now)
            val all = entry.viewing + (key to viewing)
            var watched = entry.episodesWatched
            // Out-of-order playback never silently marks every preceding episode as watched.
            while (all[(watched + 1).toString()]?.completedAt != null) watched++
            val updated = entry.copy(anime = anime, viewing = all, episodesWatched = watched, updatedAt = now,
                status = when {
                    anime.episodes > 0 && watched >= anime.episodes -> ListStatus.Completed
                    entry.status == null -> ListStatus.Watching
                    else -> entry.status
                })
            val elapsed = (credit.elapsedMs.coerceAtLeast(0) + data.watchRemainderMs)
            val seconds = (elapsed / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val day = app.animix.core.localDayIndex(now)
            data.copy(entries = data.entries + (anime.key to updated), watchRemainderMs = (elapsed % 1000).toInt(),
                watchDays = if (seconds > 0) data.watchDays + (day to ((data.watchDays[day] ?: 0) + seconds)) else data.watchDays)
        }
        save()
    }

    /** Adds playback time to today; called by the player while video is playing. */
    fun addWatchTime(seconds: Int) {
        if (seconds <= 0) return
        val day = app.animix.core.localDayIndex()
        _state.update { it.copy(watchDays = it.watchDays + (day to (it.watchDays[day] ?: 0) + seconds)) }
        save()
    }

    fun renameProfile(name: String) {
        val clean = name.trim().take(32)
        if (clean.isEmpty()) return
        _state.update { it.copy(profile = it.profile.copy(name = clean)) }
        save()
    }

    /**
     * Merges titles from another service in one save. What is already on this device wins:
     * a local status or score is kept, watched episodes only ever grow. Imported marks never
     * grant watch rewards (they do not touch [LibraryEntry.viewing]).
     */
    fun importTitles(titles: List<ImportedTitle>): ImportResult {
        var added = 0
        var updated = 0
        val changes = mutableListOf<StatusChange>()
        _state.update { data ->
            val entries = data.entries.toMutableMap()
            for (title in titles) {
                val key = title.anime.key
                val current = entries[key]
                val base = current ?: LibraryEntry(title.anime)
                val merged = base.copy(
                    anime = title.anime.takeIf { it.title.isNotEmpty() } ?: base.anime,
                    status = base.status ?: title.status,
                    favorite = base.favorite || title.favorite,
                    episodesWatched = maxOf(base.episodesWatched, title.episodesWatched),
                    score = base.score ?: title.score?.takeIf { it in 1..10 },
                    updatedAt = nowMillis(),
                )
                if (merged.copy(updatedAt = base.updatedAt, anime = base.anime) == base) continue
                if (current == null) added++ else updated++
                if (merged.status != base.status) changes += StatusChange(key, base.status, merged.status)
                entries[key] = merged
            }
            data.copy(entries = entries)
        }
        changes.forEach { statusChanges.tryEmit(it) }
        save()
        return ImportResult(added, updated)
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

/** One title brought in from Shikimori or Anixart. */
data class ImportedTitle(
    val anime: Anime,
    val status: ListStatus?,
    val episodesWatched: Int = 0,
    val score: Int? = null,
    val favorite: Boolean = false,
)

data class ImportResult(val added: Int, val updated: Int)
