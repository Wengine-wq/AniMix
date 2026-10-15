package app.animix

import app.animix.data.shikimori.stripBbCode
import app.animix.data.yummy.TitleMatcher
import app.animix.data.yummy.YummyRepository
import app.animix.data.yummy.YummySkip
import app.animix.data.yummy.YummySkips
import app.animix.data.yummy.YummyVideo
import app.animix.data.yummy.YummyVideoData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataLayerTest {

    @Test
    fun identicalTitlesMatchFully() {
        assertEquals(100, TitleMatcher.score("Магическая битва", "магическая  битва!"))
    }

    @Test
    fun differentSeasonsDoNotMatch() {
        assertTrue(TitleMatcher.score("Магическая битва 2", "Магическая битва") < 50)
        assertTrue(TitleMatcher.score("Attack on Titan", "Attack on Titan III") < 50)
    }

    @Test
    fun bbCodeIsRemoved() {
        assertEquals("Отряд героя Химмеля вернулся.", stripBbCode("Отряд героя [character=186854]Химмеля[/character] вернулся."))
    }

    @Test
    fun videosAreGroupedByPlayerAndStudio() {
        fun video(n: String, player: String, dub: String) = YummyVideo(
            video_id = n.toLong(),
            data = YummyVideoData(player = "Плеер $player", dubbing = "Озвучка $dub"),
            number = n,
            iframe_url = "//player/$player/$dub/$n",
            skips = YummySkips(opening = YummySkip(time = 90, length = 85)),
        )
        val groups = YummyRepository.groupDubbings(
            listOf(
                video("2", "Aksor", "AniLibria"),
                video("1", "Aksor", "AniLibria"),
                video("1", "Kodik", "AniLibria"),
                video("1", "Kodik", "AniLibria"), // duplicate episode is ignored
            ),
        )
        assertEquals(2, groups.size)
        assertEquals("Kodik", groups.first().player) // Kodik is preferred
        val aksor = groups.first { it.player == "Aksor" }
        assertEquals(listOf("1", "2"), aksor.episodes.map { it.number })
        assertEquals("AniLibria", aksor.studio)
        assertEquals("https://player/Aksor/AniLibria/1", aksor.episodes.first().iframeUrl)
        assertEquals(175, aksor.episodes.first().opening?.end)
    }
}
