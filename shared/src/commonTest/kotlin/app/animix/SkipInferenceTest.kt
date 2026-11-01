package app.animix

import app.animix.data.skips.EpisodeSkips
import app.animix.data.skips.TimedSkip
import app.animix.data.skips.inferEnding
import app.animix.data.skips.inferOpening
import app.animix.model.SkipRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipInferenceTest {
    private fun episode(op: Pair<Double, Double>?, ed: Pair<Double, Double>?, length: Double = 1420.0) = EpisodeSkips(
        openings = listOfNotNull(op?.let { TimedSkip(it.first, it.second, length) }),
        endings = listOfNotNull(ed?.let { TimedSkip(it.first, it.second, length) }),
    )

    @Test fun neighboursAgreeOnTheOpeningPlace() {
        val near = listOf(episode(60.0 to 150.0, null), episode(62.0 to 151.0, null), episode(300.0 to 390.0, null))
        // The median ignores the one episode with a cold open.
        assertEquals(SkipRange(62, 90), inferOpening(near, 1420.0))
    }

    @Test fun endingKeepsItsDistanceFromTheEnd() {
        val near = listOf(episode(null, 1300.0 to 1390.0, 1420.0), episode(null, 1305.0 to 1395.0, 1425.0))
        // 120 s before the end of a 1430 s release in both neighbours (the median picks one).
        val ending = inferEnding(near, 1430.0)!!
        assertEquals(1310, ending.start)
        assertEquals(90, ending.length)
    }

    @Test fun oneEpisodeOrAnotherReleaseIsNotEnough() {
        assertNull(inferOpening(listOf(episode(60.0 to 150.0, null)), 1420.0))
        assertNull(inferOpening(listOf(episode(60.0 to 150.0, null, 2600.0), episode(60.0 to 150.0, null, 2600.0)), 1420.0))
        assertNull(inferEnding(listOf(episode(null, 1300.0 to 1390.0), episode(null, 1300.0 to 1390.0)), null))
    }
}
