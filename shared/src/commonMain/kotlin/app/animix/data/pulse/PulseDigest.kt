package app.animix.data.pulse

import app.animix.data.Recommendations
import app.animix.data.library.Library
import app.animix.data.library.ListStatus
import app.animix.data.library.tasteGenres
import app.animix.data.shikimori.CatalogOrder
import app.animix.data.shikimori.CatalogQuery
import app.animix.data.shikimori.ShikimoriRepository
import app.animix.model.Anime
import app.animix.model.AnimeSource
import app.animix.model.AnimeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Collects what the week's digest needs from the catalog: fresh cards of «Смотрю» (one request), the
 * next air date of the closest ongoing titles, ongoing titles in the viewer's genres and weekly picks.
 * Every part may fail on its own; the digest then shows what it has.
 */
class PulseDigest(private val shikimori: ShikimoriRepository, private val recommendations: Recommendations, private val library: Library) {
    suspend fun build(): WeeklyDigest = coroutineScope {
        val data = library.state.value
        val watching = data.entries.values.filter { it.status == ListStatus.Watching || it.status == ListStatus.OnHold }.map { it.anime }
        val cards = async { safe { shikimori.byIds(watching.filter { it.source == AnimeSource.Shikimori }.map { it.id }) }.associateBy { it.key } }
        val taste = data.tasteGenres().take(2).map { it.first }
        val ongoing = async {
            safe {
                val ids = shikimori.genres().filter { it.title in taste }.map { it.id }
                shikimori.catalog(CatalogQuery(status = "ongoing", order = CatalogOrder.Ranked, genreIds = ids.take(1), limit = 30))
            }.filter { it.key !in data.entries.keys && it.title.isNotBlank() }.take(10)
        }
        val picks = async { safe { recommendations.forLibrary(data, 10) } }
        val fresh = cards.await()
        // Air dates only for the few closest ongoing titles: one detail request each, cached for half an hour.
        val next = watching.mapNotNull { fresh[it.key] }.filter { it.status == AnimeStatus.Ongoing }.take(5).map { anime ->
            async { anime.key to runCatching { shikimori.detail(anime.id).nextEpisodeAt }.getOrNull() }
        }.mapNotNull { it.await().let { (key, at) -> at?.let { key to it } } }.toMap()
        digestFrom(data, fresh, nextEpisodes = next, ongoingForYou = ongoing.await(), picks = picks.await())
    }

    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> = try { block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { emptyList() }
}
