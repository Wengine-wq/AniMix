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

/** The filters of «Умный подбор»: genres that must all be present, kind, era, length and only finished titles. */
@kotlinx.serialization.Serializable
data class PickFilters(
    val genres: List<String> = emptyList(),
    /** "tv", "movie", "short" (OVA, ONA, specials) or null for any. */
    val kind: String? = null,
    /** "new" (2020 on), "2010s", "classic" (before 2010) or null. */
    val era: String? = null,
    /** "short" (up to 13), "season" (14–26), "long" (more) or null. */
    val length: String? = null,
    val finished: Boolean = false,
)

/** A personal pick and, in a few words, why it is here. */
data class Pick(val anime: Anime, val reason: String?)

/** A title found for «Подобрано для вас», with the liked title it resembles, if any. */
data class PickCandidate(val anime: Anime, val similarTo: Anime? = null)

/**
 * «Подобрано для вас»: titles similar to the best-liked ones and the best of the viewer's strongest
 * genres, ranked by how well they fit the whole taste (see [rankPicks]), without anything already listed.
 */
class Recommendations(private val shikimori: ShikimoriRepository, private val anime: AnimeRepository) {

    suspend fun forLibrary(library: LibraryData, limit: Int = 24): List<Anime> = personal(library, limit).map { it.anime }

    /**
     * «Подобрано для вас» with the reason of each pick. Seeds are the titles that say most about the
     * viewer — favourites, high scores, finished and recently watched series, the recent ones weighing
     * more — and each brings its similar titles. Genre pairs of the taste («Романтика + Школа») find
     * the specific rather than the overall top of one genre. Everything is ranked against the whole taste.
     */
    suspend fun personal(library: LibraryData, limit: Int = 24): List<Pick> = coroutineScope {
        val taste = library.tasteGenres().take(4).map { it.first }
        if (taste.isEmpty()) return@coroutineScope emptyList()
        val genres = safeGenres()
        fun ids(names: List<String>) = names.mapNotNull { name -> genres.firstOrNull { it.title == name }?.id }
        val newest = library.entries.values.maxOfOrNull { it.updatedAt } ?: 0L
        val seeds = library.entries.values
            .filter { it.status != ListStatus.Dropped && it.anime.title.isNotBlank() &&
                (it.favorite || (it.score ?: 0) >= 8 || it.status == ListStatus.Completed || it.episodesWatched >= 3) }
            .sortedByDescending { entry ->
                val recency = ((entry.updatedAt - newest + 60L * 86_400_000) / 86_400_000).coerceIn(0, 60)
                (entry.score ?: 6) * 6 + (if (entry.favorite) 30 else 0) + entry.episodesWatched.coerceAtMost(24) + recency
            }.take(8).map { it.anime }
        val pairs = taste.take(3).let { top -> listOfNotNull(top.getOrNull(0)?.let { a -> top.getOrNull(1)?.let { listOf(a, it) } },
            top.getOrNull(0)?.let { a -> top.getOrNull(2)?.let { listOf(a, it) } }, top.getOrNull(1)?.let { a -> top.getOrNull(2)?.let { listOf(a, it) } }) }
        val byPair = pairs.map { pair -> async { safe {
            val genreIds = ids(pair)
            if (genreIds.size < 2) emptyList()
            else shikimori.catalog(CatalogQuery(genreIds = genreIds, order = CatalogOrder.Ranked, limit = 30)).map(::PickCandidate)
        } } }
        val byGenre = ids(taste.take(1)).map { id -> async { safe { shikimori.catalog(CatalogQuery(genreIds = listOf(id), order = CatalogOrder.Ranked, limit = 30)).map(::PickCandidate) } } }
        val bySimilar = seeds.map { title -> async { safe { anime.similar(title).map { PickCandidate(it, title) } } } }
        val ranked = varied(rankPickCandidates(library, (bySimilar + byPair + byGenre).awaitAll().flatten(), limit))
        ranked.map { candidate ->
            val matching = candidate.anime.genres.filter { it in taste }.take(2)
            Pick(candidate.anime, candidate.similarTo?.let { "Похоже на «${it.title}»" }
                ?: matching.takeIf { it.isNotEmpty() }?.joinToString(" + ")?.let { "Ваши жанры: $it" })
        }
    }

