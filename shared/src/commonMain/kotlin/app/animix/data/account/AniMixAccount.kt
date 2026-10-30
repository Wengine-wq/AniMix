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
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.plugins.expectSuccess
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
    /**
     * Android's return link after Google sign-in. Not `animix://`: AniMix 2.x on the same phone
     * claims animix://oauth/callback too, and the browser could hand the sign-in to the old app.
     */
    const val CALLBACK = "animix-nh://oauth/callback"
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
    private val refreshLock = Mutex()
    private var session: StoredAccount? = null
    private var signingOut: StoredAccount? = null
    private var loaded = false
    private var sessionGeneration = 0L
    private var signInStartedAt = 0L
    private var returnUri = AniMixServer.CALLBACK

    suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        session = store.readChecked(FILE)?.let { AppJson.decodeFromString(StoredAccount.serializer(), it) }
        loaded = true
        mutable.value = mutable.value.copy(user = session?.user, path = session?.path)
    }

    /**
     * The Google sign-in page on the server, opened in the normal system browser. It ends with a
     * redirect to [returnTo]: the `animix://` link on Android, a loopback listener on desktop.
     */
    fun signInUrl(returnTo: String = AniMixServer.CALLBACK): String {
        signInStartedAt = TrustedClock.now()
        returnUri = returnTo
        mutable.value = mutable.value.copy(waitingForBrowser = true, error = null)
        return "$baseUrl/v1/auth/google/start?return_uri=${returnTo.encodeURLParameter()}"
    }

    fun cancelSignIn() { signInStartedAt = 0; mutable.value = mutable.value.copy(waitingForBrowser = false) }

    /** Takes the sign-in redirect (true) or leaves the link to navigation (false). */
    fun handlesCallback(link: String): Boolean {
        if (!sameReturnAddress(link, AniMixServer.CALLBACK)) return false
        completeSignIn(link)
        return true
    }

    /** Ignored unless a sign-in started on this device in the last 15 minutes. */
    fun completeSignIn(callback: String) {
        if (!sameReturnAddress(callback, returnUri)) return
        val started = signInStartedAt
        if (started == 0L || TrustedClock.now() - started > 15 * 60_000L) return
        signInStartedAt = 0
        val generation = sessionGeneration
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
                save(StoredAccount(access, refresh, user, path), generation)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                AppLog.failure("Account", "sign_in", error)
                mutable.value = mutable.value.copy(error = (error as? AccountException)?.message ?: "Не удалось войти. Проверьте сеть и повторите.")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun signOut() {
        val current = session ?: signingOut ?: return
        // Invalidate synchronously: an in-flight refresh must never resurrect this account.
        session = null
        signingOut = current
        sessionGeneration++
        val generation = sessionGeneration
        mutable.value = mutable.value.copy(busy = true, error = null)
        scope.launch {
            try {
                lock.withLock {
                    if (session == null && sessionGeneration == generation) {
                        store.deleteChecked(FILE)
                        signingOut = null
                        mutable.value = AccountState()
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                AppLog.failure("Account", "sign_out_storage", error)
                mutable.value = mutable.value.copy(busy = false, error = "Не удалось удалить сессию с устройства. Повторите выход.")
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
    internal suspend fun authorized(userId: String? = null, request: suspend (String) -> HttpResponse): HttpResponse {
        val current = session ?: throw AccountException("Войдите в аккаунт AniMix.")
        if (userId != null && current.user.id != userId) throw AccountException("Аккаунт изменился.")
        suspend fun send(token: String): HttpResponse {
            val response = request(token)
            if (response.status == HttpStatusCode.Unauthorized) throw ClientRequestException(response, "Unauthorized")
            if (response.status.value !in 200..299 && response.status != HttpStatusCode.Conflict) throw AccountException("Сервер не принял изменения (${response.status.value}).")
            return response
        }
        val response = try { send(current.accessToken) } catch (error: ClientRequestException) {
            if (error.response.status != HttpStatusCode.Unauthorized) throw error
            send(refresh(current).accessToken)
        }
        if (session?.user?.id != current.user.id) throw AccountException("Аккаунт изменился.")
        return response
    }

    private suspend fun refresh(expired: StoredAccount): StoredAccount = refreshLock.withLock {
        val current = session ?: throw AccountException("Войдите в аккаунт AniMix.")
        val generation = sessionGeneration
        if (current.user.id != expired.user.id) throw AccountException("Аккаунт изменился.")
        if (current.accessToken != expired.accessToken) return@withLock current
        val response = try {
            json(http.post("$baseUrl/v1/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("refresh_token", current.refreshToken) }.toString())
            }.bodyAsText())
        } catch (error: ClientRequestException) {
            if (error.response.status == HttpStatusCode.Unauthorized) {
                lock.withLock { if (session == current) { session = null; store.delete(FILE); mutable.value = AccountState(error = "Сессия AniMix закончилась. Войдите снова.") } }
            }
            throw error
        }
        val next = StoredAccount(response.text("access_token") ?: throw AccountException("Сессия AniMix закончилась. Войдите снова."),
            response.text("refresh_token") ?: current.refreshToken, user(response["user"] as? JsonObject) ?: current.user, current.path)
        lock.withLock {
            if (session != current) throw AccountException("Аккаунт изменился.")
            store.writeChecked(FILE, AppJson.encodeToString(StoredAccount.serializer(), next))
            if (sessionGeneration != generation || session != current) { store.delete(FILE); throw AccountException("Аккаунт изменился.") }
            session = next
        }
        next
    }

    private suspend fun save(value: StoredAccount, generation: Long = sessionGeneration) = lock.withLock {
        if (sessionGeneration != generation) throw AccountException("Аккаунт изменился.")
        store.writeChecked(FILE, AppJson.encodeToString(StoredAccount.serializer(), value))
        if (sessionGeneration != generation) { store.delete(FILE); throw AccountException("Аккаунт изменился.") }
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

    internal suspend fun getJson(path: String, userId: String): JsonObject = json(authorized(userId) { token ->
        http.get("$baseUrl$path") { bearerAuth(token); expectSuccess = false }
    }.bodyAsText())

    internal suspend fun putJson(path: String, body: String, userId: String): Pair<Int, JsonObject> {
        val response = authorized(userId) { token -> http.put("$baseUrl$path") {
            bearerAuth(token); expectSuccess = false; contentType(ContentType.Application.Json); setBody(body)
        } }
        return response.status.value to json(response.bodyAsText())
    }

    internal suspend fun command(path: String, userId: String, body: JsonObject = JsonObject(emptyMap()), delete: Boolean = false): JsonObject {
        val response = authorized(userId) { token ->
            if (delete) http.delete("$baseUrl$path") { bearerAuth(token); expectSuccess = false }
            else http.post("$baseUrl$path") { bearerAuth(token); expectSuccess = false; contentType(ContentType.Application.Json); setBody(body.toString()) }
        }
        if (response.status.value !in 200..299) throw AccountException("Не удалось выполнить действие. Обновите страницу.")
        return json(response.bodyAsText())
    }

    internal suspend fun uploadMedia(kind: String, bytes: ByteArray, userId: String): String {
        require(kind in setOf("avatar", "banner"))
        val response = authorized(userId) { token -> http.put("$baseUrl/v1/me/media/$kind") {
            bearerAuth(token); expectSuccess = false; contentType(ContentType.Image.PNG); setBody(bytes)
        } }
        return (json(response.bodyAsText())["user"] as? JsonObject)?.text("${kind}_url")
            ?: throw AccountException("Сервер не сохранил изображение профиля.")
    }

    private fun sameReturnAddress(link: String, expected: String): Boolean = runCatching {
        val actual = Url(link); val target = Url(expected)
        actual.protocol == target.protocol && actual.host == target.host && actual.port == target.port &&
            actual.encodedPath == target.encodedPath && actual.user == null && actual.password == null
    }.getOrDefault(false)

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
