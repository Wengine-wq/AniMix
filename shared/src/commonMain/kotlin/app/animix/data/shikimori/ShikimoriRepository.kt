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
