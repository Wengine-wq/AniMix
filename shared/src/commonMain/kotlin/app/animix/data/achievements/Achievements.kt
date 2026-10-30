package app.animix.data.achievements

import app.animix.core.AppLog
import app.animix.core.AppJson
import app.animix.core.AppSettings
import app.animix.core.DiskStore
import app.animix.core.nowMillis
import app.animix.data.library.Library
import app.animix.data.library.LibraryData
import app.animix.data.library.ListStatus
import app.animix.data.library.hasVerifiedCompletion
import app.animix.data.library.verifiedEpisodes
import app.animix.data.library.completedWithHistory
import app.animix.data.library.confirmedWatchSeconds
import app.animix.data.library.episodesWithHistory
import app.animix.core.localDayIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Instant

enum class AchievementCategory(val label: String) {
    Anime("Аниме"), Library("Коллекция"), Settings("Настройки"), Habits("Привычки"), WatchTime("Время"),
    Downloads("Загрузки"), Discovery("Поиск"), Community("Сообщество"), Profile("Профиль"), Meta("Особые"),
}

data class Trigger(
    val metric: String,
    val threshold: Int,
    /** Shikimori id for title-specific achievements. */
    val animeId: Int? = null,
    /** Theme name or setting name, depending on [metric]. */
    val value: String? = null,
    val required: List<String> = emptyList(),
)

data class Achievement(
    val id: String,
    val number: Int,
    val category: AchievementCategory,
    val name: String,
    val description: String,
    val trigger: Trigger,
    /** Hidden until earned: the list shows neither the name nor the condition. */
    val secret: Boolean = false,
)

/** Progress of one achievement right now. */
data class AchievementProgress(
    val achievement: Achievement,
    val current: Int,
    val unlockedAt: Long?,
    val historyUnlockedAt: Long? = null,
) {
    val target: Int get() = achievement.trigger.threshold
    val earned: Boolean get() = unlockedAt != null
    val fromHistory: Boolean get() = !earned && historyUnlockedAt != null
    val unlocked: Boolean get() = earned || historyUnlockedAt != null
    val fraction: Float get() = if (unlocked) 1f else (current.toFloat() / target).coerceIn(0f, 1f)
}

/** What has to be remembered between launches; the rest is derived from the library and settings. */
@Serializable
data class AchievementState(
    val unlocked: Map<String, Long> = emptyMap(),
    val counters: Map<String, Long> = emptyMap(),
    /** Named sets of title keys (search finds, filtered finds, ever-favourited, ever-planned, …). */
    val sets: Map<String, Set<String>> = emptyMap(),
    /** When each title was first marked completed, for the monthly achievement. */
    val firstCompleted: Map<String, Long> = emptyMap(),
    /** Recognition of external viewing history. These awards carry no XP or activity dates. */
    val historical: Map<String, Long> = emptyMap(),
)

/**
 * Watches the local library and settings, plus explicit events from screens, and unlocks
 * achievements once. Playback, download and review counters are fed through [record] by
 * whatever produces those events.
 */
