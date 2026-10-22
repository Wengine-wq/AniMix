package app.animix.data.downloads

import app.animix.core.AppLog
import app.animix.core.AppJson
import app.animix.core.nowMillis
import app.animix.data.playback.KodikResolver
import app.animix.model.Anime
import app.animix.model.Dubbing
import app.animix.model.Episode
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable enum class DownloadStatus { Queued, Resolving, Downloading, Assembling, Paused, Failed, Completed }

@Serializable data class DownloadItem(
    val id: String,
    val anime: Anime,
    val studio: String,
    val dubbingKey: String,
    val episode: Episode,
    val quality: String,
    val status: DownloadStatus = DownloadStatus.Queued,
    val completedParts: Int = 0,
    val totalParts: Int = 0,
    val downloadedBytes: Long = 0,
    val file: String? = null,
    val error: String? = null,
) {
    val active get() = status in setOf(DownloadStatus.Resolving, DownloadStatus.Downloading, DownloadStatus.Assembling)
}

/** Persistent single-worker queue: resolve each episode only when it reaches the head. */
class DownloadManager(
    private val scope: CoroutineScope,
    private val resolver: KodikResolver,
    http: HttpClient,
    val files: OfflineFiles,
    assembler: MediaAssembler = createMediaAssembler(),
    /** Pauses between attempts after a network drop (screen off, Wi-Fi switching): about four minutes in all. */
    private val retryDelaysMs: List<Long> = listOf(3_000, 8_000, 15_000, 30_000, 60_000, 120_000),
    parallelism: () -> Int = { 3 },
    private val onCompleted: (String) -> Unit = {},
) {
    private val pipeline = HlsDownload(http, files, assembler, parallelism)
    private val mutable = MutableStateFlow<List<DownloadItem>>(emptyList())
    val state = mutable.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private val lock = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private data class Progress(val id: String, val done: Int, val total: Int, val bytes: Long, val muxing: Boolean)
    private val progressUpdates = Channel<Progress>(Channel.CONFLATED)
    private var activeId: String? = null
    private var activeJob: Job? = null
    private var lastPersist = 0L

    init {
        scope.launch(Dispatchers.IO) {
            if (!awaitStorage()) return@launch
            for (event in progressUpdates) {
                try {
                    update(event.id) { current ->
                        if (current.status in setOf(DownloadStatus.Paused, DownloadStatus.Completed, DownloadStatus.Failed)) current else current.copy(
                            status = if (event.muxing) DownloadStatus.Assembling else DownloadStatus.Downloading,
                            completedParts = event.done, totalParts = event.total, downloadedBytes = event.bytes,
                        )
                    }
                    mutable.value.firstOrNull { it.id == event.id }?.let { item ->
                        downloadProgress(item.title, if (event.total > 0) (event.done * 100 / event.total).coerceIn(0, 100) else null)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { mutableNotice.value = "Не удалось сохранить очередь. Проверьте свободное место."; activeJob?.cancel() }
            }
        }
        scope.launch(Dispatchers.IO) {
            if (!awaitStorage()) return@launch
            for (signal in wake) {
                while (true) {
                    val job = lock.withLock {
                        val item = mutable.value.firstOrNull { it.status == DownloadStatus.Queued } ?: return@withLock null
                        mutable.value = mutable.value.map { if (it.id == item.id) it.copy(status = DownloadStatus.Resolving, error = null) else it }
                        scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { process(item) }.also { activeId = item.id; activeJob = it }
                    } ?: break
                    job.start(); job.join()
                    lock.withLock { if (activeJob === job) { activeJob = null; activeId = null } }
                }
            }
        }
    }

    suspend fun load() = withContext(Dispatchers.IO) {
      try {
        lock.withLock {
            if (ready.isCompleted) return@withLock
            files.mkdir("work")
            val raw = files.read("queue.json")
            val restored = if (raw == null) emptyList() else try {
                AppJson.decodeFromString<List<DownloadItem>>(raw)
            } catch (_: Exception) {
                files.move("queue.json", "queue.corrupt-${nowMillis()}.json")
                mutableNotice.value = "Не удалось прочитать очередь. Её копия сохранена; файлы загрузок не удалены."
                emptyList()
            }
            mutable.value = restored.map { item ->
                when {
                    item.status == DownloadStatus.Completed && (item.file == null || !files.exists(item.file)) -> item.copy(status = DownloadStatus.Failed, error = "Скачанный файл отсутствует.")
                    item.active -> item.copy(status = DownloadStatus.Queued)
                    else -> item
                }
            }
            persist()
            mutable.value.filter { it.status == DownloadStatus.Completed }.forEach { onCompleted(it.id) }
            ready.complete(Unit)
            wake.trySend(Unit)
        }
      } catch (cancel: CancellationException) {
          ready.cancel(cancel)
          throw cancel
      } catch (error: Exception) {
          mutableNotice.value = "Не удалось открыть хранилище загрузок. Проверьте свободное место."
          ready.completeExceptionally(error)
      }
    }

    private suspend fun awaitStorage(): Boolean = try { ready.await(); true }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }

    suspend fun enqueueSeason(anime: Anime, dubbing: Dubbing, quality: String = "720p") = enqueue(anime, dubbing, dubbing.episodes, quality)
    suspend fun enqueueEpisode(anime: Anime, dubbing: Dubbing, episode: Episode, quality: String) = enqueue(anime, dubbing, listOf(episode), quality)
    suspend fun enqueueEpisodes(anime: Anime, dubbing: Dubbing, episodes: List<Episode>, quality: String) = enqueue(anime, dubbing, episodes, quality)

    private suspend fun enqueue(anime: Anime, dubbing: Dubbing, episodes: List<Episode>, quality: String) = withContext(Dispatchers.IO) {
        ready.await()
        require(dubbing.isKodik) { "Загрузка этого источника пока не поддерживается." }
        lock.withLock {
            val existing = mutable.value.associateBy { it.id }
            val additions = episodes.map { episode ->
                val identity = "${anime.key}|${dubbing.key}|${episode.number}|$quality"
                // Stable file-safe FNV-1a ID; never use a remote title or URL as a disk path.
                val hash = identity.encodeToByteArray().fold(14695981039346656037uL) { value, byte -> (value xor byte.toUByte().toULong()) * 1099511628211uL }
                DownloadItem("episode-${hash.toString(16)}", anime, dubbing.studio, dubbing.key, episode, quality)
            }.filter { it.id !in existing }.distinctBy { it.id }
            mutable.value += additions
            persist()
        }
        wake.trySend(Unit)
    }

    suspend fun pause(id: String) = withContext(Dispatchers.IO) {
        ready.await()
        val job = lock.withLock {
            mutable.value = mutable.value.map { if (it.id == id && it.status != DownloadStatus.Completed) it.copy(status = DownloadStatus.Paused) else it }
            persist()
            activeJob.takeIf { activeId == id }
        }
        job?.cancelAndJoin()
    }
    suspend fun resume(id: String) = withContext(Dispatchers.IO) {
        ready.await()
        lock.withLock {
            mutable.value = mutable.value.map { if (it.id == id && it.status in setOf(DownloadStatus.Paused, DownloadStatus.Failed)) it.copy(status = DownloadStatus.Queued, error = null) else it }
            persist()
        }
        wake.trySend(Unit)
    }
    suspend fun pauseAll() = withContext(Dispatchers.IO) {
        ready.await()
        val job = lock.withLock {
            mutable.value = mutable.value.map { if (it.status == DownloadStatus.Queued || it.active) it.copy(status = DownloadStatus.Paused) else it }
            persist(); activeJob
        }
        job?.cancelAndJoin()
        downloadActivity(false) {}
    }
    /** Desktop exit: do not wait for initialization if the app closes during startup. */
    suspend fun shutdown() { if (ready.isCompleted && !ready.isCancelled) pauseAll() }
    suspend fun resumeAll() { ready.await(); mutable.value.filter { it.status in setOf(DownloadStatus.Paused, DownloadStatus.Failed) }.forEach { resume(it.id) } }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        pause(id)
        files.deleteTree("work/$id"); files.delete("$id.mp4"); files.delete("$id.assembling.mp4")
        lock.withLock { mutable.value = mutable.value.filterNot { it.id == id }; persist() }
    }
    fun offlineUri(item: DownloadItem): String? = item.file?.takeIf { item.status == DownloadStatus.Completed && files.exists(it) }?.let { localMediaUri(files.absolute(it)) }

    private suspend fun update(id: String, immediate: Boolean = false, transform: (DownloadItem) -> DownloadItem) = lock.withLock {
        mutable.value = mutable.value.map { if (it.id == id) transform(it) else it }
        if (immediate || nowMillis() - lastPersist > 1_000) persist()
    }
    private fun persist() { files.write("queue.json", AppJson.encodeToString(mutable.value)); lastPersist = nowMillis() }

    private suspend fun fetch(item: DownloadItem): String {
        for (attempt in 0..2) {
            try {
                val source = resolver.resolve(item.episode.iframeUrl, forceRefresh = attempt > 0)
                currentCoroutineContext().ensureActive()
                return pipeline.download(source, item.id, item.quality) { done, total, bytes, muxing ->
                    progressUpdates.trySend(Progress(item.id, done, total, bytes, muxing))
                }
            } catch (expired: SourceExpired) {
                if (attempt == 2) throw IllegalStateException("Срок действия ссылки истёк. Повторите загрузку.")
            }
        }
        error("unreachable")
    }

    private suspend fun process(item: DownloadItem) {
        try {
            downloadActivity(true) { scope.launch { pauseAll() } }
            if (files.exists("${item.id}.mp4") && files.size("${item.id}.mp4") > 0) {
                update(item.id, true) { it.copy(status = DownloadStatus.Completed, file = "${item.id}.mp4") }
                onCompleted(item.id)
                AppLog.info("Downloads", "episode_completed")
                return
            }
            downloadProgress(item.title, null)
            var output: String? = null
            var failures = 0
            while (output == null) {
                try {
                    output = fetch(item)
                } catch (error: Exception) {
                    // Our own pause/cancel ends here; a timeout inside the resolver is just a failed attempt.
                    currentCoroutineContext().ensureActive()
                    if (error !is CancellationException && (error is IllegalArgumentException || error is IllegalStateException)) throw error
                    // Network drops (screen off, Wi-Fi switching) are retried; parts already saved are kept.
                    if (failures >= retryDelaysMs.size) throw error
                    delay(retryDelaysMs[failures++])
                }
            }
            currentCoroutineContext().ensureActive()
            check(output != null) { "Не удалось сохранить серию." }
            update(item.id, true) { it.copy(status = DownloadStatus.Completed, file = output, downloadedBytes = files.size(output), error = null) }
            onCompleted(item.id)
            AppLog.info("Downloads", "episode_completed")
        } catch (cancel: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                try { update(item.id, true) { if (it.status != DownloadStatus.Completed) it.copy(status = DownloadStatus.Paused) else it } }
                catch (_: Exception) { mutableNotice.value = "Не удалось сохранить очередь. Проверьте свободное место." }
            }
            throw cancel
        } catch (error: Exception) {
            AppLog.failure("Downloads", "episode_failed", error)
            try {
                update(item.id, true) { it.copy(status = DownloadStatus.Failed, error = when (error) {
                    is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf { message -> "http" !in message && message.length < 180 } ?: "Не удалось скачать серию. Повторите попытку."
                    else -> "Не удалось скачать серию. Проверьте соединение и свободное место."
                }) }
            } catch (_: Exception) { mutableNotice.value = "Не удалось сохранить очередь. Проверьте свободное место." }
        } finally { if (mutable.value.none { it.status == DownloadStatus.Queued }) downloadActivity(false) {} }
    }
}


private val DownloadItem.title: String get() = "${anime.title} · Серия ${episode.number}"
