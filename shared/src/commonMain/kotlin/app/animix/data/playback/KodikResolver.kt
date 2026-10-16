package app.animix.data.playback

import app.animix.core.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareHead
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.utils.io.readBuffer
import kotlinx.io.readString

/** Headers must accompany both the manifest AND its segments in the eventual native player. */
data class ResolvedStream(val qualities: Map<String, String>, val headers: Map<String, String>)

class KodikResolver(
    private val http: HttpClient,
    private val capture: StreamCapture = createStreamCapture(),
    private val clock: () -> Long = ::nowMillis,
) {
    private data class Cached(val stream: ResolvedStream, val savedAt: Long)
    private val cache = linkedMapOf<String, Cached>()
    private val cacheLock = Mutex()

    @OptIn(FlowPreview::class)
    suspend fun resolve(iframeUrl: String, forceRefresh: Boolean = false): ResolvedStream {
        val key = httpUrlOrNull(iframeUrl)?.toString()
            ?: throw StreamResolutionException("Некорректная ссылка на источник видео")
        try {
            return withTimeoutOrNull(28_000) {
                val saved = cacheLock.withLock {
                    cache[key]?.takeIf { !forceRefresh && clock() - it.savedAt in 0..1_200_000 }
                        .also { cache.remove(key) }
                }
                if (saved != null && reachable(saved.stream)) {
                    remember(key, saved.stream, saved.savedAt)
                    return@withTimeoutOrNull saved.stream
                }
                val result = captureAndResolve(key)
                remember(key, result)
                result
            } ?: throw StreamResolutionException("Плеер не отдал прямую ссылку за 28 секунд. Попробуйте ещё раз.")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: StreamResolutionException) {
            throw error
        } catch (_: Exception) {
            throw StreamResolutionException("Не удалось получить поток. Проверьте соединение и повторите попытку.")
        }
    }

    suspend fun clear() = cacheLock.withLock { cache.clear() }

    private suspend fun remember(key: String, stream: ResolvedStream, savedAt: Long = clock()) = cacheLock.withLock {
        cache.remove(key)
        cache[key] = Cached(stream, savedAt)
        while (cache.size > 80) cache.remove(cache.keys.first())
    }

    /** One owner for ranking and retries; lower-ranked observations survive a failed master. */
    @OptIn(FlowPreview::class)
    private suspend fun captureAndResolve(key: String): ResolvedStream = coroutineScope {
        val observations = capture.candidates(key).buffer(64).produceIn(this)
        val pending = linkedMapOf<String, StreamCandidate>()
        val seen = mutableSetOf<String>()
        fun enqueue(candidate: StreamCandidate) {
            if (captureRank(candidate.value) >= 0 && seen.add(candidate.value)) pending[candidate.value] = candidate
        }
        try {
            while (true) {
                if (pending.isEmpty()) {
                    while (pending.isEmpty()) enqueue(observations.receive())
                    val grace = if (pending.values.maxOf { captureRank(it.value) } >= 350) 150L else 500L
                    withTimeoutOrNull(grace) {
                        while (true) {
                            val next = observations.receiveCatching()
                            next.exceptionOrNull()?.let { throw it }
                            if (next.isClosed) break
                            enqueue(next.getOrThrow())
                        }
                    }
                }
                val candidate = pending.values.maxBy { captureRank(it.value) }
                pending.remove(candidate.value)
                val headers = mapOf("Referer" to MediaReferer, HttpHeaders.UserAgent to candidate.userAgent)
                val sources = qualities(candidate.value, headers)
                if (sources.isNotEmpty()) return@coroutineScope ResolvedStream(sources, headers)
                // Keep observations arriving during HTTP validation, including a usable lower rendition.
                while (true) {
                    val next = observations.tryReceive().getOrNull() ?: break
                    enqueue(next)
                }
            }
            @Suppress("UNREACHABLE_CODE") error("No source")
        } finally { observations.cancel() }
    }

    private suspend fun reachable(stream: ResolvedStream): Boolean = coroutineScope {
        stream.qualities.values.distinct().map { url -> async { probe(url, stream.headers) != null } }.awaitAll().any { it }
    }

    private suspend fun qualities(expression: String, headers: Map<String, String>): Map<String, String> = coroutineScope {
        val parsed = StreamPlaylists.parseExpression(expression)
        val found = linkedMapOf<String, String>()
        val verified = parsed.map { (label, url) -> async {
            val verified = probe(url, headers)
            if (verified == null) emptyMap() else buildMap {
                put(label, verified.url)
                if (verified.body.startsWith("#EXTM3U")) {
                    val master = StreamPlaylists.master(verified.body, verified.url)
                    if (master.isNotEmpty()) { put("Авто", verified.url); putAll(master) }
                }
            }
        } }.awaitAll()
        verified.forEach(found::putAll)
        val adaptive = found.size > 1 && "Авто" in found
        if (parsed.size == 1 && found.isNotEmpty() && !adaptive) {
            val siblings = StreamPlaylists.siblings(parsed.values.first())
            siblings.filterValues { it !in found.values }.map { (label, url) -> async {
                probe(url, headers)?.let { label to it.url }
            } }.awaitAll().filterNotNull().forEach { (label, url) -> found[label] = url }
        }
        StreamPlaylists.sorted(found)
    }

    /** MP4 is only HEAD-probed: never download a whole episode to validate its URL. */
    private data class Verified(val body: String, val url: String)

    private suspend fun readProbe(response: HttpResponse, isHls: Boolean): Verified? {
        if (response.status.value !in 200..299) return null
        if (!isHls) return if (response.headers[HttpHeaders.ContentType]?.contains("text/html", true) == true) null
            else Verified("mp4", response.call.request.url.toString())
        val body = response.bodyAsChannel().readBuffer(1_048_577L).readString()
            .trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        return if (body.length <= 1_048_576 && body.startsWith("#EXTM3U")) Verified(body, response.call.request.url.toString()) else null
    }

    private suspend fun probe(url: String, requestHeaders: Map<String, String>): Verified? = try {
        withTimeoutOrNull(5_000) {
            val isHls = httpUrlOrNull(url)?.encodedPath?.lowercase()?.endsWith(".m3u8") == true
            if (isHls) http.prepareGet(url) {
                headers { requestHeaders.forEach { (k, v) -> set(k, v) }; set(HttpHeaders.Accept, "application/vnd.apple.mpegurl, */*") }
            }.execute { readProbe(it, true) }
            else http.prepareHead(url) { headers { requestHeaders.forEach { (k, v) -> set(k, v) } } }.execute { readProbe(it, false) }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) { null }
}
