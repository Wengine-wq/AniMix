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

/** The motherboard from WMI (asked once, a few hundred milliseconds), then the system name. */
private val desktopName: String by lazy {
    val board = if (Platform.name == "Windows") runCatching {
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
            "\$b = Get-CimInstance Win32_BaseBoard; \"\$(\$b.Manufacturer) \$(\$b.Product)\"")
            .redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { process.destroy(); "" } else text
    }.getOrDefault("") else ""
    val clean = board.lines().firstOrNull().orEmpty()
        .replace(Regex("""(?i)\b(?:Corporation|Inc\.?|Co\.?,?\s*Ltd\.?|Computer|Technology)(?=\s|$)"""), "")
        .replace(Regex("""(?i)to be filled by o\.?e\.?m\.?|default string|system product name"""), "")
        .replace(Regex("""\s+"""), " ").trim()
    val system = System.getProperty("os.name").orEmpty().ifBlank { Platform.name }
    if (clean.length >= 3) "$clean · $system" else "Компьютер · $system"
}

actual fun deviceName(): String = desktopName

actual fun localDayIndex(millis: Long): Long =
    Math.floorDiv(millis + java.util.TimeZone.getDefault().getOffset(millis), 86_400_000L)

actual fun utcOffsetMillis(millis: Long): Long = java.util.TimeZone.getDefault().getOffset(millis).toLong()
