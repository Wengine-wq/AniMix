package app.animix

import app.animix.data.library.LibraryEntry
import app.animix.data.skips.TimedSkip
import app.animix.data.skips.bestFor
import app.animix.model.Anime
import app.animix.model.Episode
import app.animix.model.SkipRange
import app.animix.ui.player.resumePositionMs
import app.animix.ui.player.skipTarget
import app.animix.ui.player.watchedFromMs
import app.animix.ui.screens.detail.closestEpisode
import app.animix.ui.screens.detail.resumeEpisode
import kotlin.test.*

class PlayerSkipTest {
    @Test fun skipOnlyAppliesInsideTheDeclaredRange() {
        val opening = SkipRange(10, 90)
        assertNull(skipTarget(9999, opening))
        assertEquals(100_000, skipTarget(10_000, opening))
        assertEquals(100_000, skipTarget(99_999, opening))
        assertNull(skipTarget(100_000, opening))
        assertNull(skipTarget(20_000, null))
        assertNull(skipTarget(20_000, SkipRange(10, 0)))
        assertNull(skipTarget(0, SkipRange(-1, 90)))
    }

    @Test fun anEpisodeCountsAsWatchedFromItsEndingOrTheLastMinutes() {
        assertEquals(1_300_000, watchedFromMs(1_440_000, SkipRange(1300, 90)))
        assertEquals((1_440_000 * 0.92).toLong(), watchedFromMs(1_440_000, null))
        // An ending in the first half is a bad submission, not the credits.
        assertEquals((1_440_000 * 0.92).toLong(), watchedFromMs(1_440_000, SkipRange(100, 90)))
        assertNull(watchedFromMs(0, null))
    }

    @Test fun aFinishedEpisodeStartsOverAndAStartedOneResumes() {
        assertEquals(600_000, resumePositionMs(600_000, 1_440_000, null))
        assertEquals(0, resumePositionMs(1_430_000, 1_440_000, null))
        assertEquals(0, resumePositionMs(1_440_000, 1_440_000, null))
        assertEquals(0, resumePositionMs(1_320_000, 1_440_000, SkipRange(1300, 90)))
        assertEquals(0, resumePositionMs(3_000, 1_440_000, null))
        // Unknown length: trust the saved position until the player learns it.
        assertEquals(1_430_000, resumePositionMs(1_430_000, null, null))
    }

    @Test fun aniSkipTimesMustComeFromAReleaseOfTheSameLength() {
        val skips = listOf(TimedSkip(90.4, 179.6, 1500.0), TimedSkip(13.9, 123.8, 1430.7))
        assertEquals(SkipRange(13, 111), skips.bestFor(1431.0))
        assertNull(listOf(TimedSkip(13.9, 123.8, 1430.7)).bestFor(1200.0))
        assertEquals(SkipRange(1370, 88), listOf(TimedSkip(1370.0, 1460.0, 1471.0)).bestFor(1458.0))
        assertEquals(SkipRange(13, 111), skips.drop(1).bestFor(null))
    }

    @Test fun watchStartsWhereTheViewerStopped() {
        val episodes = (1..12).map { Episode("$it", null, "https://kodik.test/$it", null, null, null) }
        val anime = Anime.placeholder(1)
        assertEquals("1", resumeEpisode(episodes, null)?.number)
        assertEquals("5", resumeEpisode(episodes, LibraryEntry(anime, episodesWatched = 4))?.number)
        assertEquals("3", resumeEpisode(episodes, LibraryEntry(anime, episodesWatched = 4, episodePositions = mapOf("3" to 60_000)))?.number)
        assertEquals("12", resumeEpisode(episodes, LibraryEntry(anime, episodesWatched = 12))?.number)
        assertEquals("7", closestEpisode(episodes, "7")?.number)
        assertEquals("12", closestEpisode(episodes.take(12), "13")?.number)
        assertNull(closestEpisode(episodes, null))
    }
}
