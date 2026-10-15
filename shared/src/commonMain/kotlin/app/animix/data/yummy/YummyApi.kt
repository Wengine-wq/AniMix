package app.animix.data.yummy

import app.animix.core.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
internal class YummyEnvelope<T>(val response: T? = null)

@Serializable
internal class YummyRemoteIds(
    val shikimori_id: Int? = null,
    val myanimelist_id: Int? = null,
)

@Serializable
internal class YummyPoster(val fullsize: String? = null, val big: String? = null, val medium: String? = null)

@Serializable
internal class YummyNamed(val title: String? = null, val alias: String? = null)

@Serializable
internal class YummyRating(val average: Double? = null, val shikimori_rating: Double? = null)

@Serializable
internal class YummyEpisodes(val count: Int? = null, val aired: Int? = null)

@Serializable
internal class YummyScreenshotSizes(val small: String? = null, val full: String? = null)

@Serializable
internal class YummyScreenshot(val sizes: YummyScreenshotSizes? = null)

@Serializable
internal class YummyMinAge(val title: String? = null)

/** A release as returned by search; the detail call fills in the optional fields. */
@Serializable
internal class YummyRelease(
    val anime_id: Int,
    val title: String = "",
    val year: Int? = null,
    val poster: YummyPoster? = null,
    val remote_ids: YummyRemoteIds? = null,
    val type: YummyNamed? = null,
    val anime_status: YummyNamed? = null,
    val rating: YummyRating? = null,
    val genres: List<YummyNamed>? = null,
    val min_age: YummyMinAge? = null,
    // Detail-only fields.
    val description: String? = null,
    val episodes: YummyEpisodes? = null,
    val duration: Int? = null,
    val studios: List<YummyNamed>? = null,
    val other_titles: List<String>? = null,
    val random_screenshots: List<YummyScreenshot>? = null,
) {
    /** YummyAnime uses 0 for "no Shikimori entry". */
    val shikimoriId: Int? get() = remote_ids?.shikimori_id?.takeIf { it > 0 }
}

@Serializable
internal class YummyAvatars(val small: String? = null, val big: String? = null)

@Serializable
internal class YummyAuthor(val nickname: String? = null, val avatars: YummyAvatars? = null)

@Serializable
internal class YummyReviewRating(val average: Double? = null)

@Serializable
internal class YummyLikes(val likes: Int? = null, val dislikes: Int? = null)

@Serializable
internal class YummyReview(
    val review_id: Long,
    val create_date: Long = 0,
    val rating: YummyReviewRating? = null,
    val nickname: String? = null,
    val author: YummyAuthor? = null,
    val avatar: YummyAvatars? = null,
    val likes: YummyLikes? = null,
    val text_html: String? = null,
    val text_preview: String? = null,
)

@Serializable
internal class YummyReviews(val reviews: List<YummyReview> = emptyList())

@Serializable
internal class YummyVideoData(val player: String? = null, val dubbing: String? = null)

@Serializable
internal class YummySkip(val time: Int? = null, val length: Int? = null)

@Serializable
internal class YummySkips(val opening: YummySkip? = null, val ending: YummySkip? = null)

@Serializable
internal class YummyVideo(
    val video_id: Long? = null,
    val data: YummyVideoData? = null,
    val number: String? = null,
    val iframe_url: String? = null,
    val duration: Int? = null,
    val skips: YummySkips? = null,
)

/** YummyAnime public API (api.yani.tv). */
internal class YummyApi(
    private val http: HttpClient,
    private val baseUrl: String = BASE_URL,
    private val applicationToken: String = APPLICATION_TOKEN,
) {
    suspend fun search(query: String, limit: Int = 15): List<YummyRelease> =
        get("$baseUrl/anime", ListSerializer(YummyRelease.serializer())) {
            parameter("q", query)
            parameter("limit", limit)
        }

    suspend fun anime(animeId: Int): YummyRelease =
        get("$baseUrl/anime/$animeId", YummyRelease.serializer()) {}

    suspend fun reviews(animeId: Int, limit: Int = 20): List<YummyReview> =
        get("$baseUrl/anime/$animeId/reviews", YummyReviews.serializer()) { parameter("limit", limit) }.reviews

    suspend fun videos(animeId: Int): List<YummyVideo> =
        get("$baseUrl/anime/$animeId/videos", ListSerializer(YummyVideo.serializer())) {}

    private suspend fun <T> get(
        url: String,
        serializer: KSerializer<T>,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): T {
        val text = http.get(url) {
            header("X-Application", applicationToken)
            block()
        }.bodyAsText()
        return AppJson.decodeFromString(YummyEnvelope.serializer(serializer), text).response
            ?: throw IllegalStateException("YummyAnime returned no data")
    }

    companion object {
        const val BASE_URL = "https://api.yani.tv"

        /** Public application id that identifies AniMix to YummyAnime; not a secret. */
        const val APPLICATION_TOKEN = "ze645twqfeql6l1u"
    }
}
