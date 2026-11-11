package app.animix

import app.animix.core.Settings
import app.animix.core.SettingsPreferences
import app.animix.data.library.LibraryEntry
import app.animix.data.library.ListStatus
import app.animix.data.shikimori.VideoDto
import app.animix.data.shikimori.trailer
import app.animix.model.Anime
import app.animix.model.AnimeStatus
import app.animix.model.Episode
import app.animix.ui.screens.catalog.rememberSearch
import app.animix.ui.screens.detail.episodeRanges
import app.animix.ui.screens.detail.resumeTarget
import app.animix.ui.screens.detail.trailerHost
import app.animix.ui.screens.profile.lastSeenLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Release13Test {
    private fun episode(number: Int) = Episode(number.toString(), null, "https://example.invalid/$number", null, null, null)

    @Test
    fun shortTitlesStayOneRangeAndLongOnesGoByAHundred() {
        assertEquals(1, episodeRanges((1..100).map(::episode)).size)
        val long = episodeRanges((1..1157).map(::episode))
        assertEquals(12, long.size)
        assertEquals("101", long[1].first().number)
        assertEquals("200", long[1].last().number)
        assertEquals("1157", long.last().last().number)
    }

    @Test
    fun recentSearchesKeepSixNewestWithoutRepeats() {
        var recent = emptyList<String>()
        for (query in listOf("Фрирен", "ван пис", "  ", "блич", "ФРИРЕН ", "a", "b", "c", "d")) recent = rememberSearch(recent, query)
        assertEquals(listOf("d", "c", "b", "a", "ФРИРЕН", "блич"), recent)
    }

    @Test
    fun recentSearchesSurviveSavingSettings() {
        val saved = SettingsPreferences.decode(SettingsPreferences.encode(Settings(recentSearches = listOf("Фрирен", "ван пис"))))
        assertEquals(listOf("Фрирен", "ван пис"), saved.recentSearches)
    }

    @Test
    fun theTrailerIsTheNewestPromoVideoNeverAnEpisodePreview() {
        val videos = listOf(
            VideoDto("https://youtu.be/a", "23", "episode_preview", "//img.youtube.com/vi/a/hqdefault.jpg"),
            VideoDto("https://youtu.be/pv1", "PV1", "pv", "//img.youtube.com/vi/pv1/hqdefault.jpg"),
            VideoDto("//vk.com/video-1_2", "PV2", "pv", null),
            VideoDto("https://youtu.be/op", "OP", "op", null),
        )
        val trailer = videos.trailer()
        assertEquals("https://vk.com/video-1_2", trailer?.url)
        assertNull(trailer?.imageUrl)
        assertEquals("https://img.youtube.com/vi/pv1/hqdefault.jpg", videos.take(2).trailer()?.imageUrl)
        assertNull(videos.take(1).trailer())
    }

    @Test
    fun theTrailerSaysWhereItOpens() {
        assertEquals("YouTube", trailerHost("https://youtu.be/zYnoUjuv7EE"))
        assertEquals("YouTube", trailerHost("https://www.youtube.com/watch?v=1"))
        assertEquals("VK Видео", trailerHost("https://vk.com/video-1_2"))
        assertEquals("Rutube", trailerHost("https://rutube.ru/video/1/"))
        assertEquals("example.org", trailerHost("https://example.org/v/1"))
    }

    @Test
    fun postersSayTheListAndTheProgressOfWhatIsBeingWatched() {
        val title = Anime.placeholder(1).copy(title = "A", episodes = 24, episodesAired = 12, status = AnimeStatus.Ongoing)
        val marks = listMarks(listOf(
            LibraryEntry(title, status = ListStatus.Watching, episodesWatched = 6),
            LibraryEntry(title.copy(id = 2), status = ListStatus.Completed, episodesWatched = 24),
            LibraryEntry(title.copy(id = 3), favorite = true),
            LibraryEntry(title.copy(id = 4), episodesWatched = 2),
        ))
        assertEquals("Смотрю", marks["s1"]?.label)
        assertEquals(0.5f, marks["s1"]?.progress)
        assertEquals("Просмотрено", marks["s2"]?.label)
        assertNull(marks["s2"]?.progress)
        assertEquals("Избранное", marks["s3"]?.label)
        // Watched without a list: nothing to say on the poster.
        assertFalse("s4" in marks)
    }

    @Test
    fun lastSeenReadsTheSameForAnyone() {
        val now = 1_800_000_000_000L
        assertEquals("В сети 5 минут назад", lastSeenLabel(now - 5 * 60_000, now))
        assertEquals("В сети только что", lastSeenLabel(now - 20_000, now))
        assertTrue(lastSeenLabel(now - 40L * 86_400_000, now).startsWith("В сети "))
        assertEquals("Не в сети", lastSeenLabel(0, now))
    }

    @Test
    fun watchLeadsToTheEpisodeLeftHalfWayOrTheNextOne() {
        val title = Anime.placeholder(7).copy(title = "B", episodes = 12)
        assertNull(resumeTarget(null, 12))
        assertNull(resumeTarget(LibraryEntry(title), 12))
        assertEquals("5" to true, resumeTarget(LibraryEntry(title, episodesWatched = 4, episodePositions = mapOf("3" to 0L, "5" to 61_000L)), 12))
        assertEquals("5" to false, resumeTarget(LibraryEntry(title, episodesWatched = 4), 12))
        // Watched to the end: nothing to continue.
        assertNull(resumeTarget(LibraryEntry(title, episodesWatched = 12), 12))
    }
}
