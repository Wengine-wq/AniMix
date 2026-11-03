package app.animix.data.notify

import app.animix.core.AppJson
import app.animix.core.AppLog
import app.animix.core.AppSettings
import app.animix.core.DiskStore
import app.animix.core.TrustedClock
import app.animix.data.library.Library
import app.animix.data.library.ListStatus
import app.animix.model.Anime
import app.animix.model.AnimeSource
import app.animix.model.AnimeStatus
import app.animix.model.WatchSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** One notice in the bell: a new episode of a title from «Смотрю», aired or out in the chosen dubbing. */
@Serializable
data class EpisodeNotice(
    val id: String,
    val anime: Anime,
    val episode: Int,
    /** The studio when the notice is about the chosen dubbing; null for a newly aired episode. */
    val dubbing: String? = null,
    val at: Long,
    val read: Boolean = false,
)

/** What was last seen per title: episodes aired and episodes in the chosen dubbing. */
@Serializable
private data class Seen(val aired: Int = 0, val dubbed: Int = 0, val dubbingKey: String? = null, val dubbingCheckedAt: Long = 0)

@Serializable
private data class NotifierState(val seen: Map<String, Seen> = emptyMap(), val notices: List<EpisodeNotice> = emptyList(), val checkedAt: Long = 0)

/**
 * «Уведомления»: watches the ongoing titles in «Смотрю». One request reads fresh cards for all of them;
 * the chosen dubbing of each is looked up at most every two hours. The first look only remembers the
 * counts, so turning notifications on never floods the bell with old episodes.
 */
class EpisodeNotifier(
    private val store: DiskStore,
    private val library: Library,
    private val settings: AppSettings,
    private val cards: suspend (List<Int>) -> List<Anime>,
    private val source: suspend (Anime) -> WatchSource,
    private val post: (EpisodeNotice) -> Unit,
) {
    private val lock = Mutex()
    private var state = NotifierState()
    private var loaded = false
    private val mutable = MutableStateFlow<List<EpisodeNotice>>(emptyList())
    val notices: StateFlow<List<EpisodeNotice>> = mutable.asStateFlow()

    private suspend fun load() {
        if (loaded) return
        state = store.readChecked(FILE)?.let { runCatching { AppJson.decodeFromString<NotifierState>(it) }.getOrNull() } ?: NotifierState()
        mutable.value = state.notices
        loaded = true
    }

    suspend fun start() = lock.withLock { load() }

    /** Checks now unless the last check was less than [minIntervalMs] ago. Returns the new notices. */
    suspend fun check(minIntervalMs: Long = 30 * 60_000L): List<EpisodeNotice> = lock.withLock {
        load()
        val now = TrustedClock.now()
        val prefs = settings.state.value
        if (!prefs.notifyNewEpisodes || now - state.checkedAt in 0 until minIntervalMs) return@withLock emptyList()
        val watching = library.state.value.entries.values.filter { it.status == ListStatus.Watching }
            .map { it.anime }.filter { it.status != AnimeStatus.Released }
        val fresh = runCatching { cards(watching.filter { it.source == AnimeSource.Shikimori }.map { it.id }) }
            .onFailure { AppLog.failure("Notify", "cards", it) }.getOrDefault(emptyList()).associateBy { it.key }
        val added = mutableListOf<EpisodeNotice>()
        val seen = state.seen.toMutableMap()
        for (old in watching) {
            val anime = fresh[old.key] ?: old
            if (anime.status == AnimeStatus.Released && old.status != AnimeStatus.Ongoing) continue
            val before = seen[anime.key]
            var current = before ?: Seen(aired = anime.episodesAired)
            if (before != null && anime.episodesAired > before.aired) {
                added += EpisodeNotice("${anime.key}:${anime.episodesAired}", anime, anime.episodesAired, null, now)
            }
            current = current.copy(aired = maxOf(current.aired, anime.episodesAired))
            val dubbingKey = prefs.dubbingByAnime[anime.key]
            if (prefs.notifyDubbing && dubbingKey != null && now - current.dubbingCheckedAt !in 0 until DUBBING_INTERVAL) {
                val count = runCatching { source(anime).dubbings.firstOrNull { it.key == dubbingKey } }
                    .onFailure { AppLog.failure("Notify", "dubbing", it) }.getOrNull()
                    ?.let { dubbing -> dubbing.studio to (dubbing.episodes.mapNotNull { it.number.toDoubleOrNull()?.toInt() }.maxOrNull() ?: 0) }
                if (count != null) {
                    val (studio, episodes) = count
                    if (current.dubbingKey == dubbingKey && episodes > current.dubbed) {
                        added += EpisodeNotice("${anime.key}:$dubbingKey:$episodes", anime, episodes, studio, now)
                    }
                    current = current.copy(dubbed = if (current.dubbingKey == dubbingKey) maxOf(current.dubbed, episodes) else episodes,
                        dubbingKey = dubbingKey, dubbingCheckedAt = now)
                }
            }
            seen[anime.key] = current
        }
        // Titles that left «Смотрю» are forgotten; the bell keeps the last 50 notices.
        val keys = watching.map { it.key }.toSet()
        val notices = (added + state.notices).distinctBy { it.id }.take(50)
        state = NotifierState(seen.filterKeys { it in keys }, notices, now)
        mutable.value = notices
        store.writeChecked(FILE, AppJson.encodeToString(NotifierState.serializer(), state))
        added.forEach(post)
        added
    }

    /** The bell was opened: everything shown counts as read. */
    suspend fun markRead() = lock.withLock {
        load()
        if (state.notices.none { !it.read }) return@withLock
        state = state.copy(notices = state.notices.map { it.copy(read = true) })
        mutable.value = state.notices
        store.writeChecked(FILE, AppJson.encodeToString(NotifierState.serializer(), state))
    }

    private companion object {
        const val FILE = "episode-notices.json"
        const val DUBBING_INTERVAL = 2 * 60 * 60_000L
    }
}
