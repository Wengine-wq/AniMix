package app.animix.data.importer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.animix.core.AppJson
import app.animix.core.AppLog
import app.animix.core.Platform
import app.animix.ui.components.SecondaryButton
import app.animix.ui.components.Txt
import app.animix.ui.theme.AniMixTheme
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Opens Shikimori in a small Edge/Chrome app window with its own profile (never the browser
 * profile of the user) and watches it through the loopback DevTools port until it reaches the redirect.
 */
@Composable
actual fun OAuthBrowser(url: String, redirectPrefix: String, onRedirect: (String) -> Unit, onCancel: () -> Unit, service: String) {
    val redirect by rememberUpdatedState(onRedirect)
    val cancel by rememberUpdatedState(onCancel)
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        val result = withContext(Dispatchers.IO) { runCatching { watch(url, redirectPrefix, if (service == "Shikimori") "shikimori-login" else "animix-login") } }
        result.onSuccess { if (it != null) redirect(it) else cancel() }
            .onFailure { problem = it.message ?: "Не удалось открыть окно входа." }
    }
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.widthIn(max = 380.dp).clip(AniMixTheme.shapes.large).background(AniMixTheme.colors.surfaceRaised).padding(20.dp)) {
            Txt("Вход в $service", AniMixTheme.type.heading)
            Spacer(Modifier.height(6.dp))
            Txt(problem ?: "Войдите в открывшемся окне. Оно закроется само, когда $service подтвердит вход.", AniMixTheme.type.caption)
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Отмена", onCancel)
        }
    }
}

/** Looks for [prefix] in every string of every DevTools event (redirect requests and their Location headers). */
private class RedirectListener(private val prefix: String, private val captured: AtomicReference<String?>) : WebSocket.Listener {
    private val text = StringBuilder()

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        text.append(data)
        if (last) {
            val message = text.toString()
            text.setLength(0)
            if (prefix in message) runCatching { find(AppJson.parseToJsonElement(message)) }.getOrNull()?.let { captured.compareAndSet(null, it) }
        }
        webSocket.request(1)
        return null
    }

    private fun find(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.startsWith(prefix) }
        is JsonObject -> element.values.firstNotNullOfOrNull(::find)
        is JsonArray -> element.firstNotNullOfOrNull(::find)
    }
}

private fun browser(): File? {
    val roots = listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA"))
    return roots.flatMap { listOf(File(it, "Microsoft/Edge/Application/msedge.exe"), File(it, "Google/Chrome/Application/chrome.exe")) }
        .firstOrNull { it.isFile }
}

/**
 * The redirect URL, or null when the window was closed.
 *
 * The browser is followed through its DevTools port, not through the launched process: Edge may
 * relaunch itself (a pending update, a new profile) or hand the window to an instance that already
 * uses this profile, and the launcher then exits while the sign-in window stays open.
 */
private suspend fun watch(url: String, redirectPrefix: String, profileName: String): String? {
    val executable = browser() ?: throw IllegalStateException("Для входа установите Microsoft Edge или Google Chrome.")
    // A separate, persistent profile: the service remembers the login next time, the usual browser is untouched.
    val profile = File(Platform.cacheDir, profileName).apply { mkdirs() }
    val portFile = File(profile, "DevToolsActivePort")
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    fun readPort(): Int? = runCatching { portFile.readLines().firstOrNull()?.toIntOrNull() }.getOrNull()
    fun get(port: Int, path: String, method: String = "GET"): String? = runCatching {
        val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(2))
            .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        response.body().takeIf { response.statusCode() in 200..299 }
    }.getOrNull()

    // An instance left from an earlier attempt owns the profile: open the page there instead.
    var port: Int? = readPort()?.takeIf { get(it, "/json/version") != null }
    var process: Process? = null
    if (port != null) {
        AppLog.info("OAuth", "reused_browser")
        get(port, "/json/new?" + url, "PUT") ?: get(port, "/json/new?" + url)
    } else {
        portFile.delete()
        process = ProcessBuilder(
            executable.absolutePath, "--app=$url", "--user-data-dir=${profile.absolutePath}",
            "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
            "--no-first-run", "--no-default-browser-check", "--window-size=520,780",
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }
    // A redirect to a custom scheme (animix://) never becomes a page URL; it shows up in network events.
    val captured = AtomicReference<String?>(null)
    val sockets = mutableMapOf<String, WebSocket>()
    val started = System.currentTimeMillis()
    var misses = 0
    try {
        val deadline = started + 10 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val known = port
            if (known == null) {
                port = readPort()
                // Only a browser that never opened DevTools counts as not started; an early launcher exit does not.
                if (port == null && System.currentTimeMillis() - started > 20_000) {
                    AppLog.info("OAuth", "browser_not_started")
                    return null
                }
            } else {
                val pages = get(known, "/json/list")?.let { body -> runCatching { AppJson.parseToJsonElement(body).jsonArray.map { it.jsonObject } }.getOrNull() }
                if (pages == null) {
                    // The window was closed: DevTools stopped answering for a while.
                    if (++misses >= 6) { AppLog.info("OAuth", "window_closed"); return captured.get() }
                } else {
                    misses = 0
                    pages.mapNotNull { it["url"]?.jsonPrimitive?.content }.firstOrNull { it.startsWith(redirectPrefix) }?.let { return it }
                    captured.get()?.let { return it }
                    if (!redirectPrefix.startsWith("http")) pages.forEach { page ->
                        val id = page["id"]?.jsonPrimitive?.content ?: return@forEach
                        val socket = page["webSocketDebuggerUrl"]?.jsonPrimitive?.content ?: return@forEach
                        if (page["type"]?.jsonPrimitive?.content != "page" || id in sockets) return@forEach
                        runCatching { client.newWebSocketBuilder().buildAsync(URI(socket), RedirectListener(redirectPrefix, captured)).get(3, TimeUnit.SECONDS) }
                            .getOrNull()?.let { ws -> sockets[id] = ws; ws.sendText("{\"id\":1,\"method\":\"Network.enable\"}", true) }
                    }
                }
            }
            delay(300)
        }
        return null
    } finally {
        sockets.values.forEach { runCatching { it.abort() } }
        // Close the sign-in browser through DevTools: the launched process may not be the browser any more.
        port?.let { known ->
            runCatching {
                val browserSocket = AppJson.parseToJsonElement(get(known, "/json/version") ?: error("gone"))
                    .jsonObject["webSocketDebuggerUrl"]?.jsonPrimitive?.content ?: error("no socket")
                val ws = client.newWebSocketBuilder().buildAsync(URI(browserSocket), object : WebSocket.Listener {}).get(3, TimeUnit.SECONDS)
                ws.sendText("{\"id\":1,\"method\":\"Browser.close\"}", true).get(3, TimeUnit.SECONDS)
            }
        }
        process?.let { launched ->
            launched.descendants().use { children -> children.forEach { it.destroy() } }
            launched.destroy()
        }
    }
}

