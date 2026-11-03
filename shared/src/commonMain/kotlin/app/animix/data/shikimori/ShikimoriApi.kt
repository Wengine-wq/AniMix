package app.animix.data.shikimori

import app.animix.core.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import kotlinx.serialization.builtins.ListSerializer
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Shikimori catalog through its public GraphQL endpoint. No account needed. */
internal class ShikimoriApi(private val http: HttpClient, private val baseUrl: String = BASE_URL) {

    suspend fun animes(query: CatalogQuery): List<AnimeDto> {
        val variables = buildMap {
            put("page", JsonPrimitive(query.page))
            put("limit", JsonPrimitive(query.limit))
            put("order", JsonPrimitive(query.order.apiValue))
            query.kinds.takeIf { it.isNotEmpty() }?.let { put("kind", JsonPrimitive(it.joinToString(","))) }
            query.status?.let { put("status", JsonPrimitive(it)) }
            query.season?.let { put("season", JsonPrimitive(it)) }
            query.genreIds.takeIf { it.isNotEmpty() }?.let { put("genre", JsonPrimitive(it.joinToString(","))) }
            query.search?.takeIf { it.isNotBlank() }?.let { put("search", JsonPrimitive(it.trim())) }
        }
        val response = AppJson.decodeFromString(AnimesResponse.serializer(), post(LIST_QUERY, JsonObject(variables)))
        response.errors?.firstOrNull()?.let { throw ShikimoriException(it.message) }
        return response.data?.animes ?: throw ShikimoriException("Shikimori returned no data")
    }

    suspend fun anime(id: Int): AnimeDto? {
        val variables = JsonObject(mapOf("ids" to JsonPrimitive(id.toString())))
        val response = AppJson.decodeFromString(AnimesResponse.serializer(), post(DETAIL_QUERY, variables))
        response.errors?.firstOrNull()?.let { throw ShikimoriException(it.message) }
        return response.data?.animes?.firstOrNull()
    }

    /** Cards for up to 50 known ids in one request (lists brought over from elsewhere). */
    suspend fun animesByIds(ids: List<Int>): List<AnimeDto> {
        if (ids.isEmpty()) return emptyList()
        val variables = JsonObject(mapOf("ids" to JsonPrimitive(ids.take(50).joinToString(","))))
        val response = AppJson.decodeFromString(AnimesResponse.serializer(), post(BY_IDS_QUERY, variables))
        response.errors?.firstOrNull()?.let { throw ShikimoriException(it.message) }
        return response.data?.animes.orEmpty()
    }

    /**
     * Several searches in one GraphQL request (aliased fields), with the extra names and dates
     * that matching titles from other services needs. Keeps the import within Shikimori's rate limit.
     */
    suspend fun searchMany(queries: List<String>, limit: Int = 8): List<List<AnimeDto>> {
        if (queries.isEmpty()) return emptyList()
        val declarations = queries.indices.joinToString(", ") { "\$s$it: String" }
        val fields = queries.indices.joinToString(" ") {
            "q$it: animes(search: \$s$it, limit: $limit, censored: false) { $CARD_FIELDS japanese synonyms airedOn { year date } studios { name } }"
        }
        val variables = JsonObject(queries.mapIndexed { index, query -> "s$index" to JsonPrimitive(query) }.toMap())
        val raw = AppJson.parseToJsonElement(post("query($declarations) { $fields }", variables)).jsonObject
        raw["errors"]?.let { throw ShikimoriException("Shikimori не ответил на поиск") }
        val data = raw["data"]?.jsonObject ?: throw ShikimoriException("Shikimori returned no data")
        return queries.indices.map { index ->
            data["q$index"]?.let { AppJson.decodeFromJsonElement(ListSerializer(AnimeDto.serializer()), it) }.orEmpty()
        }
    }

