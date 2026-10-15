package app.animix.data.yummy

import app.animix.data.cache.ResponseCache
import app.animix.model.Anime
import app.animix.model.Dubbing
import app.animix.model.Episode
import app.animix.model.SkipRange
import app.animix.model.WatchSource
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class WatchSourceNotFound(title: String) : Exception("«$title» не найдено на YummyAnime")

/** Finds where an anime from Shikimori can be watched on YummyAnime. */
class YummyRepository internal constructor(
    private val api: YummyApi,
    private val cache: ResponseCache,
) {
    suspend fun watchSource(anime: Anime, forceRefresh: Boolean = false): WatchSource =
        cache.get("yummy:source:${anime.id}", ttl = 15.minutes, WatchSource.serializer(), forceRefresh) {
            val release = findRelease(anime)
            WatchSource(
                yummyId = release.anime_id,
                title = release.title,
                dubbings = groupDubbings(api.videos(release.anime_id)),
            )
        }

    /** The YummyAnime release for a Shikimori anime, remembered for a month once found. */
    private suspend fun findRelease(anime: Anime): YummyRelease =
        cache.get("yummy:release:${anime.id}", ttl = 30.days, YummyRelease.serializer()) {
            searchRelease(anime) ?: throw WatchSourceNotFound(anime.title)
        }

    private suspend fun searchRelease(anime: Anime): YummyRelease? {
        val titles = listOfNotNull(anime.title, anime.originalTitle, anime.englishTitle).distinct()
        var bestGuess: Pair<YummyRelease, Int>? = null
        var lastError: Throwable? = null
        var searched = false
        for (query in titles) {
            if (TitleMatcher.normalize(query).isBlank()) continue
            val results = try {
                api.search(query).also { searched = true }
            } catch (error: Exception) {
                lastError = error
                continue
            }
            // Yummy links most releases to Shikimori directly; that match is exact.
            results.firstOrNull { it.remote_ids?.shikimori_id == anime.id }?.let { return it }
            for (release in results) {
                val score = TitleMatcher.best(titles, listOf(release.title))
                val yearMatches = anime.year == null || release.year == null || anime.year == release.year
                if (score >= 90 && yearMatches && score > (bestGuess?.second ?: 0)) bestGuess = release to score
            }
        }
        // Every search failed: report the network problem rather than "not found".
        if (!searched && lastError != null) throw lastError
        return bestGuess?.first
    }

    internal companion object {
        /**
         * Groups Yummy's flat video list into one entry per (player, studio).
         * The same studio served by two players stays as two entries: their
         * episode URLs are not interchangeable.
         */
        fun groupDubbings(videos: List<YummyVideo>): List<Dubbing> {
            val groups = LinkedHashMap<String, MutableMap<String, Episode>>()
            val names = HashMap<String, Pair<String, String>>()
            for (video in videos) {
                val url = video.iframe_url?.trim().orEmpty()
                if (url.isEmpty()) continue
                val studio = video.data?.dubbing?.cleanPrefix("Озвучка ")?.ifBlank { null } ?: "Неизвестная озвучка"
                val player = video.data?.player?.cleanPrefix("Плеер ")?.ifBlank { null } ?: "Плеер"
                val key = "${player.lowercase()}|${studio.lowercase()}"
                names.getOrPut(key) { studio to player }
                val number = video.number?.trim()?.ifEmpty { null } ?: "1"
                groups.getOrPut(key) { LinkedHashMap() }.getOrPut(number) {
                    Episode(
                        number = number,
                        videoId = video.video_id,
                        iframeUrl = if (url.startsWith("//")) "https:$url" else url,
                        durationSeconds = video.duration?.takeIf { it > 0 },
                        opening = video.skips?.opening?.toRange(),
                        ending = video.skips?.ending?.toRange(),
                    )
                }
            }
            return groups.map { (key, episodes) ->
                val (studio, player) = names.getValue(key)
                Dubbing(studio = studio, player = player, episodes = episodes.values.sortedBy { it.sortKey })
            }.sortedWith(
                compareByDescending<Dubbing> { it.isKodik }.thenByDescending { it.episodes.size },
            )
        }

        private fun String.cleanPrefix(prefix: String) = trim().removePrefix(prefix).trim()

        private fun YummySkip.toRange(): SkipRange? {
            val start = time ?: return null
            val len = length ?: return null
            return if (start >= 0 && len > 0) SkipRange(start, len) else null
        }
    }
}
