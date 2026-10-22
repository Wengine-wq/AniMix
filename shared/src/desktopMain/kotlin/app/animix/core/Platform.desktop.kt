package app.animix.core

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File

actual object Platform {
    private val os = System.getProperty("os.name").orEmpty()

    actual val name: String = when {
        os.startsWith("Windows", ignoreCase = true) -> "Windows"
        os.startsWith("Mac", ignoreCase = true) -> "macOS"
        else -> "Linux"
    }

    private val appDir: File by lazy {
        val base = when (name) {
            "Windows" -> System.getenv("LOCALAPPDATA")?.let(::File)
            "macOS" -> File(System.getProperty("user.home"), "Library/Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
        } ?: File(System.getProperty("user.home"))
        // ANIMIX_HOME points a separate copy (tests, off-screen snapshots) at its own data,
        // so it never touches the user's lists and settings.
        (System.getenv("ANIMIX_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(base, "AniMix3")).apply { mkdirs() }
    }

    actual val dataDir: String get() = File(appDir, "data").apply { mkdirs() }.absolutePath

    actual val cacheDir: String get() = File(appDir, "cache").apply { mkdirs() }.absolutePath
}

actual fun createHttpEngine(): HttpClientEngine = OkHttp.create()

actual fun localDayIndex(millis: Long): Long =
    Math.floorDiv(millis + java.util.TimeZone.getDefault().getOffset(millis), 86_400_000L)

actual fun utcOffsetMillis(millis: Long): Long = java.util.TimeZone.getDefault().getOffset(millis).toLong()
