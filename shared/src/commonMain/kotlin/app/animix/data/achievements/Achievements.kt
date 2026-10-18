package app.animix.data.achievements

import app.animix.core.AppJson
import app.animix.core.AppSettings
import app.animix.core.DiskStore
import app.animix.core.nowMillis
import app.animix.data.library.Library
import app.animix.data.library.LibraryData
import app.animix.data.library.ListStatus
import app.animix.data.library.hasVerifiedCompletion
import app.animix.data.library.verifiedEpisodes
import app.animix.core.localDayIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
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
)

/** Progress of one achievement right now. */
data class AchievementProgress(
    val achievement: Achievement,
    val current: Int,
    val unlockedAt: Long?,
) {
    val target: Int get() = achievement.trigger.threshold
    val unlocked: Boolean get() = unlockedAt != null
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
    private var loaded = false

    val progress: StateFlow<List<AchievementProgress>> = _progress.asStateFlow()

    /** Achievements unlocked during this session, for the toast. */
    val unlockedNow: SharedFlow<Achievement> = _unlockedNow.asSharedFlow()

    val all: List<Achievement> get() = catalog

    suspend fun load() {
        if (loaded) return
        store.read(FILE)?.let { text ->
            runCatching { AppJson.decodeFromString(AchievementState.serializer(), text) }.onSuccess { _state.value = it }
        }
        loaded = true
        scope.launch {
            library.statusChanges.collect { change ->
                if (change.from == ListStatus.OnHold && change.to == ListStatus.Watching) {
                    _state.update { it.copy(counters = it.counters + ("hold_to_watching" to ((it.counters["hold_to_watching"] ?: 0) + 1))) }
                    save()
                }
            }
        }
        scope.launch {
            combine(library.state, settings.state, _state) { lib, _, _ -> lib }.collect { lib ->
                observeLibrary(lib)
                evaluate(lib)
            }
        }
    }

    // --- Events from screens -------------------------------------------------------------

    /** The user picked a theme by hand in Settings. */
    fun themeChosen(name: String) = addToSet("theme", name)

    /** The user switched a setting on by hand. */
    fun settingEnabled(name: String) = addToSet("setting", name)

    /** A title was opened from search results. */
    fun openedFromSearch(animeKey: String) = addToSet("search", animeKey)

    /** A title was opened from a catalog filtered by type. */
    fun openedFromFilter(animeKey: String) = addToSet("filtered", animeKey)

    /** Adds to a non-playback event counter, such as completed downloads or published reviews. */
    fun record(counter: String, amount: Long = 1) = _state.update {
        it.copy(counters = it.counters + (counter to ((it.counters[counter] ?: 0L) + amount)))
    }.also { save() }

    // --- Internals ---------------------------------------------------------------------------

    private fun addToSet(name: String, value: String) {
        if (_state.value.sets[name]?.contains(value) == true) return
        _state.update { it.copy(sets = it.sets + (name to (it.sets[name].orEmpty() + value))) }
        save()
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
            save()
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
                "watch_seconds" -> lib.watchDays.values.sumOf { it.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                "episode_downloads_completed" -> (state.counters["episode_downloads_completed"] ?: 0).toInt()
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

        // Two passes so the completionist sees unlocks made in the first one.
        repeat(2) {
            for (a in catalog) {
                if (a.id in unlocked) continue
                if (current(a) >= a.trigger.threshold) {
                    unlocked[a.id] = nowMillis()
                    fresh += a
                }
            }
        }
        if (fresh.isNotEmpty()) {
            _state.update { it.copy(unlocked = unlocked) }
            save()
            fresh.forEach { _unlockedNow.tryEmit(it) }
        }
        _progress.value = catalog.map { AchievementProgress(it, current(it), unlocked[it.id]) }
    }

    private fun save() {
        if (!loaded) return
        scope.launch {
            writeLock.withLock { store.write(FILE, AppJson.encodeToString(AchievementState.serializer(), _state.value)) }
        }
    }

    suspend fun flush() = writeLock.withLock { store.write(FILE, AppJson.encodeToString(AchievementState.serializer(), _state.value)) }

    internal companion object {
        const val FILE = "achievements.json"

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
