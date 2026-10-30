package app.animix.data.pulse

import app.animix.core.AndroidPlatform
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun pulseApiKey(): String? = withContext(Dispatchers.IO) {
    runCatching { File(AndroidPlatform.context.noBackupFilesDir, "polza.key").takeIf { it.isFile }
        ?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
}
