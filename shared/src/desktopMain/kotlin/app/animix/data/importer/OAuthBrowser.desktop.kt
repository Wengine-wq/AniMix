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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Opens Shikimori in a small Edge/Chrome app window with its own profile (never the browser
 * profile of the user) and watches it through the loopback DevTools port until it reaches the redirect.
 */
@Composable
actual fun OAuthBrowser(url: String, redirectPrefix: String, onRedirect: (String) -> Unit, onCancel: () -> Unit) {
    val redirect by rememberUpdatedState(onRedirect)
    val cancel by rememberUpdatedState(onCancel)
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        val result = withContext(Dispatchers.IO) { runCatching { watch(url, redirectPrefix) } }
        result.onSuccess { if (it != null) redirect(it) else cancel() }
            .onFailure { problem = it.message ?: "Не удалось открыть окно входа." }
    }
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.widthIn(max = 380.dp).clip(AniMixTheme.shapes.large).background(AniMixTheme.colors.surfaceRaised).padding(20.dp)) {
            Txt("Вход в Shikimori", AniMixTheme.type.heading)
            Spacer(Modifier.height(6.dp))
            Txt(problem ?: "Войдите в открывшемся окне. Оно закроется само, когда Shikimori подтвердит вход.", AniMixTheme.type.caption)
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Отмена", onCancel)
        }
    }
}

private fun browser(): File? {
    val roots = listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA"))
    return roots.flatMap { listOf(File(it, "Microsoft/Edge/Application/msedge.exe"), File(it, "Google/Chrome/Application/chrome.exe")) }
        .firstOrNull { it.isFile }
}

/** The redirect URL, or null when the window was closed. */
private suspend fun watch(url: String, redirectPrefix: String): String? {
    val executable = browser() ?: throw IllegalStateException("Для входа установите Microsoft Edge или Google Chrome.")
    // A separate, persistent profile: Shikimori remembers the login next time, the usual browser is untouched.
    val profile = File(Platform.cacheDir, "shikimori-login").apply { mkdirs() }
    File(profile, "DevToolsActivePort").delete()
    val process = ProcessBuilder(
        executable.absolutePath, "--app=$url", "--user-data-dir=${profile.absolutePath}",
        "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
        "--no-first-run", "--no-default-browser-check", "--window-size=520,780",
    ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
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
                    AppJson.parseToJsonElement(response.body()).jsonArray.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.content }
                }.getOrNull()
                // The window was closed.
                if (pages == null && !process.isAlive) return null
                pages?.firstOrNull { it.startsWith(redirectPrefix) }?.let { return it }
            }
            delay(300)
        }
        return null
    } finally {
        process.descendants().use { children -> children.forEach { it.destroy() } }
        process.destroy()
    }
}
