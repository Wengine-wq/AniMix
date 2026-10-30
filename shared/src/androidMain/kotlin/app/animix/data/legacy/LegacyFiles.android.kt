package app.animix.data.legacy

import android.content.Context
import app.animix.core.AndroidPlatform
import app.animix.core.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * AniMix 2.x shares this package name, so after installing over it its Flutter preferences stay in
 * this app's private storage. They are converted to the JSON shape of the Windows file. Elsewhere
 * (a separate debug package, a fresh install) there is nothing here and only the account remains.
 */
actual fun legacyPreferencesText(): String? {
    val saved = AndroidPlatform.context.getSharedPreferences(FLUTTER_PREFERENCES, Context.MODE_PRIVATE).all
    if (saved.isEmpty()) return null
    return buildJsonObject {
        for ((key, value) in saved) when (value) {
            is Long -> put(key, JsonPrimitive(value))
            is Int -> put(key, JsonPrimitive(value))
            is Boolean -> put(key, JsonPrimitive(value))
            // Lists are "<prefix>!<json>" in current plugin versions; the older serialized form is skipped.
            is String -> if (value.startsWith(LIST_PREFIX)) {
                if (value.startsWith("$LIST_PREFIX!")) runCatching { AppJson.parseToJsonElement(value.removePrefix("$LIST_PREFIX!")) as? JsonArray }
                    .getOrNull()?.let { put(key, it) }
            } else put(key, JsonPrimitive(value))
        }
    }.toString()
}

private const val FLUTTER_PREFERENCES = "FlutterSharedPreferences"
private const val LIST_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
