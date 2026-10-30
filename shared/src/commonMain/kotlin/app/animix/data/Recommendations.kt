package app.animix.data

import app.animix.data.library.LibraryData
import app.animix.data.library.ListStatus
import app.animix.data.library.tasteGenres
import app.animix.data.shikimori.CatalogOrder
import app.animix.data.shikimori.CatalogQuery
import app.animix.data.shikimori.ShikimoriRepository
import app.animix.model.Anime
import app.animix.model.AnimeKind
import app.animix.model.AnimeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A title found for «Подобрано для вас», with the liked title it resembles, if any. */
data class PickCandidate(val anime: Anime, val similarTo: Anime? = null)

/**
 * «Подобрано для вас»: titles similar to the best-liked ones and the best of the viewer's strongest
 * genres, ranked by how well they fit the whole taste (see [rankPicks]), without anything already listed.
 */
class Recommendations(private val shikimori: ShikimoriRepository, private val anime: AnimeRepository) {

    suspend fun forLibrary(library: LibraryData, limit: Int = 24): List<Anime> = coroutineScope {
        val taste = library.tasteGenres().take(3).map { it.first }
        if (taste.isEmpty()) return@coroutineScope emptyList()
        val genreIds = shikimori.genres().filter { it.title in taste }.sortedBy { taste.indexOf(it.title) }.map { it.id }
        val liked = library.entries.values
            .filter { it.status != ListStatus.Dropped && (it.favorite || (it.score ?: 0) >= 8 || it.status == ListStatus.Completed) }
            .sortedByDescending { (it.score ?: 0) * 10 + it.episodesWatched + if (it.favorite) 40 else 0 }
            .take(4)
            .map { it.anime }
        val byGenre = genreIds.map { id -> async { safe { shikimori.catalog(CatalogQuery(genreIds = listOf(id), order = CatalogOrder.Ranked, limit = 30)).map(::PickCandidate) } } }
        val bySimilar = liked.map { title -> async { safe { anime.similar(title).map { PickCandidate(it, title) } } } }
        rankPicks(library, (bySimilar + byGenre).awaitAll().flatten(), limit)
    }

    private suspend fun safe(block: suspend () -> List<PickCandidate>): List<PickCandidate> = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Scores every candidate against the whole taste instead of taking sources in turn: genre fit
 * (weighted by [tasteGenres], dropped titles count against), resemblance to several liked titles,
 * and the catalog score. Announcements, music clips, adult titles and anything listed are left out;
 * no more than three picks resemble the same liked title, so one favourite cannot fill the shelf.
 */
fun rankPicks(library: LibraryData, candidates: List<PickCandidate>, limit: Int = 24): List<Anime> {
    val taste = library.tasteGenres()
    val strongest = taste.firstOrNull()?.second?.toDouble()?.takeIf { it > 0 } ?: return emptyList()
    val weights = taste.associate { (genre, weight) -> genre to weight / strongest }
    val disliked = library.entries.values.filter { it.status == ListStatus.Dropped }.flatMap { it.anime.genres }
        .groupingBy { it }.eachCount()
    val listed = library.entries.keys
    val grouped = candidates.filter { candidate ->
        val a = candidate.anime
        a.title.isNotBlank() && a.key !in listed && a.status != AnimeStatus.Announced && a.kind != AnimeKind.Other &&
            a.genres.none { it in AdultGenres } && (a.score ?: 7.0) >= 6.0
    }.groupBy { it.anime.key }
    val scored = grouped.values.map { same ->
        val a = same.first().anime
        val fit = a.genres.sumOf { weights[it] ?: 0.0 } / maxOf(a.genres.size, 3) * 3
        val dislike = a.genres.sumOf { (disliked[it] ?: 0).coerceAtMost(3) * 0.15 }
        val resemblance = same.mapNotNull { it.similarTo?.key }.distinct().size
        val quality = ((a.score ?: 7.0) - 7.0) * 0.6
        Triple(a, fit + resemblance * 1.2 + quality - dislike, same.firstOrNull { it.similarTo != null }?.similarTo?.key)
    }.sortedByDescending { it.second }
    val perSource = HashMap<String, Int>()
    return scored.filter { (_, _, source) ->
        source == null || (perSource[source] ?: 0).let { count -> perSource[source] = count + 1; count < 3 }
    }.take(limit).map { it.first }
}

private val AdultGenres = setOf("Хентай", "Эротика", "Hentai", "Erotica")
