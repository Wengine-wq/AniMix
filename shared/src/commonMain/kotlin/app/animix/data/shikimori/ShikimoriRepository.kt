package app.animix.data.shikimori

import app.animix.data.cache.ResponseCache
import app.animix.model.Anime
import app.animix.model.AnimeDetail
import app.animix.model.Genre
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
