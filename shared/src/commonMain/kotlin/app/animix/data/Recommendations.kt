package app.animix.data

import app.animix.data.library.LibraryData
import app.animix.data.library.ListStatus
import app.animix.data.library.tasteGenres
import app.animix.data.shikimori.CatalogOrder
import app.animix.data.shikimori.CatalogQuery
import app.animix.data.shikimori.ShikimoriRepository
import app.animix.model.Anime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * «Подобрано для вас»: the viewer's strongest genres (see [tasteGenres]) and titles similar
 * to their best-liked ones, without anything already in their lists.
 */
class Recommendations(private val shikimori: ShikimoriRepository, private val anime: AnimeRepository) {

    suspend fun forLibrary(library: LibraryData, limit: Int = 24): List<Anime> = coroutineScope {
        val taste = library.tasteGenres().take(3).map { it.first }
        if (taste.isEmpty()) return@coroutineScope emptyList()
        val genreIds = shikimori.genres().filter { it.title in taste }.sortedBy { taste.indexOf(it.title) }.map { it.id }
        val liked = library.entries.values
            .filter { it.favorite || (it.score ?: 0) >= 8 || it.status == ListStatus.Completed }
            .sortedByDescending { (it.score ?: 0) * 10 + it.episodesWatched }
            .take(2)
            .map { it.anime }
        val byGenre = genreIds.map { id -> async { safe { shikimori.catalog(CatalogQuery(genreIds = listOf(id), order = CatalogOrder.Ranked, limit = 30)) } } }
        val bySimilar = liked.map { title -> async { safe { anime.similar(title) } } }
        val lists = (bySimilar + byGenre).awaitAll()
        val known = library.entries.keys
        // Interleave the sources so one genre does not fill the whole shelf.
        val merged = LinkedHashMap<String, Anime>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (index in 0 until longest) {
            for (list in lists) {
                val item = list.getOrNull(index) ?: continue
                if (item.key !in known && item.title.isNotBlank()) merged.putIfAbsent(item.key, item)
            }
        }
        merged.values.take(limit)
    }

    private suspend fun safe(block: suspend () -> List<Anime>): List<Anime> = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        emptyList()
    }
}
