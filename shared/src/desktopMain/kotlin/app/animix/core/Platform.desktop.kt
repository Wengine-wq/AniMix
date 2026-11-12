package app.animix.core

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File

actual object Platform {
    private val os = System.getProperty("os.name").orEmpty()

    actual val refractiveGlass: Boolean = true
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
        // Encoded, so Java's argument quoting cannot break the script (it did: a localized parse error
        // in the console code page became the device name); UTF-8 out, errors kept apart and discarded.
        val script = "\$ProgressPreference = 'SilentlyContinue'; [Console]::OutputEncoding = [Text.Encoding]::UTF8; " +
            "\$b = Get-CimInstance Win32_BaseBoard -ErrorAction Stop; Write-Output (\$b.Manufacturer + ' ' + \$b.Product)"
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val text = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        when {
            !process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) -> { process.destroy(); "" }
            process.exitValue() != 0 -> ""
            else -> text
        }
    }.getOrDefault("") else ""
    val clean = board.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#<") && !it.startsWith("<") }.orEmpty()
        .replace(Regex("""(?i)\b(?:Corporation|Inc\.?|Co\.?,?\s*Ltd\.?|Computer|Technology)(?=\s|$)"""), "")
        .replace(Regex("""(?i)to be filled by o\.?e\.?m\.?|default string|system product name"""), "")
        .replace(Regex("""\s+"""), " ").trim()
        // A board name is plain letters, digits and a few signs; anything else is not a name.
        .takeIf { it.matches(Regex("""[\p{L}\p{N} .,()/+&_-]+""")) && '�' !in it }.orEmpty()
    val system = System.getProperty("os.name").orEmpty().ifBlank { Platform.name }
    if (clean.length >= 3) "$clean · $system" else "Компьютер · $system"
}

actual fun deviceName(): String = desktopName

actual fun localDayIndex(millis: Long): Long =
    Math.floorDiv(millis + java.util.TimeZone.getDefault().getOffset(millis), 86_400_000L)

actual fun utcOffsetMillis(millis: Long): Long = java.util.TimeZone.getDefault().getOffset(millis).toLong()
