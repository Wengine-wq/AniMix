package app.animix.data.library

import app.animix.core.AppJson
import app.animix.core.DiskStore
import app.animix.core.nowMillis
import app.animix.core.AppLog
import app.animix.core.takeCharacters
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

/** A claim from an external list, kept separate from decoded playback and local list edits. */
@Serializable
data class ImportedViewing(
    val completed: Boolean = false,
    val episodesWatched: Int = 0,
)

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
    val importedViewing: ImportedViewing? = null,
) {
    val isEmpty: Boolean get() = status == null && !favorite && episodesWatched == 0 && score == null && episodePositions.isEmpty() && viewing.isEmpty() && importedViewing == null
}

@Serializable
data class LibraryData(
    val profile: LocalProfile = LocalProfile(),
    val entries: Map<String, LibraryEntry> = emptyMap(),
    /** Seconds watched per local day (see [app.animix.core.localDayIndex]), for streaks and the activity chart. */
    val watchDays: Map<Long, Int> = emptyMap(),
    val watchRemainderMs: Int = 0,
    /** Usage by calendar year for «Итоги года» (hours, weekdays, studios, sessions, skips). */
    val usage: Map<Int, YearUsage> = emptyMap(),
    /** Playback time measured by AniMix 2.x and brought over once; see [LegacyMigrationRecord]. */
    val legacyWatchSeconds: Long = 0,
    val legacyMigration: LegacyMigrationRecord? = null,
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
    private val loadLock = Mutex()
    private var loaded = false
    private var persistedText: String? = null

    // Reward processing must see every committed snapshot; StateFlow may conflate quick edits.
    internal var onAchievementChange: ((LibraryData) -> Unit)? = null
    internal var onAchievementStatusChange: ((StatusChange) -> Unit)? = null

    suspend fun load() = loadLock.withLock {
        if (loaded) return@withLock
        // Unreadable data must never become a new empty library, including on exit/flush.
        val text = store.readChecked(FILE)
        val decoded = text?.let { runCatching { AppJson.decodeFromString(LibraryData.serializer(), it) } }
        val backup = if (text == null || decoded?.isFailure == true) store.readChecked(BACKUP) else null
        val restored = backup?.let { runCatching { AppJson.decodeFromString(LibraryData.serializer(), it) }.getOrNull() }
        if (decoded?.isFailure == true && restored == null) {
            throw IllegalStateException("Не удалось прочитать библиотеку. Исходный файл сохранён без изменений.", decoded.exceptionOrNull())
        }
        if (text == null && backup != null && restored == null) {
            throw IllegalStateException("Не удалось прочитать резервную копию библиотеки. Файл сохранён без изменений.")
        }
        if (restored != null && text != null) {
            // Archive the incompatible original before recovering; failure here blocks replacement.
            store.writeChecked("library.recovery-${nowMillis()}.json", text)
        }
        val data = decoded?.getOrNull() ?: restored
        persistedText = if (restored != null) backup else text
        _state.value = data?.copy(profile = data.profile.copy(name = data.profile.name.takeCharacters(ProfileNameLimit)))
            ?: LibraryData(profile = LocalProfile(createdAt = nowMillis()))
        this.loaded = true
        if (restored != null) AppLog.info("Library", "backup_restored")
        if (text == null || restored != null || data != _state.value) save()
    }

    /** Every list status change as it happens, so listeners never miss a quick In-between step. */
    val statusChanges = kotlinx.coroutines.flow.MutableSharedFlow<StatusChange>(extraBufferCapacity = 64)

    fun setStatus(anime: Anime, status: ListStatus?) {
        if (anime.title.isBlank()) return
        val before = _state.value.entries[anime.key]?.status
        change(anime) { it.copy(status = status) }
        if (before != status) {
            val change = StatusChange(anime.key, before, status)
            statusChanges.tryEmit(change)
            onAchievementStatusChange?.invoke(change)
        }
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
        opening: app.animix.model.SkipRange?, ending: app.animix.model.SkipRange?, reachedEnd: Boolean,
        context: PlaybackContext? = null) {
        if (durationMs <= 0 || (credit.ranges.isEmpty() && !reachedEnd)) return
        // Confirmation dates feed day streaks and marathons; a moved device clock must not shift them.
        val now = app.animix.core.TrustedClock.now()
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
            val year = dayToDate(day).year
            val usage = if (seconds > 0) data.usage + (year to (data.usage[year] ?: YearUsage()).add(
                seconds, now, app.animix.core.localHour(now), app.animix.core.weekdayOf(day), day, anime.key, context)) else data.usage
            data.copy(entries = data.entries + (anime.key to updated), watchRemainderMs = (elapsed % 1000).toInt(),
                watchDays = if (seconds > 0) data.watchDays + (day to ((data.watchDays[day] ?: 0) + seconds)) else data.watchDays,
                usage = usage)
        }
        save()
    }

    /** An opening or ending skipped in the player, and the seconds it saved, for «Итоги года». */
    fun recordSkip(seconds: Int) {
        if (seconds <= 0) return
        val year = dayToDate(app.animix.core.localDayIndex()).year
        _state.update { data ->
            val current = data.usage[year] ?: YearUsage()
            data.copy(usage = data.usage + (year to current.copy(skips = current.skips + 1, skippedSeconds = current.skippedSeconds + seconds)))
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

    /**
     * Playback time and the record of a migration from AniMix 2.x. Time is the larger of what was
     * already brought over and the new value, so migrating twice never adds the same hours again.
     * A default profile name takes the 2.x name.
     */
    fun applyLegacy(watchSeconds: Long, record: LegacyMigrationRecord, profileName: String?) {
        _state.update { data ->
            val name = profileName?.trim()?.takeCharacters(ProfileNameLimit)?.takeIf { it.isNotEmpty() }
            val profile = if (name != null && data.profile.name == LocalProfile().name) data.profile.copy(name = name, updatedAt = nowMillis()) else data.profile
            data.copy(legacyWatchSeconds = maxOf(data.legacyWatchSeconds, watchSeconds.coerceAtLeast(0)),
                legacyMigration = record.copy(watchSeconds = maxOf(data.legacyWatchSeconds, watchSeconds.coerceAtLeast(0))), profile = profile)
        }
        save()
    }

    fun renameProfile(name: String) {
        val clean = name.trim().takeCharacters(ProfileNameLimit)
        if (clean.isEmpty()) return
        _state.update { it.copy(profile = it.profile.copy(name = clean, updatedAt = nowMillis())) }
        save()
    }

    fun updateProfile(draft: ProfileDraft, unlockedBadges: Set<String>) {
        _state.update { it.copy(profile = it.profile.edited(draft, unlockedBadges, nowMillis())) }
        save()
    }

    /**
     * Merges titles from another service in one save. What is already on this device wins:
     * a local status or score is kept, watched episodes only ever grow. External viewing claims
     * can unlock historical awards, but never produce playback coverage or XP.
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
                val completed = base.importedViewing?.completed == true || title.status == ListStatus.Completed
                val importedEpisodes = maxOf(base.importedViewing?.episodesWatched ?: 0,
                    title.episodesWatched.coerceAtLeast(0),
                    if (title.status == ListStatus.Completed) title.anime.episodes.coerceAtLeast(0) else 0)
                val merged = base.copy(
                    anime = title.anime.takeIf { it.title.isNotEmpty() } ?: base.anime,
                    status = base.status ?: title.status,
                    favorite = base.favorite || title.favorite,
                    episodesWatched = maxOf(base.episodesWatched, importedEpisodes),
                    score = base.score ?: title.score?.takeIf { it in 1..10 },
                    importedViewing = if (completed || importedEpisodes > 0) ImportedViewing(completed, importedEpisodes) else base.importedViewing,
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
        if (anime.title.isBlank()) return
        _state.update { data ->
            val current = data.entries[anime.key] ?: LibraryEntry(anime)
            // Keep the freshest copy of the anime so lists show current titles and posters.
            val updated = transform(current).copy(anime = anime.takeIf { it.title.isNotEmpty() } ?: current.anime, updatedAt = nowMillis())
            data.copy(entries = if (updated.isEmpty) data.entries - anime.key else data.entries + (anime.key to updated))
        }
        save()
    }

    /** Refresh cached metadata without changing list state, rewards or the user's sort order. */
    fun refreshMetadata(anime: Anime) {
        if (anime.title.isBlank()) return
        val entry = _state.value.entries[anime.key] ?: return
        if (entry.anime == anime) return
        _state.update { data ->
            val current = data.entries[anime.key] ?: return@update data
            data.copy(entries = data.entries + (anime.key to current.copy(anime = anime)))
        }
        save()
    }

    /** Writes the current state now; call before the app exits. */
    suspend fun flush() {
        load()
        writeLock.withLock { persist() }
    }

    private fun save() {
        if (!loaded) return
        onAchievementChange?.invoke(_state.value)
        scope.launch {
            // Always write the latest state, so out-of-order launches can't save an older one.
            try {
                writeLock.withLock { persist() }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                AppLog.failure("Library", "save_failed", error)
            }
        }
    }

    private suspend fun persist() {
        check(loaded) { "Library has not loaded successfully" }
        val text = AppJson.encodeToString(LibraryData.serializer(), _state.value)
        // A flush with no edits must not rotate the last useful recovery copy away.
        if (text != persistedText) persistedText?.let { store.writeChecked(BACKUP, it) }
        store.writeChecked(FILE, text)
        persistedText = text
    }

    private companion object {
        const val FILE = "library.json"
        const val BACKUP = "library.backup.json"
    }
}

/** What the last migration from AniMix 2.x brought over, shown in Settings → «Аккаунт». */
@Serializable
data class LegacyMigrationRecord(
    /** "account" (AniMix 2.x account) or "device" (2.x data on this computer). */
    val source: String,
    val at: Long,
    val titles: Int,
    val achievements: Int,
    val watchSeconds: Long,
    val accountName: String? = null,
)

/** One title brought in from Shikimori or Anixart. */
data class ImportedTitle(
    val anime: Anime,
    val status: ListStatus?,
    val episodesWatched: Int = 0,
    val score: Int? = null,
    val favorite: Boolean = false,
)

data class ImportResult(val added: Int, val updated: Int)
