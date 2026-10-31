package app.animix.data.legacy

import app.animix.core.AppJson
import app.animix.data.library.ListStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One list entry of AniMix 2.x, in the shape of its library API and local cache. */
data class LegacyEntry(val shikimoriId: Int, val status: ListStatus?, val score: Int?, val episodesWatched: Int, val updatedAt: Long)

/** Everything worth bringing over from AniMix 2.x, from either its account or this device. */
data class LegacySnapshot(
    val entries: List<LegacyEntry> = emptyList(),
    /** 2.x achievement id → unlock time in ms. */
    val achievements: Map<String, Long> = emptyMap(),
    /** Playback seconds measured by 2.x, already limited to 24 hours per day where days are known. */
    val watchSeconds: Long = 0,
    val profileName: String? = null,
    /** Episodes the 2.x player marked watched, per Shikimori id. */
    val watchedEpisodes: Map<Int, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = entries.isEmpty() && achievements.isEmpty() && watchSeconds <= 0 && watchedEpisodes.isEmpty()
}

/** Reading AniMix 2.x data. Everything here is untrusted input: bad values are skipped, never thrown. */
object LegacyFormat {
    /** 2.x awards that exist in 3.0 with the same or a stricter condition. */
    val AchievementMap: Map<String, String> = mapOf(
        "download" to "first_download",
        "completed" to "first_completed",
        "marathon" to "marathon",
        "insane" to "ten_hours",
        "titan_wall" to "attack_on_titan",
        "demon_blade" to "demon_slayer",
        "one_piece" to "one_piece",
    )

    fun mapAchievements(legacy: Map<String, Long>): Map<String, Long> =
        legacy.mapNotNull { (id, at) -> AchievementMap[id]?.let { it to at } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, times) -> times.min() }

    fun status(raw: String?): ListStatus? = when (raw?.trim()?.lowercase()) {
        "watching", "rewatching" -> ListStatus.Watching
        "planned" -> ListStatus.Planned
        "completed" -> ListStatus.Completed
        "on_hold" -> ListStatus.OnHold
        "dropped" -> ListStatus.Dropped
        else -> null
    }

    /** Entries of the 2.x library API (`{shikimori_id, status, score, episodes_watched, updated_at}`). */
    fun entries(array: JsonElement?): List<LegacyEntry> = (array as? JsonArray).orEmpty().mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val id = obj.long("shikimori_id")?.toInt()?.takeIf { it > 0 } ?: return@mapNotNull null
        LegacyEntry(id, status(obj.text("status")), obj.long("score")?.toInt()?.takeIf { it in 1..10 },
            (obj.long("episodes_watched") ?: 0).toInt().coerceIn(0, 5000), obj.long("updated_at") ?: 0)
    }

    /** Newest record per title wins when the account cache and the device-only profile both have it. */
    fun merge(vararg lists: List<LegacyEntry>): List<LegacyEntry> =
        lists.toList().flatten().groupBy { it.shikimoriId }.values.map { same -> same.maxBy { it.updatedAt } }

    /**
     * The `shared_preferences.json` of AniMix 2.x on Windows: lists (account cache and device-only
     * profile), unlocked awards, daily playback seconds, watched episodes and the profile name.
     */
    fun parsePreferences(text: String): LegacySnapshot {
        val root = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return LegacySnapshot()
        fun value(key: String): JsonElement? = root["flutter.$key"]
        fun embedded(key: String): JsonElement? = (value(key) as? JsonPrimitive)?.contentOrNull
            ?.let { runCatching { AppJson.parseToJsonElement(it) }.getOrNull() }
        // The achievement service of 2.x also kept every list status, and those keys survive sign-out and
        // a cleared account cache. They are the oldest source: any real list record wins over them.
        val tracked = root.mapNotNull { (key, element) ->
            val id = key.removePrefix("flutter.local_achievements_v1_status_").takeIf { it != key }?.toIntOrNull()?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val status = status((element as? JsonPrimitive)?.contentOrNull) ?: return@mapNotNull null
            LegacyEntry(id, status, null, 0, updatedAt = -1)
        }
        val entries = merge(entries(embedded("animix_library_cache_v2")), entries(embedded("local_library_v1")), tracked)
        val awards = root.mapNotNull { (key, element) ->
            val id = key.removePrefix("flutter.local_achievements_v1_").takeIf { it != key } ?: return@mapNotNull null
            if (id.startsWith("status_") || id.startsWith("daily_") || id == "library_ids") return@mapNotNull null
            (element as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let { id to it }
        }.toMap()
        val seconds = root.entries.sumOf { (key, element) ->
            if (!key.startsWith("flutter.usage_seconds_v1_")) 0L
            else ((element as? JsonPrimitive)?.longOrNull ?: 0L).coerceIn(0, SECONDS_PER_DAY)
        }
        val watched = HashMap<Int, Int>()
        for ((key, element) in root) {
            val id = when {
                key.startsWith("flutter.watched_eps_v2_") -> key.removePrefix("flutter.watched_eps_v2_")
                key.startsWith("flutter.watched_episodes_") -> key.removePrefix("flutter.watched_episodes_")
                else -> continue
            }.toIntOrNull()?.takeIf { it > 0 } ?: continue
            val numbers = (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
            // A list of numbers proves the episodes up to its longest prefix from 1, not gaps in between.
            val set = numbers.filter { it == kotlin.math.floor(it) }.map { it.toInt() }.toSet()
            var prefix = 0
            while ((prefix + 1) in set) prefix++
            if (prefix > 0) watched[id] = maxOf(watched[id] ?: 0, prefix)
        }
        val name = listOf("local_profile_v1", "animix_profile_cache_v2").firstNotNullOfOrNull { key ->
            (embedded(key) as? JsonObject)?.text("display_name")?.trim()?.takeIf { it.isNotEmpty() }
        }
        return LegacySnapshot(entries, awards, seconds, name, watched)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private const val SECONDS_PER_DAY = 86_400L
}
