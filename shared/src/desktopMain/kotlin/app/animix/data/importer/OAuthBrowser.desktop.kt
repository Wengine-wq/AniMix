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

/** The redirect URL, or null when the window was closed. */
private suspend fun watch(url: String, redirectPrefix: String, profileName: String): String? {
    val executable = browser() ?: throw IllegalStateException("Для входа установите Microsoft Edge или Google Chrome.")
    // A separate, persistent profile: the service remembers the login next time, the usual browser is untouched.
    val profile = File(Platform.cacheDir, profileName).apply { mkdirs() }
    File(profile, "DevToolsActivePort").delete()
    val process = ProcessBuilder(
        executable.absolutePath, "--app=$url", "--user-data-dir=${profile.absolutePath}",
        "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
        "--no-first-run", "--no-default-browser-check", "--window-size=520,780",
    ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    // A redirect to a custom scheme (animix://) never becomes a page URL; it shows up in network events.
    val captured = AtomicReference<String?>(null)
    val sockets = mutableMapOf<String, WebSocket>()
    try {
        var port: Int? = null
        val deadline = System.currentTimeMillis() + 10 * 60_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive && port == null) return null
            if (port == null) {
                port = runCatching { File(profile, "DevToolsActivePort").readLines().firstOrNull()?.toIntOrNull() }.getOrNull()
            } else {
                val pages = runCatching {
                    val response = client.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/list")).timeout(Duration.ofSeconds(2)).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    AppJson.parseToJsonElement(response.body()).jsonArray.map { it.jsonObject }
                }.getOrNull()
                // The window was closed.
                if (pages == null && !process.isAlive) return captured.get()
                pages?.mapNotNull { it["url"]?.jsonPrimitive?.content }?.firstOrNull { it.startsWith(redirectPrefix) }?.let { return it }
                captured.get()?.let { return it }
                if (!redirectPrefix.startsWith("http")) pages?.forEach { page ->
                    val id = page["id"]?.jsonPrimitive?.content ?: return@forEach
                    val socket = page["webSocketDebuggerUrl"]?.jsonPrimitive?.content ?: return@forEach
                    if (page["type"]?.jsonPrimitive?.content != "page" || id in sockets) return@forEach
                    runCatching { client.newWebSocketBuilder().buildAsync(URI(socket), RedirectListener(redirectPrefix, captured)).get(3, TimeUnit.SECONDS) }
                        .getOrNull()?.let { ws -> sockets[id] = ws; ws.sendText("{\"id\":1,\"method\":\"Network.enable\"}", true) }
                }
            }
            delay(300)
        }
        return null
    } finally {
        sockets.values.forEach { runCatching { it.abort() } }
        process.descendants().use { children -> children.forEach { it.destroy() } }
        process.destroy()
    }
}
