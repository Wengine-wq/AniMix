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
    suspend fun catalog(query: CatalogQuery, forceRefresh: Boolean = false, onBase: ((List<Anime>) -> Unit)? = null): List<Anime> = coroutineScope {
        val text = query.search?.trim()
        val extra = if (text != null && query.page == 1) {
            async {
                // Yummy completes the catalog. A slow response must still join the same search;
                // use the shared HTTP retry/timeout policy, with no shorter source-specific cutoff.
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
        // Show the ready base immediately, then merge Yummy's complementary results when ready.
        onBase?.invoke(base)
        val needle = TitleMatcher.normalize(text.orEmpty())
        val (close, rest) = extra?.await().orEmpty().partition {
            TitleMatcher.normalize(it.title).startsWith(needle) || TitleMatcher.score(needle, it.title) >= 70
        }
        // A Yummy-only title that matches the query well goes first; looser matches go last.
        close + base + rest
    }

    /**
     * «Похожее»: Shikimori's candidates with their genres, re-ranked by [TitleLinks.rankSimilar] against
     * this title, without its own franchise and the viewer's dropped titles. None for YummyAnime-only titles.
     */
    suspend fun similar(anime: Anime, library: app.animix.data.library.LibraryData? = null): List<Anime> {
        if (anime.source != AnimeSource.Shikimori) return emptyList()
        val candidates = shikimori.similar(anime.id)
        if (candidates.isEmpty()) return emptyList()
        val detail = runCatching { shikimori.detail(anime.id) }.getOrElse { if (it is CancellationException) throw it; null }
        // The recommendations come without genres; one request by ids brings them for the first thirty.
        val withGenres = runCatching { shikimori.byIds(candidates.take(30).map { it.id }) }
            .getOrElse { if (it is CancellationException) throw it; emptyList() }.associateBy { it.id }
        val pool = candidates.map { withGenres[it.id] ?: it }
        val base = detail?.anime?.takeIf { it.genres.isNotEmpty() } ?: anime
        return TitleLinks.rankSimilar(base, pool, franchise = detail?.related.orEmpty().map { it.key }.toSet(), library = library)
    }

    suspend fun yummyReviews(anime: Anime): List<Review> = yummy.reviews(anime)

    suspend fun shikimoriDiscussion(detail: AnimeDetail, page: Int = 1): List<Review> =
        detail.topicId?.let { shikimori.discussion(it, page) }.orEmpty()

    suspend fun detail(anime: Anime, forceRefresh: Boolean = false): AnimeDetail = when (anime.source) {
        AnimeSource.Shikimori -> shikimori.detail(anime.id, forceRefresh)
        AnimeSource.Yummy -> yummy.detail(anime.id, forceRefresh)
    }
}
