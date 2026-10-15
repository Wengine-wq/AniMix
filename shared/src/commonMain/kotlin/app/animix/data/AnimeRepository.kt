package app.animix.data

import app.animix.data.shikimori.CatalogQuery
import app.animix.data.shikimori.ShikimoriRepository
import app.animix.data.yummy.TitleMatcher
import app.animix.data.yummy.YummyRepository
import app.animix.model.Anime
import app.animix.model.AnimeDetail
import app.animix.model.AnimeSource
import app.animix.model.Review
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The AniMix catalog: Shikimori as the base, plus titles that only YummyAnime has.
 * Screens talk to this instead of to either source directly.
 */
class AnimeRepository(
    private val shikimori: ShikimoriRepository,
    private val yummy: YummyRepository,
) {
    /** A catalog page. Searches also include YummyAnime-only titles on the first page. */
    suspend fun catalog(query: CatalogQuery, forceRefresh: Boolean = false): List<Anime> = coroutineScope {
        val text = query.search?.trim()
        val extra = if (text != null && query.page == 1) {
            async {
                // Yummy-only results are a bonus: Shikimori results still show if Yummy is down.
                try {
                    yummy.searchOnlyOnYummy(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        } else {
            null
        }
        val base = shikimori.catalog(query, forceRefresh)
        val needle = TitleMatcher.normalize(text.orEmpty())
        val (close, rest) = extra?.await().orEmpty().partition {
            TitleMatcher.normalize(it.title).startsWith(needle) || TitleMatcher.score(needle, it.title) >= 70
        }
        // A Yummy-only title that matches the query well goes first; looser matches go last.
        close + base + rest
    }

    /** Titles similar to this one (Shikimori recommendations; none for YummyAnime-only titles). */
    suspend fun similar(anime: Anime): List<Anime> =
        if (anime.source == AnimeSource.Shikimori) shikimori.similar(anime.id) else emptyList()

    suspend fun yummyReviews(anime: Anime): List<Review> = yummy.reviews(anime)

    suspend fun shikimoriDiscussion(detail: AnimeDetail, page: Int = 1): List<Review> =
        detail.topicId?.let { shikimori.discussion(it, page) }.orEmpty()

    suspend fun detail(anime: Anime, forceRefresh: Boolean = false): AnimeDetail = when (anime.source) {
        AnimeSource.Shikimori -> shikimori.detail(anime.id, forceRefresh)
        AnimeSource.Yummy -> yummy.detail(anime.id, forceRefresh)
    }
}