    suspend fun genres(): List<GenreDto> {
        val response = AppJson.decodeFromString(GenresResponse.serializer(), post(GENRES_QUERY, null))
        response.errors?.firstOrNull()?.let { throw ShikimoriException(it.message) }
        return response.data?.genres.orEmpty()
    }

    suspend fun similar(id: Int): List<RestAnimeDto> =
        AppJson.decodeFromString(ListSerializer(RestAnimeDto.serializer()), http.get("$baseUrl/api/animes/$id/similar").bodyAsText())

    /** Comments of a topic, newest first. */
    suspend fun comments(topicId: Int, page: Int, limit: Int): List<CommentDto> =
        AppJson.decodeFromString(
            ListSerializer(CommentDto.serializer()),
            http.get("$baseUrl/api/comments") {
                parameter("commentable_id", topicId)
                parameter("commentable_type", "Topic")
                parameter("page", page)
                parameter("limit", limit)
                parameter("desc", 1)
            }.bodyAsText(),
        )

    /** Generated events of one anime (anons, ongoing, released, episode), newest first. */
    suspend fun animeTopics(id: Int, limit: Int): List<NewsTopicDto> =
        AppJson.decodeFromString(
            ListSerializer(NewsTopicDto.serializer()),
            http.get("$baseUrl/api/animes/$id/topics") { parameter("limit", limit) }.bodyAsText(),
        )

    /** The site's news feed: announcements, trailers, premiere dates and other news, newest first. */
    suspend fun newsFeed(page: Int, limit: Int): List<NewsTopicDto> =
        AppJson.decodeFromString(
            ListSerializer(NewsTopicDto.serializer()),
            http.get("$baseUrl/api/topics") {
                parameter("forum", "news")
                parameter("page", page)
                parameter("limit", limit)
            }.bodyAsText(),
        )

    /** Site news linked to one anime, newest first. */
    suspend fun newsTopics(id: Int, limit: Int): List<NewsTopicDto> =
        AppJson.decodeFromString(
            ListSerializer(NewsTopicDto.serializer()),
            http.get("$baseUrl/api/topics") {
                parameter("forum", "news")
                parameter("linked_type", "Anime")
                parameter("linked_id", id)
                parameter("limit", limit)
            }.bodyAsText(),
        )

    private suspend fun post(query: String, variables: JsonObject?): String =
        http.post("$baseUrl/api/graphql") {
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(GraphQlRequest.serializer(), GraphQlRequest(query, variables)))
        }.bodyAsText()

    companion object {
        const val BASE_URL = "https://shikimori.io"

        private const val CARD_FIELDS =
            "id name russian english kind score status episodes episodesAired " +
                "airedOn { year } poster { mainUrl main2xUrl originalUrl } genres { id russian kind }"

        private const val LIST_QUERY =
            "query(\$page: PositiveInt, \$limit: PositiveInt, \$order: OrderEnum, \$kind: AnimeKindString, " +
                "\$status: AnimeStatusString, \$season: SeasonString, \$genre: String, \$search: String) { " +
                "animes(page: \$page, limit: \$limit, order: \$order, kind: \$kind, status: \$status, " +
                "season: \$season, genre: \$genre, search: \$search, censored: true) { $CARD_FIELDS } }"

        private const val DETAIL_QUERY =
            "query(\$ids: String) { animes(ids: \$ids, limit: 1, censored: false) { $CARD_FIELDS " +
                "description descriptionHtml duration rating nextEpisodeAt studios { name } screenshots { originalUrl x332Url } " +
                "related { relationKind relationText anime { $CARD_FIELDS } } topic { id commentsCount } } }"

        private const val BY_IDS_QUERY =
            "query(\$ids: String) { animes(ids: \$ids, limit: 50, censored: false) { $CARD_FIELDS } }"

        private const val GENRES_QUERY = "{ genres(entryType: Anime) { id russian name kind } }"
    }
}

class ShikimoriException(message: String) : Exception(message)
