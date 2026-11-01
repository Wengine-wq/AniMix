package app.animix

import app.animix.data.TitleLinks
import app.animix.model.Anime
import app.animix.model.AnimeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class TitleLinksTest {
    private fun anime(id: Int, genres: List<String> = emptyList(), year: Int? = 2015, kind: AnimeKind = AnimeKind.Tv, score: Double? = 7.5) =
        Anime.placeholder(id).copy(title = "T$id", genres = genres, year = year, kind = kind, score = score)

    @Test fun relatedFollowsWatchOrderWithoutClips() {
        val items = listOf(
            TitleLinks.Related(anime(3, year = 2018), "side_story", "Другая история"),
            TitleLinks.Related(anime(2, year = 2017), "sequel", "Продолжение"),
            TitleLinks.Related(anime(4, kind = AnimeKind.Other), "other", "Клип"),
            TitleLinks.Related(anime(1, year = 2013), "prequel", "Предыстория"),
        )
        assertEquals(listOf(1, 2, 3), TitleLinks.orderRelated(items).map { it.anime.id })
    }

    @Test fun similarPrefersSharedGenresAndSkipsTheFranchise() {
        val base = anime(1, listOf("Экшен", "Драма", "Военное"))
        val candidates = listOf(
            anime(10, listOf("Комедия", "Повседневность")),
            anime(11, listOf("Экшен", "Драма", "Военное")),
            anime(12, listOf("Экшен", "Драма")),
            anime(13, listOf("Экшен", "Драма", "Военное")),
        )
        val ranked = TitleLinks.rankSimilar(base, candidates, franchise = setOf("s13"))
        assertEquals(listOf(11, 12, 10), ranked.map { it.id })
    }
}
