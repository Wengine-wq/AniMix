package app.animix

import app.animix.data.downloads.*
import app.animix.model.Anime
import app.animix.model.Episode
import kotlin.test.*

class OfflineQueueTest {
    private fun episode(number: Int, quality: String = "720p", dub: String = "dub") = DownloadItem(
        "$number-$quality-$dub", Anime.placeholder(123), "Studio", dub,
        Episode(number.toString(), null, "", 12, null, null), quality,
        status = DownloadStatus.Completed, file = "$number.mp4",
    )

    @Test fun nextAvailableEpisodeCanHaveDifferentQualityAndNumberingGaps() {
        val first = episode(1)
        val second = episode(2, "1080p")
        val third = episode(3)
        assertEquals(second, nextDownloadedEpisode(listOf(first, third, second), first) { true })
        assertEquals(third, nextDownloadedEpisode(listOf(first, third), first) { true })
    }

    @Test fun prefersSameQualityWithoutSkippingEarlierEpisodeOrChangingDub() {
        val first = episode(1)
        val second = episode(2)
        assertEquals(second, nextDownloadedEpisode(listOf(episode(2, "1080p"), second, episode(3), episode(2, dub = "other")), first) { true })
        assertNull(nextDownloadedEpisode(listOf(episode(2, dub = "other")), first) { true })
    }

    @Test fun missingFilesAndIncompleteDownloadsAreExcluded() {
        val first = episode(1)
        val third = episode(3)
        assertEquals(third, nextDownloadedEpisode(listOf(episode(2), third, episode(2, "1080p").copy(status = DownloadStatus.Downloading)), first) { it != episode(2) })
        assertNull(nextDownloadedEpisode(listOf(episode(2)), first) { false })
    }
}
