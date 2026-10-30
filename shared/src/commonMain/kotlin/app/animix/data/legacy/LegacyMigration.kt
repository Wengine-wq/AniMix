package app.animix.data.legacy

import app.animix.core.AppJson
import app.animix.core.AppLog
import app.animix.core.TrustedClock
import app.animix.core.utcOffsetMillis
import app.animix.data.achievements.Achievements
import app.animix.data.library.ImportedTitle
import app.animix.data.library.LegacyMigrationRecord
import app.animix.data.library.Library
import app.animix.model.Anime
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** 2.x data on this device, if the platform can read it (Windows: the old app's preferences file). */
expect fun legacyPreferencesText(): String?

/** What Settings → «Аккаунт» shows about the migration. */
data class LegacyState(
    val busy: Boolean = false,
    val stage: String = "",
    val waitingForBrowser: Boolean = false,
    val error: String? = null,
    val result: LegacyMigrationRecord? = null,
)

class LegacyMigrationException(message: String) : Exception(message)

/**
 * Brings lists, watched episodes, playback hours, awards and the profile name over from AniMix 2.x,
 * either through its account (Google sign-in on the 2.x server) or from its files on this computer.
 * Lists become imported history (no XP); awards that exist in 3.0 become ordinary awards with XP.
 * The 2.x session is used once and closed; no tokens are stored.
 */
class LegacyMigration(
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val library: Library,
    private val achievements: Achievements,
    private val titles: suspend (List<Int>) -> List<Anime>,
    private val baseUrl: String = SERVER,
    private val localText: () -> String? = ::legacyPreferencesText,
) {
    private val mutable = MutableStateFlow(LegacyState())
    val state: StateFlow<LegacyState> = mutable.asStateFlow()
    private val running = Mutex()
    private var signInStartedAt = 0L

    /** The 2.x sign-in page; it ends with a redirect to [CALLBACK]. */
    fun signInUrl(): String {
        signInStartedAt = TrustedClock.now()
        mutable.value = mutable.value.copy(waitingForBrowser = true, error = null)
        return "$baseUrl/v1/auth/google/start?return_uri=${CALLBACK.encodeURLParameter()}"
    }

    fun cancelSignIn() { signInStartedAt = 0; mutable.value = mutable.value.copy(waitingForBrowser = false) }

    /** Whether this device has 2.x data to bring over without an account. */
    fun hasLocalData(): Boolean = runCatching { localText()?.let { !LegacyFormat.parsePreferences(it).isEmpty } }.getOrNull() == true

    /** Takes the 2.x sign-in redirect (true) or leaves the link to navigation (false). */
    fun handlesCallback(link: String): Boolean {
        if (!link.startsWith(CALLBACK)) return false
        completeSignIn(link)
        return true
    }

    /** The redirect from the 2.x server. Ignored unless a sign-in started here in the last 15 minutes. */
    fun completeSignIn(callback: String) {
        if (!callback.startsWith(CALLBACK)) return
        val started = signInStartedAt
        if (started == 0L || TrustedClock.now() - started > 15 * 60_000L) return
        signInStartedAt = 0
        run("account") {
            val url = Url(callback)
            url.parameters["error"]?.let { throw LegacyMigrationException(signInError(it)) }
            val ticket = url.parameters["ticket"]?.takeIf { it.isNotBlank() }
            val state = url.parameters["state"]?.takeIf { it.isNotBlank() }
            if (ticket == null || state == null) throw LegacyMigrationException("Вход не завершён. Попробуйте ещё раз.")
            stage("Входим в аккаунт AniMix")
            val session = json(http.post("$baseUrl/v1/auth/google/exchange") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("ticket", ticket); put("state", state) }.toString())
            }.bodyAsText())
            val token = (session["access_token"] as? JsonPrimitive)?.contentOrNull ?: throw LegacyMigrationException("Сервер AniMix не подтвердил вход.")
            val user = session["user"] as? JsonObject
            try {
                stage("Читаем списки и достижения аккаунта")
                val entries = LegacyFormat.entries(json(http.get("$baseUrl/v1/library") { bearerAuth(token) }.bodyAsText())["entries"])
                val usage = runCatching { json(http.get("$baseUrl/v1/usage") { bearerAuth(token) }.bodyAsText())["stats"] as? JsonObject }.getOrNull()
                val awards = runCatching { LegacyFormat.achievements(json(http.get("$baseUrl/v1/me/achievements") { bearerAuth(token) }.bodyAsText())["achievements"]) }
                    .getOrDefault(emptyMap())
                val created = (user?.get("created_at") as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                // The server creates the profile on the first Google sign-in: that is a registration.
                val variant = if (created != null && TrustedClock.now() - created in -60_000L..REGISTRATION_WINDOW) "register" else "signin"
                val name = (user?.get("display_name") as? JsonPrimitive)?.contentOrNull
                apply(LegacySnapshot(entries, awards, LegacyFormat.usageSeconds(usage, utcOffsetMillis(TrustedClock.now())), name), "account", variant, name)
            } finally {
                runCatching { http.post("$baseUrl/v1/auth/logout") { bearerAuth(token) } }
            }
        }
    }

    /** 2.x data found on this computer, without an account. */
    fun migrateLocal() = run("device") {
        stage("Читаем данные AniMix 2.x на этом компьютере")
        val text = localText() ?: throw LegacyMigrationException("Данные AniMix 2.x на этом устройстве не найдены.")
        val snapshot = LegacyFormat.parsePreferences(text)
        if (snapshot.isEmpty) throw LegacyMigrationException("В данных AniMix 2.x нет списков, часов или достижений.")
        apply(snapshot, "device", null, null)
    }

    private fun run(source: String, block: suspend () -> Unit) {
        if (!running.tryLock()) return
        mutable.value = LegacyState(busy = true, stage = "Готовим перенос", result = mutable.value.result)
        scope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                AppLog.failure("Legacy", "migration_$source", error)
                mutable.value = mutable.value.copy(error = (error as? LegacyMigrationException)?.message
                    ?: "Не удалось перенести данные AniMix 2.x. Проверьте сеть и повторите.")
            } finally {
                mutable.value = mutable.value.copy(busy = false, stage = "", waitingForBrowser = false)
                running.unlock()
            }
        }
    }

    internal suspend fun apply(snapshot: LegacySnapshot, source: String, variant: String?, accountName: String?) {
        val ids = (snapshot.entries.map { it.shikimoriId } + snapshot.watchedEpisodes.keys).distinct()
        stage("Загружаем ${ids.size} тайтлов из Shikimori")
        val cards = ids.chunked(50).flatMapIndexed { index, chunk ->
            if (index > 0) delay(400) // Shikimori rate limit
            titles(chunk)
        }.associateBy { it.id }
        val byId = snapshot.entries.associateBy { it.shikimoriId }
        val imported = ids.mapNotNull { id ->
            val anime = cards[id] ?: return@mapNotNull null
            val entry = byId[id]
            val watched = maxOf(entry?.episodesWatched ?: 0, snapshot.watchedEpisodes[id] ?: 0)
                .let { if (anime.episodes > 0) it.coerceAtMost(anime.episodes) else it }
            ImportedTitle(anime, entry?.status, watched, entry?.score)
        }
        stage("Сохраняем списки и достижения")
        library.importTitles(imported)
        val awards = LegacyFormat.mapAchievements(snapshot.achievements)
        val record = LegacyMigrationRecord(source, TrustedClock.now(), imported.size, awards.size, snapshot.watchSeconds, accountName)
        library.applyLegacy(snapshot.watchSeconds, record, snapshot.profileName ?: accountName)
        achievements.migrateLegacy(awards, variant)
        achievements.flush()
        library.flush()
        mutable.value = mutable.value.copy(result = library.state.value.legacyMigration ?: record, error = null)
    }

    private fun stage(text: String) { mutable.value = mutable.value.copy(stage = text) }

    private fun json(text: String): JsonObject = runCatching { AppJson.parseToJsonElement(text).jsonObject }
        .getOrElse { throw LegacyMigrationException("Сервер AniMix вернул неполный ответ. Попробуйте позже.") }

    private fun signInError(code: String): String = when {
        code.contains("email_not_verified") -> "Google не подтвердил почту этого аккаунта."
        code.contains("access_denied") -> "Вход отменён."
        else -> "Google не завершил вход. Попробуйте ещё раз."
    }

    companion object {
        /** The AniMix 2.x server; its Google sign-in only returns to [CALLBACK]. */
        const val SERVER = "https://d5davburno8pol18q3hm.fovt0b64.apigw.yandexcloud.net"
        const val CALLBACK = "animix://oauth/callback"
        private const val REGISTRATION_WINDOW = 15 * 60_000L
    }
}
