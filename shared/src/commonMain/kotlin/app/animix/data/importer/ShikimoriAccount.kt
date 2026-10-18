package app.animix.data.importer

import app.animix.core.AppJson
import app.animix.core.DiskStore
import app.animix.data.library.ImportedTitle
import app.animix.data.library.ListStatus
import app.animix.data.shikimori.RestAnimeDto
import app.animix.data.shikimori.toAnime
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/** The signed-in Shikimori account, as far as the import needs it. */
@Serializable
data class ShikimoriUser(val id: Int, val nickname: String, val avatar: String? = null)

@Serializable
private class StoredSession(val accessToken: String, val refreshToken: String, val user: ShikimoriUser? = null)

@Serializable
private class TokenResponse(val access_token: String? = null, val refresh_token: String? = null)

@Serializable
private class WhoAmI(val id: Int = 0, val nickname: String = "", val avatar: String? = null, val image: Map<String, String>? = null)

@Serializable
private class UserRate(val status: String = "", val episodes: Int = 0, val score: Int = 0, val anime: RestAnimeDto? = null)

class ShikimoriAuthException(message: String) : Exception(message)

/**
 * Shikimori sign-in for importing lists. The OAuth client secret lives on the AniMix server,
 * which only exchanges codes and refresh tokens; the tokens themselves stay on this device.
 */
class ShikimoriAccount(private val http: HttpClient, private val store: DiskStore) {
    private val mutable = MutableStateFlow<ShikimoriUser?>(null)
    val user: StateFlow<ShikimoriUser?> = mutable.asStateFlow()
    private val lock = Mutex()
    private var session: StoredSession? = null
    private var loaded = false

    /** The redirect registered for the AniMix Shikimori application. */
    val redirectUri = REDIRECT_URI

    fun newState(): String = Random.nextBytes(24).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun authorizeUrl(state: String): String = URLBuilder("$BASE/oauth/authorize").apply {
        parameters.append("client_id", CLIENT_ID)
        parameters.append("redirect_uri", REDIRECT_URI)
        parameters.append("response_type", "code")
        parameters.append("scope", "user_rates")
        parameters.append("state", state)
    }.buildString()

    suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        loaded = true
        session = store.read(FILE)?.let { runCatching { AppJson.decodeFromString(StoredSession.serializer(), it) }.getOrNull() }
        mutable.value = session?.user
    }

    /** Finishes the browser sign-in: [callbackUrl] is where Shikimori sent the browser. */
    suspend fun complete(callbackUrl: String, expectedState: String): ShikimoriUser {
        val url = io.ktor.http.Url(callbackUrl)
        if (url.parameters["state"] != expectedState) throw ShikimoriAuthException("Shikimori вернул неверный ответ. Попробуйте войти ещё раз.")
        url.parameters["error"]?.let { throw ShikimoriAuthException("Вход отменён в Shikimori.") }
        val code = url.parameters["code"]?.takeIf { it.isNotBlank() } ?: throw ShikimoriAuthException("Shikimori не вернул код входа.")
        val tokens = exchange(mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT_URI))
        val me = whoAmI(tokens.access_token!!)
        val stored = StoredSession(tokens.access_token, tokens.refresh_token!!, me)
        lock.withLock { session = stored; save(stored) }
        mutable.value = me
        return me
    }

    suspend fun signOut() = lock.withLock {
        session = null
        store.delete(FILE)
        mutable.value = null
    }

    /** Every anime list entry of the signed-in user, ready for the library. */
    suspend fun importTitles(): List<ImportedTitle> {
        val me = user.value ?: throw ShikimoriAuthException("Сначала войдите в Shikimori.")
        val rates = mutableListOf<UserRate>()
        var page = 1
        while (page <= 20) {
            val batch = authorized { token ->
                http.get("$BASE/api/users/${me.id}/anime_rates") {
                    expectSuccess = false
                    bearerAuth(token)
                    parameter("limit", PAGE)
                    parameter("page", page)
                }
            }.let { AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(UserRate.serializer()), it) }
            rates += batch
            if (batch.size < PAGE) break
            page++
        }
        return rates.mapNotNull { rate ->
            val anime = rate.anime ?: return@mapNotNull null
            ImportedTitle(
                anime = anime.toAnime(),
                status = when (rate.status) {
                    "watching", "rewatching" -> ListStatus.Watching
                    "planned" -> ListStatus.Planned
                    "completed" -> ListStatus.Completed
                    "on_hold" -> ListStatus.OnHold
                    "dropped" -> ListStatus.Dropped
                    else -> null
                },
                episodesWatched = if (rate.status == "completed") maxOf(rate.episodes, anime.episodes) else rate.episodes,
                score = rate.score.takeIf { it in 1..10 },
            )
        }
    }

    /** Runs [request] with the access token, refreshing it once when Shikimori says it expired. */
    private suspend fun authorized(request: suspend (String) -> HttpResponse): String {
        val current = lock.withLock { session } ?: throw ShikimoriAuthException("Сначала войдите в Shikimori.")
        var response = request(current.accessToken)
        if (response.status == HttpStatusCode.Unauthorized) {
            val fresh = refresh(current)
            response = request(fresh.accessToken)
        }
        if (response.status == HttpStatusCode.Unauthorized) throw ShikimoriAuthException("Сессия Shikimori истекла. Войдите снова.")
        if (!response.status.isSuccess()) throw ShikimoriAuthException("Shikimori не отдал список (${response.status.value}). Попробуйте позже.")
        return response.bodyAsText()
    }

    private suspend fun refresh(current: StoredSession): StoredSession {
        val tokens = exchange(mapOf("grant_type" to "refresh_token", "refresh_token" to current.refreshToken))
        val next = StoredSession(tokens.access_token!!, tokens.refresh_token ?: current.refreshToken, current.user)
        lock.withLock { session = next; save(next) }
        return next
    }

    private suspend fun exchange(grant: Map<String, String>): TokenResponse {
        val body = JsonObject((grant + ("client_id" to CLIENT_ID)).mapValues { JsonPrimitive(it.value) })
        val response = http.post(TOKEN_PROXY) {
            expectSuccess = false
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val tokens = runCatching { AppJson.decodeFromString(TokenResponse.serializer(), response.bodyAsText()) }.getOrNull()
        if (!response.status.isSuccess() || tokens?.access_token.isNullOrBlank() || tokens?.refresh_token.isNullOrBlank() && grant["grant_type"] == "authorization_code") {
            throw ShikimoriAuthException("Сервер входа не подтвердил Shikimori. Попробуйте ещё раз.")
        }
        return tokens!!
    }

    private suspend fun whoAmI(token: String): ShikimoriUser {
        val response = http.get("$BASE/api/users/whoami") { expectSuccess = false; bearerAuth(token) }
        if (!response.status.isSuccess()) throw ShikimoriAuthException("Не удалось узнать аккаунт Shikimori.")
        val me = AppJson.decodeFromString(WhoAmI.serializer(), response.bodyAsText())
        return ShikimoriUser(me.id, me.nickname, me.image?.get("x160") ?: me.avatar)
    }

    private suspend fun save(value: StoredSession) = store.write(FILE, AppJson.encodeToString(StoredSession.serializer(), value))

    private companion object {
        const val BASE = "https://shikimori.io"
        const val CLIENT_ID = "NuL115GhQetODbWxWIKOKEt9E2IMtUqGkY9zdQacpBM"
        const val REDIRECT_URI = "https://animix.app/callback"
        // The AniMix server holds the client secret and exchanges codes for tokens.
        const val TOKEN_PROXY = "https://d5davburno8pol18q3hm.fovt0b64.apigw.yandexcloud.net"
        const val FILE = "shikimori.json"
        const val PAGE = 5000
    }
}
