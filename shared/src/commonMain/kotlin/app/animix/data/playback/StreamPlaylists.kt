package app.animix.data.playback

import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath

internal object StreamPlaylists {
    private val tagged = Regex("""\[([^\]]+)]\s*(.*?)(?=,\s*\[|$)""")
    private val height = Regex("""(?:^|/)(2160|1440|1080|720|480|360)(?:p)?(?:\.mp4|/|\.m3u8)""", RegexOption.IGNORE_CASE)
    private val attributes = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun parseExpression(raw: String): Map<String, String> {
        val text = normalizeStreamUrl(raw)
        val result = linkedMapOf<String, String>()
        for (match in tagged.findAll(text)) {
            val url = normalizeStreamUrl(match.groupValues[2])
            if (isMediaUrl(url)) {
                val label = Regex("[0-9]+").find(match.groupValues[1])?.value?.let { "${it}p" }
                    ?: match.groupValues[1].trim()
                result[label] = url
            }
        }
        if (result.isEmpty() && isMediaUrl(text)) result[quality(text)] = text
        return result
    }

    fun quality(url: String): String = httpUrlOrNull(url)?.encodedPath?.let { path ->
        height.find(path)?.groupValues?.get(1)?.let { "${it}p" }
    } ?: "Авто"

    fun master(body: String, baseUrl: String): Map<String, String> {
        val lines = body.lineSequence().map { it.trim() }.toList()
        val variants = linkedMapOf<String, Pair<Long, String>>()
        for ((index, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val next = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith('#') } ?: continue
            val props = attributes.findAll(line).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
            val bandwidth = props["BANDWIDTH"]?.toLongOrNull() ?: 0
            val resolution = props["RESOLUTION"]?.substringAfter('x')?.toIntOrNull()
            val label = resolution?.let { "${it}p" } ?: "Поток · ${bandwidth / 1000} кбит/с"
            val url = runCatching { resolveMediaReference(baseUrl, next) }.getOrNull() ?: continue
            if (httpUrlOrNull(url) == null) continue
            if ((variants[label]?.first ?: -1) < bandwidth) variants[label] = bandwidth to url
        }
        return variants.mapValues { it.value.second }
    }

    /** Only the known Kodik pathname pattern; never replace digits in a signed query. */
    fun siblings(url: String): Map<String, String> {
        val parsed = httpUrlOrNull(url) ?: return emptyMap()
        val pattern = Regex("""/(1080|720|480|360)(?=\.mp4(?::hls:|$))""")
        val match = pattern.find(parsed.encodedPath) ?: return emptyMap()
        return listOf(1080, 720, 480, 360).associate { h ->
            val path = parsed.encodedPath.replaceRange(match.range, "/$h")
            "${h}p" to URLBuilder(parsed).apply { encodedPath = path }.buildString()
        }
    }

    fun sorted(sources: Map<String, String>): Map<String, String> = sources.entries.sortedByDescending {
        if (it.key == "Авто") Int.MAX_VALUE else Regex("[0-9]+").find(it.key)?.value?.toIntOrNull() ?: 0
    }.associate { it.toPair() }
}
