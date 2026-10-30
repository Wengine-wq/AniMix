package app.animix.data.pulse

import app.animix.core.Platform
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun pulseApiKey(): String? = withContext(Dispatchers.IO) {
    System.getenv("ANIMIX_POLZA_API_KEY")?.trim()?.takeIf { it.isNotEmpty() } ?: runCatching {
        val path = System.getenv("ANIMIX_POLZA_KEY_FILE")?.let(::File) ?: File(Platform.dataDir, "../private/polza.key")
        path.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
