package app.animix.data.playback

import app.animix.core.AppJson
import app.animix.core.Platform
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

actual fun createStreamCapture(): StreamCapture = ChromiumStreamCapture()
internal actual fun resolveMediaReference(base: String, reference: String): String = URI(base).resolve(reference).normalize().toASCIIString()

/** Uses an isolated headless Edge/Chrome, never the user's browser profile or windows. */
internal class ChromiumStreamCapture(private val executable: File? = null) : StreamCapture {
    override fun candidates(iframeUrl: String): Flow<StreamCandidate> = flow {
        val browser = executable ?: findBrowser()
            ?: throw StreamResolutionException("Для получения потока установите Microsoft Edge или Google Chrome.")
        val root = File(Platform.cacheDir, "stream-capture").apply { mkdirs() }.toPath().toRealPath()
        val profile = Files.createTempDirectory(root, "session-")
        var process: Process? = null
        var cdp: CdpSession? = null
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        try {
            process = ProcessBuilder(
                browser.absolutePath, "--headless=new", "--remote-debugging-address=127.0.0.1",
                "--remote-debugging-port=0", "--user-data-dir=$profile", "--no-first-run",
                "--no-default-browser-check", "--autoplay-policy=no-user-gesture-required",
                "--disable-background-timer-throttling", "--window-size=960,540", "about:blank",
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val port = withTimeoutOrNull(10_000) {
                val endpointFile = profile.resolve("DevToolsActivePort").toFile()
                while (true) {
                    if (!process.isAlive) throw StreamResolutionException("Не удалось запустить браузер для получения потока.")
                    val value = runCatching { endpointFile.readLines().firstOrNull()?.toIntOrNull() }.getOrNull()
                    if (value != null && value in 1..65535) return@withTimeoutOrNull value
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") 0
            } ?: throw StreamResolutionException("Браузер не запустился за 10 секунд.")
            val endpoint = URI("http://127.0.0.1:$port/json/list")
            val response = client.sendAsync(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString()).await()
            val page = AppJson.parseToJsonElement(response.body()).jsonArray.firstOrNull {
                it.jsonObject["type"]?.jsonPrimitive?.content == "page"
            }?.jsonObject ?: throw StreamResolutionException("Браузер не создал страницу плеера.")
            cdp = CdpSession(client, URI(page.getValue("webSocketDebuggerUrl").jsonPrimitive.content))
            cdp.connect()
            cdp.command("Page.enable")
            cdp.command("Network.enable")
            cdp.command("Runtime.enable")
            cdp.command("Runtime.addBinding", buildJsonObject { put("name", "__animixNativeSend") })
            cdp.command("Page.addScriptToEvaluateOnNewDocument", buildJsonObject { put("source", captureScript) })
            val agent = cdp.command("Runtime.evaluate", buildJsonObject { put("expression", "navigator.userAgent"); put("returnByValue", true) })
                .get("result")?.jsonObject?.get("value")?.jsonPrimitive?.content.orEmpty()
            cdp.command("Page.navigate", buildJsonObject { put("url", iframeUrl); put("referrer", EmbedReferer) })
            val seen = mutableSetOf<String>()
            for (event in cdp.events) {
                val params = event["params"]?.jsonObject ?: continue
                val value = when (event["method"]?.jsonPrimitive?.content) {
                    "Runtime.bindingCalled" -> {
                        if (params["name"]?.jsonPrimitive?.content != "__animixNativeSend") continue
                        val payload = params["payload"]?.jsonPrimitive?.content ?: continue
                        if (payload.length > 65_536) continue
                        runCatching { AppJson.parseToJsonElement(payload).jsonObject["value"]?.jsonPrimitive?.content }.getOrNull()
                    }
                    "Network.requestWillBeSent" -> params["request"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                    else -> null
                } ?: continue
                if (captureRank(value) >= 0 && seen.add(value)) emit(StreamCandidate(value, agent))
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                cdp?.close()
                process?.let { owned ->
                    owned.descendants().use { children -> children.forEach { it.destroy() } }
                    owned.destroy()
                    if (!owned.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) owned.destroyForcibly()
                }
                client.close()
                // Delete only the newly created session directory under our own cache root.
                if (profile.parent == root && profile.fileName.toString().startsWith("session-")) {
                    repeat(3) {
                        runCatching { Files.walk(profile).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
                        if (!Files.exists(profile)) return@repeat
                        delay(100)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun findBrowser(): File? {
        val windows = listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA"))
            .flatMap { base -> listOf(File(base, "Microsoft/Edge/Application/msedge.exe"), File(base, "Google/Chrome/Application/chrome.exe")) }
        return (windows + listOf(File("/usr/bin/microsoft-edge"), File("/usr/bin/google-chrome"), File("/usr/bin/chromium"), File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")))
            .firstOrNull { it.isFile }
    }
}

/** Only loopback CDP is exposed, with fragmented messages and pending commands handled explicitly. */
private class CdpSession(private val client: HttpClient, private val uri: URI) : WebSocket.Listener {
    val events = Channel<JsonObject>(256, BufferOverflow.DROP_OLDEST)
    private val sequence = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val sending = Mutex()
    private val fragments = StringBuilder()
    private lateinit var socket: WebSocket

    suspend fun connect() { socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).buildAsync(uri, this).await() }

    suspend fun command(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject = withTimeoutOrNull(5_000) {
        val id = sequence.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        try {
            val message = buildJsonObject { put("id", id); put("method", method); put("params", params) }
            sending.withLock { socket.sendText(message.toString(), true).await() }
            answer.await()
        } finally { pending.remove(id) }
    } ?: throw StreamResolutionException("Браузер не ответил на команду получения потока.")

    override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        try {
            fragments.append(data)
            if (fragments.length > 2_097_152) throw IllegalStateException("Browser message too large")
            if (last) {
                val message = AppJson.parseToJsonElement(fragments.toString()).jsonObject
                fragments.setLength(0)
                val id = message["id"]?.jsonPrimitive?.intOrNull
                if (id != null) {
                    val task = pending[id]
                    if (message["error"] != null) task?.completeExceptionally(StreamResolutionException("Браузер не выполнил команду получения потока."))
                    else task?.complete(message["result"]?.jsonObject ?: JsonObject(emptyMap()))
                } else if (message["method"]?.jsonPrimitive?.content in setOf("Runtime.bindingCalled", "Network.requestWillBeSent")) {
                    events.trySend(message)
                }
            }
        } catch (_: Exception) { fail() }
        webSocket.request(1)
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? { fail(); return null }
    override fun onError(webSocket: WebSocket, error: Throwable) { fail() }
    private fun fail() {
        val error = StreamResolutionException("Соединение с браузером плеера прервано.")
        pending.values.forEach { it.completeExceptionally(error) }
        events.close(error)
    }

    fun close() {
        if (::socket.isInitialized) socket.abort()
        pending.values.forEach { it.cancel() }
        pending.clear()
        events.cancel()
    }
}
