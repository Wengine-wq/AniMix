package app.animix.data.account

import app.animix.core.AppJson
import app.animix.core.AppLog
import app.animix.core.DiskStore
import app.animix.core.TrustedClock
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** The AniMix account server. One address for sign-in, the online profile and lists. */
object AniMixServer {
    const val BASE_URL = "https://193-124-64-116.sslip.io"
    /** The only return address the server accepts after Google sign-in. */
    const val CALLBACK = "animix://oauth/callback"
}

@Serializable
data class AccountUser(val id: String, val name: String, val email: String? = null, val createdAt: Long? = null)

/** How this device's session began: "register" when the server created the profile, else "signin". */
@Serializable
private class StoredAccount(val accessToken: String, val refreshToken: String, val user: AccountUser, val path: String = "signin")

data class AccountState(
    val user: AccountUser? = null,
    /** "register" or "signin", for «Новый AniMix — а списки-то старые». */
    val path: String? = null,
    val busy: Boolean = false,
    val waitingForBrowser: Boolean = false,
    val error: String? = null,
)

class AccountException(message: String) : Exception(message)

/**
 * The online AniMix profile: Google sign-in on [AniMixServer], the session kept on this device
 * (refreshed when it expires), sign-out, and writing lists and awards into the account.
 */
class AniMixAccount(
    private val http: HttpClient,
    private val store: DiskStore,
    private val scope: CoroutineScope,
    private val baseUrl: String = AniMixServer.BASE_URL,
) {
    private val mutable = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = mutable.asStateFlow()
    private val lock = Mutex()
    private var session: StoredAccount? = null
    private var loaded = false
    private var signInStartedAt = 0L

    suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        session = store.read(FILE)?.let { runCatching { AppJson.decodeFromString(StoredAccount.serializer(), it) }.getOrNull() }
        loaded = true
        mutable.value = mutable.value.copy(user = session?.user, path = session?.path)
    }

    /** The Google sign-in page on the server; it ends with a redirect to [AniMixServer.CALLBACK]. */
    fun signInUrl(): String {
        signInStartedAt = TrustedClock.now()
        mutable.value = mutable.value.copy(waitingForBrowser = true, error = null)
        return "$baseUrl/v1/auth/google/start?return_uri=${AniMixServer.CALLBACK.encodeURLParameter()}"
    }

    fun cancelSignIn() { signInStartedAt = 0; mutable.value = mutable.value.copy(waitingForBrowser = false) }

    /** Takes the sign-in redirect (true) or leaves the link to navigation (false). */
    fun handlesCallback(link: String): Boolean {
        if (!link.startsWith(AniMixServer.CALLBACK)) return false
        completeSignIn(link)
        return true
    }

    /** Ignored unless a sign-in started on this device in the last 15 minutes. */
    fun completeSignIn(callback: String) {
        if (!callback.startsWith(AniMixServer.CALLBACK)) return
        val started = signInStartedAt
        if (started == 0L || TrustedClock.now() - started > 15 * 60_000L) return
        signInStartedAt = 0
        mutable.value = mutable.value.copy(busy = true, waitingForBrowser = false, error = null)
        scope.launch {
            try {
                val url = Url(callback)
                url.parameters["error"]?.let { throw AccountException(signInError(it)) }
                val ticket = url.parameters["ticket"]?.takeIf { it.isNotBlank() }
                val state = url.parameters["state"]?.takeIf { it.isNotBlank() }
                if (ticket == null || state == null) throw AccountException("Вход не завершён. Попробуйте ещё раз.")
                val response = json(http.post("$baseUrl/v1/auth/google/exchange") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("ticket", ticket); put("state", state) }.toString())
                }.bodyAsText())
                val access = response.text("access_token") ?: throw AccountException("Сервер AniMix не подтвердил вход.")
                val refresh = response.text("refresh_token") ?: throw AccountException("Сервер AniMix не подтвердил вход.")
                val user = user(response["user"] as? JsonObject) ?: throw AccountException("Сервер AniMix не вернул профиль.")
                // The server creates the profile on the first Google sign-in: that is a registration.
                val path = if (user.createdAt != null && TrustedClock.now() - user.createdAt in -60_000L..REGISTRATION_WINDOW) "register" else "signin"
                save(StoredAccount(access, refresh, user, path))
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                AppLog.failure("Account", "sign_in", error)
                mutable.value = mutable.value.copy(error = (error as? AccountException)?.message ?: "Не удалось войти. Проверьте сеть и повторите.")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun signOut() {
        val current = session ?: return
        scope.launch {
            lock.withLock {
                session = null
                store.delete(FILE)
                mutable.value = AccountState()
            }
            runCatching { http.post("$baseUrl/v1/auth/logout") { bearerAuth(current.accessToken) } }
        }
    }

    /** Writes one list entry into the account (Shikimori id, server status name, score 0 = none). */
    suspend fun putLibraryEntry(shikimoriId: Int, status: String, score: Int, episodesWatched: Int) {
        authorized { token -> http.put("$baseUrl/v1/library/$shikimoriId") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("status", status); put("score", score); put("episodes_watched", episodesWatched) }.toString())
        } }
    }

    /** Merges unlock dates into the account; the server keeps the earliest of each. */
    suspend fun mergeAchievements(unlocks: Map<String, Long>) {
        if (unlocks.isEmpty()) return
        unlocks.entries.chunked(200).forEach { chunk ->
            authorized { token -> http.put("$baseUrl/v1/me/achievements") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("achievements", buildJsonObject { chunk.forEach { (id, at) -> put(id, at) } }) }.toString())
            } }
        }
    }

    /** One request with the current token; an expired token is refreshed once. */
    private suspend fun authorized(request: suspend (String) -> HttpResponse): HttpResponse {
        val current = session ?: throw AccountException("Войдите в аккаунт AniMix.")
        return try { request(current.accessToken) }
        catch (error: ClientRequestException) {
            if (error.response.status != HttpStatusCode.Unauthorized) throw error
            request(refresh(current).accessToken)
        }
    }

    private suspend fun refresh(current: StoredAccount): StoredAccount {
        val response = try {
            json(http.post("$baseUrl/v1/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("refresh_token", current.refreshToken) }.toString())
            }.bodyAsText())
        } catch (error: ClientRequestException) {
            if (error.response.status == HttpStatusCode.Unauthorized) {
                lock.withLock { session = null; store.delete(FILE); mutable.value = AccountState(error = "Сессия AniMix закончилась. Войдите снова.") }
            }
            throw error
        }
        val next = StoredAccount(response.text("access_token") ?: throw AccountException("Сессия AniMix закончилась. Войдите снова."),
            response.text("refresh_token") ?: current.refreshToken, user(response["user"] as? JsonObject) ?: current.user, current.path)
        save(next)
        return next
    }

    private suspend fun save(value: StoredAccount) = lock.withLock {
        store.writeChecked(FILE, AppJson.encodeToString(StoredAccount.serializer(), value))
        session = value
        mutable.value = mutable.value.copy(user = value.user, path = value.path, error = null)
    }

    private fun user(obj: JsonObject?): AccountUser? {
        obj ?: return null
        val id = obj.text("id") ?: return null
        val created = obj.text("created_at")?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
        return AccountUser(id, obj.text("display_name")?.takeIf { it.isNotBlank() } ?: "AniMix", obj.text("email"), created)
    }

    private fun json(text: String): JsonObject = runCatching { AppJson.parseToJsonElement(text).jsonObject }
        .getOrElse { throw AccountException("Сервер AniMix вернул неполный ответ. Попробуйте позже.") }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun signInError(code: String): String = when {
        code.contains("email_not_verified") -> "Google не подтвердил почту этого аккаунта."
        code.contains("access_denied") -> "Вход отменён."
        else -> "Google не завершил вход. Попробуйте ещё раз."
    }

    private companion object {
        const val FILE = "animix-account.json"
        const val REGISTRATION_WINDOW = 15 * 60_000L
    }
}
