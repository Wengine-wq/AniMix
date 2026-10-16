package app.animix.data.skips

import app.animix.core.AppJson
import app.animix.core.DiskStore
import app.animix.core.nowMillis
import app.animix.model.Anime
import app.animix.model.AnimeSource
import app.animix.model.Episode
import app.animix.model.SkipRange
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** One opening or ending submitted to AniSkip, with the length of the release it was timed on. */
@Serializable
data class TimedSkip(val start: Double, val end: Double, val episodeLength: Double? = null)

/** Everything known about one episode's skips, kept on the device for offline viewing. */
@Serializable
data class EpisodeSkips(
    val openings: List<TimedSkip> = emptyList(),
    val endings: List<TimedSkip> = emptyList(),
    val checkedAt: Long = 0,
)

/** The ranges a player should use for one episode of a known length. */
data class SkipPlan(val opening: SkipRange?, val ending: SkipRange?)

/**
 * Opening and ending times. YummyAnime times come with the episode and win;
 * AniSkip (by MAL id, which Shikimori shares) fills in what they lack, for old and new titles.
 * Every answer is stored on disk, so downloaded episodes can skip without a network.
 */
class SkipTimes(
    private val http: HttpClient,
    private val store: DiskStore,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private var entries: Map<String, EpisodeSkips>? = null

    private suspend fun all(): Map<String, EpisodeSkips> = lock.withLock {
        entries ?: (store.read(FILE)?.let { text ->
            runCatching { AppJson.decodeFromString(serializer, text) }.getOrNull()
        } ?: emptyMap()).also { entries = it }
    }

    /** Ranges for [episode], from the network when possible and from the device otherwise. */
    suspend fun plan(anime: Anime, episode: Episode, durationSeconds: Double?): SkipPlan {
        val known = SkipPlan(episode.opening, episode.ending)
        if (known.opening != null && known.ending != null) return known
        val stored = fetch(anime, episode.number) ?: return known
        return SkipPlan(
            opening = known.opening ?: stored.openings.bestFor(durationSeconds),
            ending = known.ending ?: stored.endings.bestFor(durationSeconds),
        )
    }

    /** Fetches and stores skips ahead of time, e.g. for episodes queued for download. */
    fun prefetch(anime: Anime, episodes: List<Episode>) {
        if (!anime.hasMalId) return
        scope.launch {
            for (episode in episodes) {
                if (episode.opening != null && episode.ending != null) continue
                try { fetch(anime, episode.number) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
            }
        }
    }

    private suspend fun fetch(anime: Anime, number: String): EpisodeSkips? {
        if (!anime.hasMalId) return null
        val episode = number.toDoubleOrNull()?.takeIf { it >= 1 && it == floor(it) }?.toInt() ?: return null
        val key = "${anime.id}:$episode"
        val stored = all()[key]
        val age = nowMillis() - (stored?.checkedAt ?: 0)
        val complete = stored != null && stored.openings.isNotEmpty() && stored.endings.isNotEmpty()
        // Fresh titles get their times submitted later, so look again after a while.
        if (stored != null && age < if (complete) COMPLETE_TTL else MISSING_TTL) return stored
        val fetched = try {
            val response = http.get(
                "https://api.aniskip.com/v2/skip-times/${anime.id}/$episode" +
                    "?types=op&types=ed&types=mixed-op&types=mixed-ed&episodeLength=0",
            ) { expectSuccess = false }
            when {
                response.status == HttpStatusCode.NotFound -> EpisodeSkips(checkedAt = nowMillis())
                !response.status.isSuccess() -> return stored
                else -> response.body<AniSkipResponse>().toSkips()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return stored
        }
        lock.withLock {
            val updated = (entries ?: emptyMap()) + (key to fetched)
            entries = updated
            store.write(FILE, AppJson.encodeToString(serializer, updated))
        }
        return fetched
    }

    @Serializable
    private class AniSkipInterval(val startTime: Double = 0.0, val endTime: Double = 0.0)

    @Serializable
    private class AniSkipResult(val interval: AniSkipInterval? = null, val skipType: String = "", val episodeLength: Double? = null)

    @Serializable
    private class AniSkipResponse(val found: Boolean = false, val results: List<AniSkipResult> = emptyList()) {
        fun toSkips(): EpisodeSkips {
            val timed = results.mapNotNull { result ->
                val interval = result.interval ?: return@mapNotNull null
                if (interval.endTime <= interval.startTime || interval.startTime < 0) return@mapNotNull null
                result.skipType to TimedSkip(interval.startTime, interval.endTime, result.episodeLength?.takeIf { it > 0 })
            }
            return EpisodeSkips(
                openings = timed.filter { it.first.endsWith("op") }.map { it.second },
                endings = timed.filter { it.first.endsWith("ed") }.map { it.second },
                checkedAt = nowMillis(),
            )
        }
    }

    private companion object {
        const val FILE = "skips.json"
        const val COMPLETE_TTL = 30L * 24 * 60 * 60 * 1000
        const val MISSING_TTL = 24L * 60 * 60 * 1000
        val serializer = MapSerializer(String.serializer(), EpisodeSkips.serializer())
    }
}

/** Shikimori ids are MyAnimeList ids, which AniSkip is keyed by. */
private val Anime.hasMalId: Boolean get() = source == AnimeSource.Shikimori && id > 0

/**
 * The submission timed on a release closest in length to the one playing.
 * Times from a release more than 15 s longer or shorter would land in the wrong place.
 */
internal fun List<TimedSkip>.bestFor(durationSeconds: Double?): SkipRange? {
    val candidates = if (durationSeconds == null || durationSeconds <= 0) this
    else filter { it.episodeLength == null || abs(it.episodeLength - durationSeconds) <= 15 }
        .sortedBy { skip -> skip.episodeLength?.let { abs(it - durationSeconds) } ?: 15.0 }
    val best = candidates.firstOrNull() ?: return null
    val start = floor(best.start).toInt()
    val end = ceil(durationSeconds?.takeIf { it > 0 }?.let { minOf(best.end, it) } ?: best.end).toInt()
    return if (end > start) SkipRange(start, end - start) else null
}