    private suspend fun safeGenres() = try { shikimori.genres() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { emptyList() }

    /**
     * «Подборки» → «Умный подбор»: the best titles for the chosen filters. Candidates come from the
     * catalog ranked by score in each chosen genre (or the viewer's strongest genres), plus titles similar
     * to liked ones when no genre is chosen; they are filtered exactly and ranked like [rankPicks], by
     * the catalog score when there is no taste yet.
     */
    suspend fun withFilters(library: LibraryData, filters: PickFilters, limit: Int = 30): List<Anime> = coroutineScope {
        val genres = shikimori.genres()
        val chosen = filters.genres.ifEmpty { library.tasteGenres().take(3).map { it.first } }
        val genreIds = genres.filter { it.title in chosen }.map { it.id }
        val year = app.animix.data.library.dayToDate(app.animix.core.localDayIndex()).year
        val season = when (filters.era) {
            "new" -> "2020_$year"
            "2010s" -> "2010_2019"
            "classic" -> "1980_2009"
            else -> null
        }
        val kinds = when (filters.kind) { "tv" -> listOf("tv"); "movie" -> listOf("movie"); "short" -> listOf("ova", "ona", "special"); else -> emptyList() }
        fun query(genres: List<String>, page: Int) = CatalogQuery(page = page, limit = 50, order = CatalogOrder.Ranked, kinds = kinds,
            status = if (filters.finished) "released" else null, season = season, genreIds = genres)
        // Several chosen genres are asked together: the catalog returns titles with all of them, instead of
        // the top of each genre with almost nothing in common. Without a choice, each favourite genre on its own.
        val groups = when {
            filters.genres.isNotEmpty() -> listOf(genreIds)
            genreIds.isEmpty() -> listOf(emptyList())
            else -> genreIds.map { listOf(it) }
        }
        val sources = groups.flatMap { group -> listOf(1, 2, 3).map { page ->
            async { safe { shikimori.catalog(query(group, page)).map(::PickCandidate) } } } }
        val liked = if (filters.genres.isEmpty()) library.entries.values.filter { it.favorite || (it.score ?: 0) >= 8 }.take(3).map { it.anime } else emptyList()
        val similar = liked.map { title -> async { safe { anime.similar(title).map { PickCandidate(it, title) } } } }
        val candidates = (sources + similar).awaitAll().flatten().filter { candidate ->
            val a = candidate.anime
            val episodes = a.episodes.takeIf { it > 0 } ?: a.episodesAired
            (filters.genres.isEmpty() || filters.genres.all { it in a.genres }) &&
                when (filters.length) { "short" -> episodes in 1..13; "season" -> episodes in 14..26; "long" -> episodes > 26; else -> true } &&
                (!filters.finished || a.status == AnimeStatus.Released) &&
                (kinds.isEmpty() || a.kind.name.lowercase() in kinds || (filters.kind == "short" && a.kind in setOf(AnimeKind.Ova, AnimeKind.Ona, AnimeKind.Special)))
        }
        rankPicks(library, candidates, limit).ifEmpty {
            candidates.map { it.anime }.distinctBy { it.key }.filter { it.key !in library.entries.keys && it.status != AnimeStatus.Announced }
                .sortedByDescending { it.score ?: 0.0 }.take(limit)
        }
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
fun rankPicks(library: LibraryData, candidates: List<PickCandidate>, limit: Int = 24): List<Anime> =
    rankPickCandidates(library, candidates, limit).map { it.anime }

/**
 * The best order with variety: the next pick comes from another liked title than the one before
 * whenever such a pick remains, so the shelf does not open with three «Похоже на …» of one favourite.
 */
internal fun varied(ranked: List<PickCandidate>): List<PickCandidate> {
    val left = ranked.toMutableList()
    val result = mutableListOf<PickCandidate>()
    while (left.isNotEmpty()) {
        val previous = result.lastOrNull()?.similarTo?.key
        val next = left.firstOrNull { previous == null || it.similarTo?.key != previous } ?: left.first()
        left.remove(next); result += next
    }
    return result
}

/** [rankPicks] with the liked title each pick resembles most, for its reason. */
fun rankPickCandidates(library: LibraryData, candidates: List<PickCandidate>, limit: Int = 24): List<PickCandidate> {
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
        Triple(a, fit + resemblance * 1.2 + quality - dislike, same.firstOrNull { it.similarTo != null }?.similarTo)
    }.sortedByDescending { it.second }
    val perSource = HashMap<String, Int>()
    return scored.filter { (_, _, source) ->
        source == null || (perSource[source.key] ?: 0).let { count -> perSource[source.key] = count + 1; count < 3 }
    }.take(limit).map { PickCandidate(it.first, it.third) }
}

private val AdultGenres = setOf("Хентай", "Эротика", "Hentai", "Erotica")
