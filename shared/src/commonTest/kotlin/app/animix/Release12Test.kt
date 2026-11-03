package app.animix

import app.animix.core.AppSettings
import app.animix.core.DiskStore
import app.animix.data.library.Library
import app.animix.data.library.ListStatus
import app.animix.data.library.PlaybackCredit
import app.animix.data.library.ViewedRange
import app.animix.data.notify.EpisodeNotice
import app.animix.data.notify.EpisodeNotifier
import app.animix.data.playback.PlaybackTracker
import app.animix.data.playback.PlayerSnapshot
import app.animix.data.pulse.digestFrom
import app.animix.data.pulse.weekStart
import app.animix.model.Anime
import app.animix.model.AnimeStatus
import app.animix.model.Dubbing
import app.animix.model.Episode
import app.animix.model.WatchSource
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Release12Test {
    private val ongoing = Anime.placeholder(59000).copy(title = "Онгоинг", episodes = 12, episodesAired = 4, status = AnimeStatus.Ongoing)

    @Test
    fun firstCheckRemembersThenNewEpisodesAndDubbingNotify() = runTest {
        val store = DiskStore("build/test-notify-${Random.nextInt(1_000_000)}")
        val library = Library(store, backgroundScope).apply { load() }
        val settings = AppSettings(store, backgroundScope).apply { load() }
        library.setStatus(ongoing, ListStatus.Watching)
        settings.update { it.copy(dubbingByAnime = mapOf(ongoing.key to "kodik|anilibria")) }
        var aired = 4
        var dubbed = 3
        val posted = mutableListOf<EpisodeNotice>()
        val notifier = EpisodeNotifier(store, library, settings,
            cards = { listOf(ongoing.copy(episodesAired = aired)) },
            source = { WatchSource(1, "x", listOf(Dubbing("AniLibria", "Kodik", (1..dubbed).map { Episode("$it", null, "", null, null, null) }))) },
            post = { posted += it })
        assertTrue(notifier.check(minIntervalMs = 0).isEmpty(), "The first look only remembers the counts")
        aired = 5
        val aired5 = notifier.check(minIntervalMs = 0)
        assertEquals(listOf(5), aired5.map { it.episode })
        assertEquals(null, aired5.single().dubbing)
        assertEquals(1, posted.size)
        assertEquals(1, notifier.notices.value.count { !it.read })
        notifier.markRead()
        assertTrue(notifier.notices.value.all { it.read })
        store.clear()
    }

    @Test
    fun fasterPlaybackCountsCoverageButNotExtraTime() {
        val tracker = PlaybackTracker()
        val base = PlayerSnapshot(playing = true, ready = true, durationMs = 1_440_000, speed = 2f)
        tracker.sample(base.copy(positionMs = 10_000), 0)
        tracker.sample(base.copy(positionMs = 14_000), 2_000)
        val credit = tracker.drain()
        assertEquals(listOf(ViewedRange(10_000, 14_000)), credit.ranges)
        assertEquals(2_000, credit.elapsedMs, "Activity time is the real time spent")
        val normal = PlaybackTracker()
        normal.sample(base.copy(speed = 1f, positionMs = 10_000), 0)
        normal.sample(base.copy(speed = 1f, positionMs = 14_000), 2_000)
        assertTrue(normal.drain().ranges.isEmpty(), "At 1× a jump of twice the real time is a seek, not viewing")
    }

    @Test
    fun digestCountsTheWeekAndNewEpisodes() = runTest {
        val library = Library(DiskStore("build/test-digest-${Random.nextInt(1_000_000)}"), backgroundScope).apply { load() }
        library.setStatus(ongoing, ListStatus.Watching)
        library.recordPlayback(ongoing, "1", PlaybackCredit(listOf(ViewedRange(0, 1_440_000)), 1_440_000), 1_440_000, null, null, true)
        val digest = digestFrom(library.state.value, cards = mapOf(ongoing.key to ongoing.copy(episodesAired = 6)))
        assertEquals(weekStart(app.animix.core.localDayIndex()), digest.week.start)
        assertEquals(24, digest.week.minutes)
        assertEquals(1, digest.week.episodes)
        assertEquals(5, digest.fresh.single().unseen)
        assertEquals(8, digest.weeks.size)
    }
}
