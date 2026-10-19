package app.animix.data.watchflow

import app.animix.data.AnimeRepository
import app.animix.data.downloads.DownloadManager
import app.animix.data.downloads.DownloadStatus
import app.animix.data.library.Library
import app.animix.data.skips.SkipTimes
import app.animix.data.yummy.YummyRepository
import app.animix.model.Anime
import app.animix.model.Episode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class FlowDubbing(val key: String, val studio: String, val episodes: List<Episode>, val offlineOnly: Boolean = false)
data class FlowSource(val dubbings: List<FlowDubbing>, val fallbackDurationMs: Long)

class FlowPlanner(
    private val yummy: YummyRepository,
    private val animeRepository: AnimeRepository,
    private val downloads: DownloadManager,
    private val skips: SkipTimes,
    private val library: Library,
) {
    suspend fun source(anime: Anime): FlowSource {
        val saved = downloads.state.value.filter { it.anime.key == anime.key && it.status == DownloadStatus.Completed && downloads.offlineUri(it) != null }
        val online = try {
            if (saved.isNotEmpty()) withTimeoutOrNull(2500) { yummy.watchSource(anime) } else yummy.watchSource(anime)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { if (saved.isEmpty()) throw error else null }
        val dubbings = online?.dubbings.orEmpty().filter { it.isKodik }
            .map { FlowDubbing(it.key, it.studio, it.episodes) }.toMutableList()
        saved.groupBy { it.dubbingKey }.forEach { (key, items) ->
            if (dubbings.none { it.key == key }) dubbings += FlowDubbing(key, items.first().studio,
                items.map { it.episode }.distinctBy { it.number }.sortedBy { it.sortKey }, offlineOnly = true)
        }
        val fallback = if (dubbings.any { dubbing -> dubbing.episodes.any { (it.durationSeconds ?: 0) <= 0 } }) {
            try { withTimeoutOrNull(2500) { animeRepository.detail(anime).durationMinutes?.takeIf { it > 0 }?.times(60_000L) } }
            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        } else null
        return FlowSource(dubbings, fallback ?: 24 * 60_000L)
    }

    suspend fun prepare(anime: Anime, source: FlowSource, dubbing: FlowDubbing, from: String,
        options: FlowOptions, existing: List<FlowEpisode>, fitBudget: Boolean): List<FlowEpisode> {
        val index = dubbing.episodes.indexOfFirst { it.number == from }
        if (index < 0) return emptyList()
        var remaining = (options.limitMs - existing.sumOf { flowDuration(it, options) }).coerceAtLeast(0)
        val result = mutableListOf<FlowEpisode>()
        val candidates = dubbing.episodes.drop(index).let { episodes ->
            if (fitBudget) episodes.filterNot { episode -> existing.any { it.key == "${anime.key}|${dubbing.key}|${episode.number}" } }.take(100)
            else episodes.take(1)
        }
        for (episode in candidates) {
            val key = "${anime.key}|${dubbing.key}|${episode.number}"
            if (existing.any { it.key == key }) continue
            val downloaded = downloads.state.value.firstOrNull {
                it.anime.key == anime.key && it.dubbingKey == dubbing.key && it.episode.number == episode.number &&
                    it.status == DownloadStatus.Completed && downloads.offlineUri(it) != null
            }
            val duration = episode.durationSeconds?.takeIf { it > 0 }?.times(1000L) ?: source.fallbackDurationMs
            val plan = if ((options.skipOpenings && episode.opening == null) || (options.skipEndings && episode.ending == null)) {
                withTimeoutOrNull(2500) { skips.plan(anime, episode, duration / 1000.0) }
            } else null
            val enriched = episode.copy(opening = plan?.opening ?: episode.opening, ending = plan?.ending ?: episode.ending)
            val savedPosition = library.state.value.entry(anime)?.episodePositions?.get(episode.number) ?: 0
            val end = enriched.ending?.start?.times(1000L)?.takeIf { it > duration / 2 } ?: duration * 92 / 100
            val start = savedPosition.takeIf { it >= 5000 && it < end } ?: 0
            val item = FlowEpisode(anime, dubbing.key, dubbing.studio, enriched, duration,
                estimated = (episode.durationSeconds ?: 0) <= 0, startPositionMs = start, downloadId = downloaded?.id)
            val length = flowDuration(item, options)
            if (fitBudget && length > remaining) break
            result += item
            remaining -= length
        }
        return result
    }
}
