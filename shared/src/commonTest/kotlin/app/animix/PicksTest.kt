package app.animix

import app.animix.data.PickCandidate
import app.animix.data.library.*
import app.animix.data.rankPicks
import app.animix.model.Anime
import app.animix.model.AnimeKind
import app.animix.model.AnimeStatus
import kotlin.test.*

class PicksTest {
    private fun anime(id: Int, genres: List<String>, score: Double? = 7.5, status: AnimeStatus = AnimeStatus.Released, kind: AnimeKind = AnimeKind.Tv) =
        Anime.placeholder(id).copy(title = "T$id", genres = genres, score = score, status = status, kind = kind, episodes = 12)

    private val library = LibraryData(entries = listOf(
        LibraryEntry(anime(1, listOf("Экшен", "Фэнтези")), ListStatus.Completed, favorite = true, score = 9),
        LibraryEntry(anime(2, listOf("Экшен", "Сёнен")), ListStatus.Completed, score = 8),
        LibraryEntry(anime(3, listOf("Романтика")), ListStatus.Dropped),
    ).associateBy { it.anime.key })

    @Test fun genreFitAndResemblanceOutrankSourceOrder() {
        val liked = library.entries.getValue("s1").anime
        val other = library.entries.getValue("s2").anime
        val picks = rankPicks(library, listOf(
            PickCandidate(anime(10, listOf("Романтика"))),
            PickCandidate(anime(11, listOf("Экшен", "Фэнтези"))),
            PickCandidate(anime(12, listOf("Экшен")), liked),
            PickCandidate(anime(12, listOf("Экшен")), other),
        ))
        assertEquals(listOf("s12", "s11", "s10"), picks.map { it.key })
    }

    @Test fun listedAnnouncedAdultAndWeakTitlesAreLeftOut() {
        val picks = rankPicks(library, listOf(
            PickCandidate(library.entries.getValue("s1").anime),
            PickCandidate(anime(20, listOf("Экшен"), status = AnimeStatus.Announced)),
            PickCandidate(anime(21, listOf("Экшен", "Хентай"))),
            PickCandidate(anime(22, listOf("Экшен"), score = 5.1)),
            PickCandidate(anime(23, listOf("Экшен"), kind = AnimeKind.Other)),
            PickCandidate(anime(24, listOf("Экшен"))),
        ))
        assertEquals(listOf("s24"), picks.map { it.key })
    }

    @Test fun oneLikedTitleCannotFillTheShelf() {
        val liked = library.entries.getValue("s1").anime
        val picks = rankPicks(library, (30..40).map { PickCandidate(anime(it, listOf("Экшен", "Фэнтези")), liked) })
        assertEquals(3, picks.size)
    }

    @Test fun noTasteMeansNoPicks() {
        assertTrue(rankPicks(LibraryData(), listOf(PickCandidate(anime(1, listOf("Экшен"))))).isEmpty())
    }
}
