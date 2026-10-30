package app.animix.data.legacy

import java.io.File

/**
 * AniMix 2.x (Flutter) keeps its preferences in %APPDATA%/com.example/animix/shared_preferences.json.
 * A separate copy started with ANIMIX_HOME reads only `<ANIMIX_HOME>/legacy/shared_preferences.json`.
 */
actual fun legacyPreferencesText(): String? {
    val isolated = System.getenv("ANIMIX_HOME")?.takeIf { it.isNotBlank() }
    val file = if (isolated != null) File(isolated, "legacy/shared_preferences.json")
    else System.getenv("APPDATA")?.let { File(it, "com.example/animix/shared_preferences.json") }
    return file?.takeIf { it.isFile && it.length() in 1..64L * 1024 * 1024 }?.readText()
}
