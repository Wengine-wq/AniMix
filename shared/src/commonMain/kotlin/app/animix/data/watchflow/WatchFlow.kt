package app.animix.data.watchflow

import app.animix.core.AppJson
import app.animix.core.DiskStore
import app.animix.core.nowMillis
import app.animix.data.playback.NativePlayer
import app.animix.data.playback.PlayerSnapshot
import app.animix.data.skips.SkipPlan
import app.animix.model.Anime
import app.animix.model.Episode
import app.animix.model.SkipRange
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

@Serializable
data class FlowOptions(
    val minutes: Int = 55,
    val skipOpenings: Boolean = true,
    val skipEndings: Boolean = false,
    val autoNext: Boolean = true,
) {
    val limitMs: Long get() = minutes.coerceIn(1, 360) * 60_000L
}

@Serializable
data class FlowEpisode(
    val anime: Anime,
    val dubbingKey: String,
    val studio: String,
    val episode: Episode,
    val durationMs: Long,
    val estimated: Boolean = false,
    val startPositionMs: Long = 0,
    val downloadId: String? = null,
) {
    val key: String get() = "${anime.key}|$dubbingKey|${episode.number}"
}

enum class FlowPhase { Draft, Running, Paused, Finished }
enum class FlowFinish { QueueCompleted, TimeLimit, NextDoesNotFit, Stopped }

data class FlowState(
    val loaded: Boolean = false,
    val options: FlowOptions = FlowOptions(),
    val queue: List<FlowEpisode> = emptyList(),
    val phase: FlowPhase = FlowPhase.Draft,
    val index: Int = 0,
    val finishedKeys: Set<String> = emptySet(),
    val positionMs: Long = 0,
    val startedAt: Long? = null,
    val deadlineAt: Long? = null,
    val remainingMs: Long = 0,
    val awaitingNext: Boolean = false,
    val finish: FlowFinish? = null,
) {
    val active: Boolean get() = phase == FlowPhase.Running || phase == FlowPhase.Paused
    val current: FlowEpisode? get() = queue.getOrNull(index)
    val remainingContentMs: Long get() = queue.drop(index).sumOf {
        if (it.key in finishedKeys) 0 else flowDuration(it, options, if (it.key == current?.key && active) positionMs else it.startPositionMs)
    }
    val estimated: Boolean get() = queue.drop(index).any { it.estimated }
}

/** Unknown or invalid skip metadata never manufactures a saving in the planner. */
fun flowSkipPlan(item: FlowEpisode, durationMs: Long = item.durationMs): SkipPlan {
    fun valid(range: SkipRange?, ending: Boolean): SkipRange? = range?.takeIf {
        it.start >= 0 && it.length > 0 && (it.start.toLong() + it.length) * 1000 <= durationMs &&
            it.length.toLong() * 1000 <= durationMs / 2 && (!ending || it.start.toLong() * 1000 > durationMs / 2)
    }
    return SkipPlan(valid(item.episode.opening, false), valid(item.episode.ending, true))
}

fun flowDuration(item: FlowEpisode, options: FlowOptions, positionMs: Long = item.startPositionMs): Long {
    val duration = item.durationMs.coerceAtLeast(0)
    val start = positionMs.coerceIn(0, duration)
    val plan = flowSkipPlan(item)
    val ranges = listOfNotNull(plan.opening.takeIf { options.skipOpenings }, plan.ending.takeIf { options.skipEndings })
        .map { maxOf(start, it.start * 1000L) to minOf(duration, it.end * 1000L) }
        .filter { it.second > it.first }.sortedBy { it.first }
    var excluded = 0L
    var previousEnd = start
    for ((from, to) in ranges) {
        excluded += (to - maxOf(from, previousEnd)).coerceAtLeast(0)
        previousEnd = maxOf(previousEnd, to)
    }
    return (duration - start - excluded).coerceAtLeast(0)
}

/** Preserve episode order: fitting a session must not skip story episodes to pack the budget. */
fun fitFlowQueue(candidates: List<FlowEpisode>, options: FlowOptions, availableMs: Long = options.limitMs): List<FlowEpisode> {
    var remaining = availableMs.coerceAtLeast(0)
    val result = mutableListOf<FlowEpisode>()
    for (item in candidates.distinctBy { it.key }) {
        val duration = flowDuration(item, options)
        if (duration <= 0) continue
        if (duration > remaining) break
        result += item
        remaining -= duration
    }
    return result
}

