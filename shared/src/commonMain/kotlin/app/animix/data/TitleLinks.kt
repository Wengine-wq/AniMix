package app.animix.data

import app.animix.data.library.LibraryData
import app.animix.data.library.ListStatus
import app.animix.model.Anime
import app.animix.model.AnimeKind
import kotlin.math.abs

/**
 * AniMix's own order for «Связанное» and ranking for «Похожее», on top of what Shikimori returns.
 * Pure functions: the repository feeds them data, the tests feed them fixtures.
 */
object TitleLinks {
    /**
     * Watch order inside a franchise: what comes before and after this title first, then the main
     * story it belongs to, side stories, other versions, spin-offs and summaries; clips, music and
     * character crossovers last or not at all.
     */
    fun relationRank(kind: String?): Int? = when (kind?.lowercase()) {
        "prequel" -> 0
        "sequel" -> 1
        "parent_story", "full_story" -> 2
        "side_story" -> 3
        "alternative_version", "alternative_setting" -> 4
        "spin_off" -> 5
        "summary" -> 6
        "other" -> 7
        "character" -> 8
        else -> 7
    }

    /** One related title with Shikimori's relation, for ordering and the label under its card. */
    data class Related(val anime: Anime, val kind: String?, val label: String?)

    /** Related titles in watch order (rank, then year), without clips/music and duplicates. */
    fun orderRelated(items: List<Related>): List<Related> = items
        .filter { it.anime.id > 0 && it.anime.kind != AnimeKind.Other }
        .distinctBy { it.anime.key }
        .sortedWith(compareBy<Related>({ relationRank(it.kind) ?: 9 }, { it.anime.year ?: Int.MAX_VALUE }))

    /**
     * «Похожее»: Shikimori's candidates re-ranked by how much they share with [base] — genres most of
     * all, then Shikimori's own order, the rating, the same kind of release and a close year. Titles of
     * the same franchise and the ones the viewer dropped are left out; finished ones go to the end.
     */
    fun rankSimilar(
        base: Anime,
        candidates: List<Anime>,
        franchise: Set<String> = emptySet(),
        library: LibraryData? = null,
        limit: Int = 15,
    ): List<Anime> {
        val baseGenres = base.genres.toSet()
        val pool = candidates.filter { it.key != base.key && it.key !in franchise && it.id > 0 }.distinctBy { it.key }
        return pool.mapIndexedNotNull { index, anime ->
            val status = library?.entries?.get(anime.key)?.status
            if (status == ListStatus.Dropped) return@mapIndexedNotNull null
            val genres = anime.genres.toSet()
            val overlap = if (baseGenres.isEmpty() || genres.isEmpty()) 0.3
                else (baseGenres intersect genres).size.toDouble() / (baseGenres union genres).size
            val order = 1.0 - index.toDouble() / pool.size.coerceAtLeast(1)
            val rating = ((anime.score ?: 6.5) - 6.0).coerceIn(0.0, 4.0) / 4.0
            val kind = if (anime.kind == base.kind) 1.0 else 0.0
            val year = if (base.year != null && anime.year != null) (1.0 - abs(base.year - anime.year) / 20.0).coerceAtLeast(0.0) else 0.5
            var score = 0.45 * overlap + 0.25 * order + 0.15 * rating + 0.10 * kind + 0.05 * year
            if (status == ListStatus.Completed) score *= 0.6
            anime to score
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
