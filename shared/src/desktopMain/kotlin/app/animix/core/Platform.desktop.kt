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
        File(base, "AniMix3").apply { mkdirs() }
    }

    actual val dataDir: String get() = File(appDir, "data").apply { mkdirs() }.absolutePath

    actual val cacheDir: String get() = File(appDir, "cache").apply { mkdirs() }.absolutePath
}

actual fun createHttpEngine(): HttpClientEngine = OkHttp.create()
