package app.animix.data.shikimori

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal class GraphQlRequest(val query: String, val variables: JsonObject? = null)

@Serializable
internal class GraphQlError(val message: String)

@Serializable
internal class AnimesResponse(val data: AnimesData? = null, val errors: List<GraphQlError>? = null)

@Serializable
internal class GenresResponse(val data: GenresData? = null, val errors: List<GraphQlError>? = null)

@Serializable
internal class AnimesData(val animes: List<AnimeDto> = emptyList())

@Serializable
internal class GenresData(val genres: List<GenreDto> = emptyList())

@Serializable
internal class IncompleteDate(val year: Int? = null, val date: String? = null)

@Serializable
internal class PosterDto(
    val originalUrl: String? = null,
    val mainUrl: String? = null,
    val main2xUrl: String? = null,
)

@Serializable
internal class GenreDto(val id: String, val russian: String? = null, val name: String? = null, val kind: String? = null)

@Serializable
internal class StudioDto(val name: String)

@Serializable
internal class ScreenshotDto(val originalUrl: String? = null, val x332Url: String? = null)

@Serializable
internal class TopicDto(val id: String? = null, val commentsCount: Int? = null)

/** The REST shape of an anime (used by /similar). */
@Serializable
internal class RestImageDto(val original: String? = null, val preview: String? = null)

@Serializable
internal class RestAnimeDto(
    val id: Int,
    val name: String = "",
    val russian: String? = null,
    val image: RestImageDto? = null,
    val kind: String? = null,
    val score: String? = null,
    val status: String? = null,
    val episodes: Int = 0,
    val episodes_aired: Int = 0,
    val aired_on: String? = null,
)

@Serializable
internal class RestUserDto(val nickname: String = "", val image: Map<String, String>? = null, val avatar: String? = null)

@Serializable
internal class CommentDto(
    val id: Long,
    val body: String = "",
    val html_body: String? = null,
    val created_at: String = "",
    val user: RestUserDto? = null,
    val is_offtopic: Boolean = false,
)

@Serializable
internal class RelatedDto(val relationKind: String? = null, val relationText: String? = null, val anime: AnimeDto? = null)

@Serializable
internal class AnimeDto(
    val id: String,
    val name: String = "",
    val russian: String? = null,
    val english: String? = null,
    val kind: String? = null,
    val score: Double? = null,
    val status: String? = null,
    val episodes: Int = 0,
    val episodesAired: Int = 0,
    val airedOn: IncompleteDate? = null,
    val poster: PosterDto? = null,
    val genres: List<GenreDto>? = null,
    // Detail-only fields.
    val description: String? = null,
    val duration: Int? = null,
    val rating: String? = null,
    val nextEpisodeAt: String? = null,
    val studios: List<StudioDto>? = null,
    val screenshots: List<ScreenshotDto>? = null,
    val related: List<RelatedDto>? = null,
    val topic: TopicDto? = null,
)
