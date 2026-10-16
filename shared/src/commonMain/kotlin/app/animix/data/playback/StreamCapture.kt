package app.animix.data.playback

import io.ktor.http.Url
import kotlinx.coroutines.flow.Flow

/** Browser observations, not decoded DRM or manufactured CDN tokens. */
data class StreamCandidate(val value: String, val userAgent: String)

interface StreamCapture {
    /** Closing collection must stop and dispose the browser, including on cancellation. */
    fun candidates(iframeUrl: String): Flow<StreamCandidate>
}

expect fun createStreamCapture(): StreamCapture
internal expect fun resolveMediaReference(base: String, reference: String): String

class StreamResolutionException(message: String) : Exception(message)

internal const val EmbedReferer = "https://yani.tv/"
internal const val MediaReferer = "https://kodikapi.com/"

internal fun normalizeStreamUrl(raw: String): String = raw.trim().trim('"', '\'')
    .replace("\\/", "/").replace("&amp;", "&")
    .let { if (it.startsWith("//")) "https:$it" else it }

internal fun httpUrlOrNull(raw: String): Url? = runCatching {
    Url(normalizeStreamUrl(raw)).takeIf {
        it.protocol.name in setOf("http", "https") && it.host.isNotBlank() && it.user.isNullOrEmpty() && it.password.isNullOrEmpty()
    }
}.getOrNull()

internal fun isMediaUrl(raw: String): Boolean = httpUrlOrNull(raw)?.let {
    val path = it.encodedPath.lowercase()
    (path.endsWith(".m3u8") || path.endsWith(".mp4")) &&
        !Regex("(?:^|/)(?:ads?|advert|preroll|promo|trash)(?:/|[._-]|$)").containsMatchIn(path)
} == true

internal fun captureRank(raw: String): Int {
    val sources = StreamPlaylists.parseExpression(raw)
    if (sources.isEmpty()) return -1
    if (sources.size > 1) return 400
    val path = httpUrlOrNull(sources.values.first())?.encodedPath.orEmpty().lowercase()
    return when {
        path.endsWith(".m3u8") && "master" in path -> 350
        path.endsWith(".m3u8") -> 300
        path.endsWith(".mp4") -> 200
        else -> -1
    }
}