@Serializable
private data class SavedFlow(val options: FlowOptions = FlowOptions(), val queue: List<FlowEpisode> = emptyList())

private fun flowClock(): () -> Long {
    val started = TimeSource.Monotonic.markNow()
    return { started.elapsedNow().inWholeMilliseconds }
}

/** Owns a whole viewing session. It never writes list marks, playback coverage or rewards. */
class WatchFlow(
    private val disk: DiskStore,
    private val scope: CoroutineScope,
    private val wallClock: () -> Long = ::nowMillis,
    private val monotonicClock: () -> Long = flowClock(),
    private val playerDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val mutable = MutableStateFlow(FlowState())
    val state = mutable.asStateFlow()
    private val loadLock = Mutex()
    private val writeLock = Mutex()
    private var startedMonotonic: Long? = null
    private var ticker: Job? = null
    private data class Binding(val token: Any, val pause: () -> Unit)
    private val binding = MutableStateFlow<Binding?>(null)

    suspend fun load() = loadLock.withLock {
        if (mutable.value.loaded) return@withLock
        val saved = disk.read("watch-flow.json")?.let { runCatching { AppJson.decodeFromString(SavedFlow.serializer(), it) }.getOrNull() }
        // A process restart restores a draft, never an old timer or an autoplaying session.
        mutable.value = FlowState(loaded = true, options = saved?.options ?: FlowOptions(), queue = saved?.queue.orEmpty())
    }

    fun options(options: FlowOptions) {
        if (state.value.active) return
        mutable.update { it.copy(options = options.copy(minutes = options.minutes.coerceIn(1, 360))) }
        save()
    }

    fun add(items: List<FlowEpisode>) {
        if (state.value.active) return
        mutable.update { it.copy(queue = (it.queue + items).distinctBy(FlowEpisode::key), phase = FlowPhase.Draft, finish = null) }
        save()
    }

    fun remove(key: String) {
        if (state.value.active) return
        mutable.update { it.copy(queue = it.queue.filterNot { item -> item.key == key }) }
        save()
    }

    fun move(key: String, delta: Int) {
        if (state.value.active) return
        mutable.update { current ->
            val index = current.queue.indexOfFirst { it.key == key }
            val target = index + delta
            if (index < 0 || target !in current.queue.indices) current
            else current.copy(queue = current.queue.toMutableList().apply { add(target, removeAt(index)) })
        }
        save()
    }

    /** Move by stable identities: lazy-grid indices also include headers and controls. */
    fun moveTo(key: String, targetKey: String) {
        mutable.update { current ->
            if (current.active) return@update current
            val from = current.queue.indexOfFirst { it.key == key }
            val to = current.queue.indexOfFirst { it.key == targetKey }
            if (from < 0 || to < 0 || from == to) current
            else current.copy(queue = current.queue.toMutableList().apply { add(to, removeAt(from)) })
        }
        save()
    }

    fun reset() {
        if (state.value.active) return
        val old = state.value
        val remaining = remainingQueue(old)
        mutable.value = FlowState(loaded = true, options = old.options, queue = if (old.phase == FlowPhase.Finished) remaining else emptyList())
        save()
    }

    fun start(): Boolean {
        val old = state.value
        if (!old.loaded || old.active || old.queue.isEmpty()) return false
        val now = wallClock()
        startedMonotonic = monotonicClock()
        mutable.value = old.copy(phase = FlowPhase.Running, index = 0, finishedKeys = emptySet(),
            positionMs = old.queue.first().startPositionMs, startedAt = now, deadlineAt = now + old.options.limitMs,
            remainingMs = old.options.limitMs, awaitingNext = false, finish = null)
        ticker?.cancel()
        ticker = scope.launch { while (isActive && state.value.active) { delay(250); tick() } }
        save()
        return true
    }

    fun tick() {
        val old = state.value
        val started = startedMonotonic ?: return
        if (!old.active) return
        val remaining = (old.options.limitMs - (monotonicClock() - started).coerceAtLeast(0)).coerceAtLeast(0)
        if (remaining == 0L) finish(FlowFinish.TimeLimit)
        else if (old.remainingMs / 1000 != remaining / 1000) mutable.update { if (it.active) it.copy(remainingMs = remaining) else it }
    }

    fun pause() {
        if (state.value.phase != FlowPhase.Running) return
        mutable.update { if (it.phase == FlowPhase.Running) it.copy(phase = FlowPhase.Paused) else it }
        pausePlayer()
        save()
    }

    fun resume() {
        tick()
        if (state.value.phase != FlowPhase.Paused) return
        if (state.value.awaitingNext) next() else mutable.update { if (it.phase == FlowPhase.Paused) it.copy(phase = FlowPhase.Running) else it }
    }

    fun stop() { if (state.value.active) finish(FlowFinish.Stopped) }

    fun completed(key: String) {
        tick()
        val old = state.value
        if (!old.active || old.current?.key != key || key in old.finishedKeys) return
        mutable.update { if (it.active && it.current?.key == key) it.copy(finishedKeys = it.finishedKeys + key) else it }
        if (old.index == old.queue.lastIndex) finish(FlowFinish.QueueCompleted)
        else if (old.options.autoNext) next(automatic = true)
        else { mutable.update { if (it.active) it.copy(awaitingNext = true, phase = FlowPhase.Paused) else it }; pausePlayer(); save() }
    }

    fun next(automatic: Boolean = false) {
        tick()
        val old = state.value
        if (!old.active) return
        val next = old.queue.getOrNull(old.index + 1)
        if (next == null) { finish(FlowFinish.QueueCompleted); return }
        if (automatic && flowDuration(next, old.options) > old.remainingMs) {
            finish(FlowFinish.NextDoesNotFit)
            return
        }
        mutable.update { if (it.active && it.index == old.index) it.copy(index = old.index + 1, positionMs = next.startPositionMs, phase = FlowPhase.Running, awaitingNext = false) else it }
        save()
    }

    /** Real decoder metadata corrects estimates; position is for scheduling, never watch credit. */
    fun snapshot(key: String, snapshot: PlayerSnapshot, skips: SkipPlan) {
        val old = state.value
        val current = old.current ?: return
        if (!old.active || current.key != key) return
        val updated = if (snapshot.durationMs > 0) current.copy(durationMs = snapshot.durationMs, estimated = false,
            episode = current.episode.copy(opening = skips.opening, ending = skips.ending)) else current
        val position = if (snapshot.ready || snapshot.ended) snapshot.positionMs else old.positionMs
        if (updated != current || position / 1000 != old.positionMs / 1000) {
            mutable.update { if (it.active && it.current?.key == key) it.copy(queue = it.queue.map { item -> if (item.key == key) updated else item }, positionMs = position) else it }
            if (updated != current || position / 5000 != old.positionMs / 5000) save()
        }
    }

    /** The deadline can pause the decoder even when Compose has stopped drawing in the background. */
    fun bind(player: NativePlayer): () -> Unit {
        val token = Any()
        binding.value = Binding(token, player::pause)
        if (state.value.phase != FlowPhase.Running) pausePlayer()
        return { binding.update { if (it?.token === token) null else it } }
    }

    private fun pausePlayer() {
        val current = binding.value ?: return
        scope.launch(playerDispatcher) { if (binding.value?.token === current.token) runCatching(current.pause) }
    }

    private fun finish(reason: FlowFinish) {
        mutable.update { if (it.active) it.copy(phase = FlowPhase.Finished, finish = reason,
            remainingMs = if (reason == FlowFinish.TimeLimit) 0 else it.remainingMs, awaitingNext = false) else it }
        ticker?.cancel()
        pausePlayer()
        save()
    }

    private fun remainingQueue(current: FlowState): List<FlowEpisode> = current.queue.drop(current.index).filterNot { it.key in current.finishedKeys }
        .map { if (it.key == current.current?.key) it.copy(startPositionMs = current.positionMs) else it }

    suspend fun flush() = writeLock.withLock {
        val current = state.value
        val queue = if (current.phase == FlowPhase.Draft) current.queue else remainingQueue(current)
        disk.write("watch-flow.json", AppJson.encodeToString(SavedFlow.serializer(), SavedFlow(current.options, queue)))
    }

    private fun save() { if (state.value.loaded) scope.launch { flush() } }
}

expect fun flowTimeLabel(millis: Long): String
