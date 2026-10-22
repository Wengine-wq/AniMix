package app.animix

import app.animix.core.DiskStore
import app.animix.data.playback.NativePlayer
import app.animix.data.playback.PlayerSnapshot
import app.animix.data.skips.SkipPlan
import app.animix.data.watchflow.*
import app.animix.model.Anime
import app.animix.model.Episode
import app.animix.model.SkipRange
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.random.Random
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WatchFlowTest {
    private fun episode(number: Int, minutes: Int = 24, opening: SkipRange? = null, ending: SkipRange? = null) = FlowEpisode(
        Anime.placeholder(52991), "kodik|studio", "Studio", Episode("$number", null, "https://example.com/$number", minutes * 60, opening, ending), minutes * 60_000L)

    private class Clock(var wall: Long = 1_000_000, var mono: Long = 0)
    private fun TestScope.engine(clock: Clock = Clock(), scope: CoroutineScope = backgroundScope,
        disk: DiskStore = DiskStore("build/test-flow-${Random.nextInt(1_000_000)}")) =
        WatchFlow(disk, scope, { clock.wall }, { clock.mono }, UnconfinedTestDispatcher(testScheduler))

    @Test fun fiftyFiveMinutesFitsTwoEpisodesAndPreservesStoryOrder() {
        val options = FlowOptions(minutes = 55)
        val fitted = fitFlowQueue((1..4).map { episode(it) }, options)
        assertEquals(listOf("1", "2"), fitted.map { it.episode.number })
        assertEquals(48 * 60_000L, fitted.sumOf { flowDuration(it, options) })
        assertEquals(listOf("1"), fitFlowQueue(listOf(episode(1, 20), episode(2, 40), episode(3, 5)), options).map { it.episode.number })
    }

    @Test fun knownSkipsAndResumeChangeTheBudgetWithoutDoubleSubtractingOverlaps() {
        val item = episode(1, 10, SkipRange(240, 120), SkipRange(330, 120))
        assertEquals(390_000L, flowDuration(item, FlowOptions(skipOpenings = true, skipEndings = true)))
        assertEquals(210_000L, flowDuration(item, FlowOptions(skipOpenings = true, skipEndings = true), 180_000))
        assertEquals(360_000L, flowDuration(item, FlowOptions(skipOpenings = false, skipEndings = false), 240_000))
        assertEquals(0L, flowDuration(item, FlowOptions(), 900_000))
    }

    @Test fun invalidOrMissingSkipMetadataDoesNotPromiseFreeMinutes() {
        val invalid = episode(1, 24, SkipRange(-1, 90), SkipRange(100, 90))
        assertEquals(24 * 60_000L, flowDuration(invalid, FlowOptions(skipOpenings = true, skipEndings = true)))
        assertEquals(24 * 60_000L, flowDuration(episode(1), FlowOptions()))
        assertEquals(24 * 60_000L, flowDuration(episode(1, opening = SkipRange(1400, 90)), FlowOptions()))
    }

    @Test fun fittingDeduplicatesEpisodesAndStopsAtTheBudget() {
        val item = episode(1, 20)
        assertEquals(listOf(item, episode(2, 20)), fitFlowQueue(listOf(item, item, episode(2, 20), episode(3, 20)), FlowOptions(minutes = 40)))
        assertTrue(fitFlowQueue(listOf(item), FlowOptions(), availableMs = -1).isEmpty())
    }

    @Test fun pausedSessionStillExpiresAndPausesTheBoundDecoder() = runTest {
        val clock = Clock()
        val flow = engine(clock).apply { load(); options(FlowOptions(minutes = 1)); add(listOf(episode(1))); start() }
        val player = FakePlayer()
        flow.bind(player)
        flow.pause()
        assertEquals(1, player.pauses)
        clock.mono = 60_000
        flow.tick()
        assertEquals(FlowFinish.TimeLimit, flow.state.value.finish)
        assertEquals(0L, flow.state.value.remainingMs)
        assertEquals(2, player.pauses)
        flow.resume()
        assertEquals(FlowPhase.Finished, flow.state.value.phase)
    }

    @Test fun clockChangesDoNotExtendTheSessionAndLoadingDoesNotSuspendTheTimer() = runTest {
        val clock = Clock()
        val flow = engine(clock).apply { load(); options(FlowOptions(minutes = 1)); add(listOf(episode(1))); start() }
        val deadline = flow.state.value.deadlineAt
        clock.wall -= 3_600_000
        clock.mono = 30_000
        flow.tick()
        assertEquals(30_000L, flow.state.value.remainingMs)
        assertEquals(deadline, flow.state.value.deadlineAt)
        clock.mono = 60_000
        flow.tick()
        assertEquals(FlowFinish.TimeLimit, flow.state.value.finish)
    }

    @Test fun tickerStopsTheSessionWithoutAnyUiOrPlayerSnapshots() = runTest {
        val clock = Clock()
        val flow = engine(clock).apply { load(); options(FlowOptions(minutes = 1)); add(listOf(episode(1))); start() }
        val player = FakePlayer()
        flow.bind(player)
        runCurrent()
        clock.mono = 60_000
        advanceTimeBy(300)
        runCurrent()
        assertEquals(FlowFinish.TimeLimit, flow.state.value.finish)
        assertEquals(1, player.pauses)
    }

    @Test fun automaticNextAdvancesOnlyOnceAndEndsAfterTheLastEpisode() = runTest {
        val flow = engine().apply { load(); add(listOf(episode(1), episode(2))); start() }
        flow.completed(episode(1).key)
        flow.completed(episode(1).key)
        assertEquals(1, flow.state.value.index)
        assertEquals(setOf(episode(1).key), flow.state.value.finishedKeys)
        flow.completed(episode(2).key)
        assertEquals(FlowFinish.QueueCompleted, flow.state.value.finish)
        assertEquals(2, flow.state.value.finishedKeys.size)
    }

    @Test fun delayedPlaybackDoesNotAutomaticallyStartAnEpisodeThatNoLongerFits() = runTest {
        val clock = Clock()
        val flow = engine(clock).apply { load(); add(listOf(episode(1), episode(2))); start() }
        clock.mono = 40 * 60_000L
        flow.completed(episode(1).key)
        assertEquals(FlowFinish.NextDoesNotFit, flow.state.value.finish)
        flow.reset()
        assertEquals(listOf("2"), flow.state.value.queue.map { it.episode.number })
    }

    @Test fun disablingAutoNextWaitsForExplicitContinuation() = runTest {
        val flow = engine().apply { load(); options(FlowOptions(autoNext = false)); add(listOf(episode(1), episode(2))); start() }
        flow.completed(episode(1).key)
        assertEquals(FlowPhase.Paused, flow.state.value.phase)
        assertTrue(flow.state.value.awaitingNext)
        flow.resume()
        assertEquals(FlowPhase.Running, flow.state.value.phase)
        assertEquals(1, flow.state.value.index)
        assertFalse(flow.state.value.awaitingNext)
    }

    @Test fun lateCompletionResumeAndNextCannotReviveAnExpiredSession() = runTest {
        val clock = Clock()
        val flow = engine(clock).apply { load(); options(FlowOptions(minutes = 1)); add(listOf(episode(1), episode(2))); start() }
        clock.mono = 60_000
        flow.completed(episode(1).key)
        flow.next(); flow.resume(); flow.stop()
        assertEquals(FlowFinish.TimeLimit, flow.state.value.finish)
        assertTrue(flow.state.value.finishedKeys.isEmpty())
        assertEquals(0, flow.state.value.index)
    }

    @Test fun actualDurationCorrectsTheEstimateAndSeekingOnlyChangesTheForecast() = runTest {
        val flow = engine().apply { load(); add(listOf(episode(1).copy(estimated = true))); start() }
        flow.snapshot(episode(1).key, PlayerSnapshot(ready = true, durationMs = 1_200_000, positionMs = 600_000), SkipPlan(SkipRange(0, 90), null))
        assertFalse(flow.state.value.estimated)
        assertEquals(600_000L, flow.state.value.remainingContentMs)
        assertTrue(flow.state.value.finishedKeys.isEmpty())
        assertEquals(55 * 60_000L, flow.state.value.remainingMs)
        assertEquals(FlowPhase.Running, flow.state.value.phase)
    }

    @Test fun aNewPlayerBindingCannotBeRemovedByAnOldPlayerDisposal() = runTest {
        val flow = engine().apply { load(); add(listOf(episode(1))); start() }
        val first = FakePlayer()
        val second = FakePlayer()
        val unbindFirst = flow.bind(first)
        flow.bind(second)
        unbindFirst()
        flow.stop()
        assertEquals(0, first.pauses)
        assertEquals(1, second.pauses)
    }

    @Test fun restartRestoresOnlyTheRemainingQueueAsADraft() = runTest {
        val disk = DiskStore("build/test-flow-restart-${Random.nextInt(1_000_000)}")
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val flow = engine(scope = firstScope, disk = disk).apply { load(); add(listOf(episode(1), episode(2), episode(3))); start() }
        flow.completed(episode(1).key)
        flow.snapshot(episode(2).key, PlayerSnapshot(ready = true, durationMs = 1_440_000, positionMs = 120_000), SkipPlan(null, null))
        flow.flush()
        firstScope.coroutineContext[Job]!!.cancelAndJoin()
        val restored = engine(disk = disk).apply { load() }.state.value
        assertEquals(FlowPhase.Draft, restored.phase)
        assertNull(restored.deadlineAt)
        assertEquals(listOf("2", "3"), restored.queue.map { it.episode.number })
        assertEquals(120_000L, restored.queue.first().startPositionMs)
        disk.clear()
    }

    @Test fun queueEditingIsLockedWhileActiveAndDuplicatesAreIgnored() = runTest {
        val one = episode(1)
        val two = episode(2)
        val flow = engine().apply { load(); add(listOf(one, two, one)); move(two.key, -1) }
        assertEquals(listOf(two, one), flow.state.value.queue)
        flow.start()
        flow.remove(one.key); flow.add(listOf(episode(3))); flow.options(FlowOptions(minutes = 1)); flow.reset()
        assertEquals(listOf(two, one), flow.state.value.queue)
        assertEquals(55, flow.state.value.options.minutes)
    }

    private class FakePlayer : NativePlayer {
        override val state = MutableStateFlow(PlayerSnapshot())
        var pauses = 0
        override fun load(url: String, headers: Map<String, String>, startPositionMs: Long, playWhenReady: Boolean) = Unit
        override fun play() = Unit
        override fun pause() { pauses++ }
        override fun seekTo(positionMs: Long) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun release() = Unit
    }
    @Test fun draggingUsesStableKeysPersistsOrderAndCannotChangeActiveSession() = runTest {
        val disk = DiskStore("build/test-flow-drag-${kotlin.random.Random.nextInt(1_000_000_000)}")
        val flow = WatchFlow(disk, backgroundScope).apply { load() }
        val one = episode(1); val two = episode(2); val three = episode(3)
        flow.add(listOf(one, two, three))
        flow.moveTo(one.key, three.key)
        assertEquals(listOf(two.key, three.key, one.key), flow.state.value.queue.map { it.key })
        flow.moveTo("missing", two.key)
        flow.moveTo(one.key, "header")
        flow.flush()
        val restored = WatchFlow(disk, backgroundScope).apply { load() }
        assertEquals(flow.state.value.queue, restored.state.value.queue)
        assertTrue(restored.start())
        val activeOrder = restored.state.value.queue
        restored.moveTo(one.key, two.key)
        assertEquals(activeOrder, restored.state.value.queue)
    }

}