class Achievements(
    private val store: DiskStore,
    private val scope: CoroutineScope,
    private val library: Library,
    private val settings: AppSettings,
    private val catalog: List<Achievement> = AchievementCatalog,
) {
    private val _state = MutableStateFlow(AchievementState())
    private val _progress = MutableStateFlow<List<AchievementProgress>>(emptyList())
    private val _unlockedNow = MutableSharedFlow<Achievement>(extraBufferCapacity = 8)
    private val writeLock = Mutex()
    private val loadLock = Mutex()
    private var loaded = false
    private data class Event(
        val library: LibraryData,
        val transform: (AchievementState) -> AchievementState = { it },
        val flushed: CompletableDeferred<Unit>? = null,
    )
    // Every action is retained, including actions before load and intermediate list states.
    // Only this queue's consumer may change award state; disk writers only read it.
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val writes = Channel<Unit>(Channel.CONFLATED)

    init {
        library.onAchievementChange = { data -> events.trySend(Event(data)); Unit }
        library.onAchievementStatusChange = { change ->
            if (change.from == ListStatus.OnHold && change.to == ListStatus.Watching) record("hold_to_watching")
        }
    }

    val progress: StateFlow<List<AchievementProgress>> = _progress.asStateFlow()

    /** Achievements unlocked during this session, for the toast. */
    val unlockedNow: SharedFlow<Achievement> = _unlockedNow.asSharedFlow()

    val all: List<Achievement> get() = catalog

    suspend fun load() = loadLock.withLock {
        if (loaded) return@withLock
        val primary = store.readChecked(FILE)
        val decoded = primary?.let { runCatching { AppJson.decodeFromString<AchievementState>(it) } }
        _state.value = decoded?.getOrNull() ?: store.readChecked(BACKUP)?.let {
            AppJson.decodeFromString<AchievementState>(it)
        } ?: run {
            // Never overwrite unrecognised saved progress with an empty award collection.
            decoded?.exceptionOrNull()?.let { throw it }
            AchievementState()
        }
        _state.value = repairWatchTimeAwards(_state.value, library.state.value, catalog)
        loaded = true
        observeLibrary(library.state.value)
        evaluate(library.state.value)
        save()
        scope.launch {
            for (signal in writes) {
                try { persist() }
                catch (cancel: CancellationException) { throw cancel }
                // Keep the in-memory awards; the next event/flush retries the write.
                catch (error: Exception) { AppLog.failure("Achievements", "awards_save", error) }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (event in events) {
                val before = _state.value
                _state.value = event.transform(before)
                observeLibrary(event.library)
                evaluate(event.library)
                if (_state.value != before) save()
                event.flushed?.let { done ->
                    try {
                        persist()
                        done.complete(Unit)
                    } catch (error: Exception) {
                        done.completeExceptionally(error)
                        if (error is CancellationException) throw error
                    }
                }
            }
        }
    }

    // --- Events from screens -------------------------------------------------------------

    /** The user picked a theme by hand in Settings. */
    fun themeChosen(name: String) = addToSet("theme", name)

    /** The user switched a setting on by hand. */
    fun settingEnabled(name: String) = addToSet("setting", name)

    /** A genre switched on in the catalog filters; only the secret genres are remembered. */
    fun genreChosen(id: String, title: String) { secretGenre(id, title)?.let { addToSet("secret_genres", it) } }

    /** A search the catalog actually ran (after typing settled). */
    fun searched(text: String) { if (isHealerSearch(text)) addToSet("secret_searches", "healer") }

    /** A title was opened from search results. */
    fun openedFromSearch(animeKey: String) = addToSet("search", animeKey)

    /** A title was opened from a catalog filtered by type. */
    fun openedFromFilter(animeKey: String) = addToSet("filtered", animeKey)

    /** Adds to a non-playback event counter, such as completed downloads or published reviews. */
    fun record(counter: String, amount: Long = 1) {
        if (amount <= 0) return
        events.trySend(Event(library.state.value, { state ->
            state.copy(counters = state.counters + (counter to ((state.counters[counter] ?: 0L) + amount)))
        }))
    }

    /** A completed download identity is counted once, including restoration on startup. */
    fun downloadCompleted(id: String) = addToSet("downloads_completed", id)

    // --- Internals ---------------------------------------------------------------------------

    private fun addToSet(name: String, value: String) {
        events.trySend(Event(library.state.value, { it.withSet(name, value) }))
    }

    /** Remembers list history that the current library snapshot alone can't tell. */
    private fun observeLibrary(lib: LibraryData) {
        var state = _state.value
        val now = nowMillis()
        for ((key, entry) in lib.entries) {
            if (entry.favorite) state = state.withSet("favorites_ever", key)
            if (entry.status == ListStatus.Planned) state = state.withSet("planned_ever", key)
            if (entry.hasVerifiedCompletion) {
                if (key !in state.firstCompleted) state = state.copy(firstCompleted = state.firstCompleted + (key to now))
                if (state.sets["planned_ever"]?.contains(key) == true) state = state.withSet("planned_completed", key)
            }
        }
        if (state != _state.value) {
            _state.value = state
        }
    }

    private fun AchievementState.withSet(name: String, value: String): AchievementState =
        if (sets[name]?.contains(value) == true) this else copy(sets = sets + (name to (sets[name].orEmpty() + value)))

    private fun evaluate(lib: LibraryData) {
        if (!loaded) return
        val state = _state.value
        val profileName = lib.profile.name.trim().replace(Regex("\\s+"), " ")
        val entries = lib.entries.values
        val verifiedDays = entries.flatMap { it.viewing.values }.mapNotNull { it.completedAt }
            .groupingBy { localDayIndex(it) }.eachCount()
        val unlocked = state.unlocked.toMutableMap()
        val historical = state.historical.toMutableMap()
        val fresh = mutableListOf<Achievement>()

        fun current(a: Achievement): Int {
            val t = a.trigger
            val set = { name: String -> state.sets[name].orEmpty() }
            return when (t.metric) {
                "anime_season_completed", "anime_release_completed" ->
                    if (lib.entries["s${t.animeId}"]?.hasVerifiedCompletion == true) 1 else 0
                "anime_episodes_watched" -> lib.entries["s${t.animeId}"]?.verifiedEpisodes ?: 0
                "titles_completed" -> entries.count { it.hasVerifiedCompletion }
                "favorite_titles_added" -> set("favorites_ever").size
                "favorite_titles_current" -> entries.count { it.favorite }
                "planned_titles_current" -> entries.count { it.status == ListStatus.Planned }
                "on_hold_titles_current" -> entries.count { it.status == ListStatus.OnHold }
                "on_hold_to_watching_transitions" -> (state.counters["hold_to_watching"] ?: 0).toInt()
                "unique_planned_titles_completed" -> set("planned_completed").count { lib.entries[it]?.hasVerifiedCompletion == true }
                "unique_titles_completed_in_month" -> state.firstCompleted.filterKeys { lib.entries[it]?.hasVerifiedCompletion == true }
                    .values.groupingBy(::monthOf).eachCount().values.maxOrNull() ?: 0
                "custom_profile_name_saved" -> if (profileName.isNotEmpty() && profileName != "Гость") 1 else 0
                "theme_selected" -> if (t.value in set("theme")) 1 else 0
                "setting_enabled_by_user" -> if (t.value in set("setting")) 1 else 0
                "filtered_catalog_titles_opened" -> set("filtered").size
                "unique_titles_opened_from_search" -> set("search").size
                "secret_genre_selected" -> if (t.value in set("secret_genres")) 1 else 0
                "secret_search" -> if (t.value in set("secret_searches")) 1 else 0
                "watch_seconds" -> lib.confirmedWatchSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                "episode_downloads_completed" -> maxOf(state.sets["downloads_completed"].orEmpty().size, (state.counters["episode_downloads_completed"] ?: 0).toInt())
                "reviews_published" -> (state.counters["reviews_published"] ?: 0).toInt()
                "episodes_per_day" -> verifiedDays.values.maxOrNull() ?: 0
                "daily_episode_streak" -> {
                    var run = 0; var best = 0; var previous: Long? = null
                    for (day in verifiedDays.keys.sorted()) { run = if (previous != null && day == previous + 1) run + 1 else 1; best = maxOf(best, run); previous = day }
                    best
                }
                "all_required_achievements_unlocked" -> t.required.count { it in unlocked }
                else -> 0
            }
        }

        fun withHistory(a: Achievement): Int = when (a.trigger.metric) {
            "anime_season_completed", "anime_release_completed" ->
                if (lib.entries["s${a.trigger.animeId}"]?.completedWithHistory == true) 1 else 0
            "anime_episodes_watched" -> lib.entries["s${a.trigger.animeId}"]?.episodesWithHistory ?: 0
            "titles_completed" -> entries.count { it.completedWithHistory }
            "all_required_achievements_unlocked" -> a.trigger.required.count { it in unlocked || it in historical }
            else -> current(a)
        }

        // Resolve dependencies to a fixed point, independent of catalog order/depth.
        do {
            val earnedBefore = unlocked.size
            val historyBefore = historical.size
            for (a in catalog) {
                if (a.id in unlocked) continue
                if (current(a) >= a.trigger.threshold) {
                    unlocked[a.id] = nowMillis()
                    fresh += a
                } else if (a.id !in historical && withHistory(a) >= a.trigger.threshold) {
                    historical[a.id] = nowMillis()
                }
            }
        }
        while (unlocked.size != earnedBefore || historical.size != historyBefore)
        if (fresh.isNotEmpty() || historical != state.historical) {
            _state.value = _state.value.copy(unlocked = unlocked, historical = historical)
            fresh.forEach { _unlockedNow.tryEmit(it) }
        }
        _progress.value = catalog.map { AchievementProgress(it, maxOf(current(it), withHistory(it)), unlocked[it.id], historical[it.id]) }
    }

    private fun save() {
        if (!loaded) return
        writes.trySend(Unit)
    }

    /** A barrier: evaluate all earlier actions before writing, even on immediate shutdown. */
    suspend fun flush() {
        load()
        val done = CompletableDeferred<Unit>()
        events.send(Event(library.state.value, flushed = done))
        done.await()
    }

    private suspend fun persist() = writeLock.withLock {
        val text = AppJson.encodeToString(AchievementState.serializer(), _state.value)
        store.writeChecked(FILE, text)
        store.writeChecked(BACKUP, text)
    }

    internal companion object {
        const val FILE = "achievements.json"
        const val BACKUP = "achievements.backup.json"
        private const val WATCH_TIME_REPAIR = "repair_watch_time_v1"

        /**
         * Earlier builds summed a daily counter that could be inflated (34 000 hours after a list
         * import). Once, take back time awards the confirmed playback coverage does not support.
         */
        fun repairWatchTimeAwards(state: AchievementState, lib: LibraryData, catalog: List<Achievement>): AchievementState {
            if (WATCH_TIME_REPAIR in state.counters) return state
            val confirmed = lib.confirmedWatchSeconds
            val revoked = catalog.filter { it.trigger.metric == "watch_seconds" && confirmed < it.trigger.threshold }.map { it.id }.toSet()
            if (revoked.any { it in state.unlocked || it in state.historical }) AppLog.info("Achievements", "watch_time_awards_revoked")
            return state.copy(unlocked = state.unlocked - revoked, historical = state.historical - revoked,
                counters = state.counters + (WATCH_TIME_REPAIR to 1L))
        }

        /** Shikimori genre ids 12 (Hentai) and 9 (Ecchi), or their names. */
        fun secretGenre(id: String, title: String): String? = when {
            id == "12" || title.trim().lowercase() in setOf("хентай", "hentai") -> "hentai"
            id == "9" || title.trim().lowercase() in setOf("этти", "эччи", "ecchi") -> "ecchi"
            else -> null
        }

        /** «Маг целитель», «маг-целитель», «МАГ  ЦЕЛИТЕЛЬ: новый старт» — letters only, any case. */
        fun isHealerSearch(text: String): Boolean =
            "магцелитель" in text.lowercase().replace('ё', 'е').filter { it.isLetter() }

        /** "yyyy-MM" in UTC; close enough for "three titles in one month". */
        fun monthOf(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString().take(7)

        /** Longest run of consecutive "yyyy-MM-dd" days. */
        fun longestStreak(days: Set<String>): Int {
            val sorted = days.mapNotNull { dayNumber(it) }.sorted()
            var best = 0
            var run = 0
            var previous: Long? = null
            for (day in sorted) {
                run = if (previous != null && day == previous + 1) run + 1 else 1
                best = maxOf(best, run)
                previous = day
            }
            return best
        }

        private fun dayNumber(day: String): Long? = runCatching {
            Instant.parse("${day}T00:00:00Z").toEpochMilliseconds() / 86_400_000L
        }.getOrNull()
    }
}
