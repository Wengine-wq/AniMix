package app.animix.data.shikimori

import app.animix.data.cache.ResponseCache
import app.animix.model.Anime
import app.animix.model.AnimeDetail
import app.animix.model.AnimeTopic
import app.animix.model.Genre
import app.animix.model.Review
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ShikimoriRepository internal constructor(
    private val api: ShikimoriApi,
    private val cache: ResponseCache,
) {
    suspend fun catalog(query: CatalogQuery, forceRefresh: Boolean = false): List<Anime> =
        cache.get(query.cacheKey, ttl = 15.minutes, ListSerializer(Anime.serializer()), forceRefresh) {
            api.animes(query).map { it.toAnime() }.filter { it.id > 0 }
        }

    suspend fun detail(id: Int, forceRefresh: Boolean = false): AnimeDetail =
        cache.get("anime:$id", ttl = 30.minutes, AnimeDetail.serializer(), forceRefresh) {
            api.anime(id)?.toDetail() ?: throw ShikimoriException("Аниме не найдено")
        }

    /** Live cards for known ids, 50 per request; unknown ids are simply missing from the result. */
    suspend fun byIds(ids: List<Int>): List<Anime> =
        ids.distinct().filter { it > 0 }.chunked(50).flatMap { chunk -> api.animesByIds(chunk).map { it.toAnime() }.filter { it.id > 0 } }

    /** Digest snapshots must distinguish a live catalog response from stale offline cache. */
    suspend fun liveDetail(id: Int): AnimeDetail = api.anime(id)?.toDetail()
        ?: throw ShikimoriException("Аниме не найдено")

    /**
     * Dated news and events of one anime, newest first, always live. Either endpoint may fail on its own;
     * only a failure of both is an error.
     */
    suspend fun topics(id: Int, limit: Int = 15): List<AnimeTopic> {
        val events = runCatching { api.animeTopics(id, limit) }
        val news = runCatching { api.newsTopics(id, limit) }
        listOf(events, news).forEach { (it.exceptionOrNull() as? kotlinx.coroutines.CancellationException)?.let { cancel -> throw cancel } }
        if (events.isFailure && news.isFailure) throw events.exceptionOrNull()!!
        return (events.getOrDefault(emptyList()) + news.getOrDefault(emptyList()))
            .filter { it.id > 0 }.distinctBy { it.id }.map { it.toTopic() }.sortedByDescending { it.createdAt }
    }

    /**
     * News of the week for AniPulse: announcements, trailers, premiere dates and other news from the
     * Shikimori feed, the last eight days, cached for half an hour.
     */
    suspend fun weeklyNews(): List<app.animix.model.NewsItem> =
        cache.get("news:week", ttl = 30.minutes, ListSerializer(app.animix.model.NewsItem.serializer())) {
            val cutoff = kotlin.time.Clock.System.now().toEpochMilliseconds() - 8L * 86_400_000
            (1..2).flatMap { page -> runCatching { api.newsFeed(page, 30) }.getOrDefault(emptyList()) }
                .distinctBy { it.id }.mapNotNull { it.toNews() }
                .filter { item -> runCatching { kotlin.time.Instant.parse(item.createdAt).toEpochMilliseconds() >= cutoff }.getOrDefault(true) }
        }

    suspend fun similar(id: Int): List<Anime> =
        cache.get("similar:$id", ttl = 6.hours, ListSerializer(Anime.serializer())) {
            api.similar(id).map { it.toAnime() }.filter { it.id > 0 }
        }

    suspend fun discussion(topicId: Int, page: Int = 1): List<Review> =
        cache.get("comments:$topicId:$page", ttl = 10.minutes, ListSerializer(Review.serializer())) {
            api.comments(topicId, page, limit = 20).filter { !it.is_offtopic }.map { it.toReview() }.filterNot { it.body.isBlank }
        }

    suspend fun genres(): List<Genre> =
        cache.get("genres", ttl = 24.hours, ListSerializer(Genre.serializer())) {
            api.genres().mapNotNull { it.toGenre() }.sortedBy { it.title }
        }

    suspend fun ongoing(forceRefresh: Boolean = false): List<Anime> =
        catalog(CatalogQuery(status = "ongoing", limit = 20), forceRefresh)

    suspend fun popular(forceRefresh: Boolean = false): List<Anime> =
        catalog(CatalogQuery(kinds = listOf("tv", "movie"), limit = 20), forceRefresh)

    suspend fun topRated(forceRefresh: Boolean = false): List<Anime> =
        catalog(CatalogQuery(order = CatalogOrder.Ranked, kinds = listOf("tv", "movie"), limit = 20), forceRefresh)

    suspend fun search(text: String): List<Anime> = catalog(CatalogQuery(search = text, limit = 30))
}
