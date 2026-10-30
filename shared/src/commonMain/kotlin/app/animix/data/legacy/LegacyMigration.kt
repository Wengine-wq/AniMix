package app.animix.data.legacy

import app.animix.core.AppLog
import app.animix.core.TrustedClock
import app.animix.data.account.AniMixAccount
import app.animix.data.achievements.Achievements
import app.animix.data.library.ImportedTitle
import app.animix.data.library.LegacyMigrationRecord
import app.animix.data.library.Library
import app.animix.model.Anime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** 2.x data on this device, if the platform can read it (Windows: the old app's preferences file). */
expect fun legacyPreferencesText(): String?

/** What Settings → «Аккаунт» shows about the migration. */
data class LegacyState(
    val busy: Boolean = false,
    val stage: String = "",
    val error: String? = null,
    val result: LegacyMigrationRecord? = null,
)

class LegacyMigrationException(message: String) : Exception(message)

/**
 * Brings the local profile of AniMix 2.x (lists, scores, watched episodes, playback hours, awards and
 * the name) into this app and, when signed in, into the online AniMix account. Lists become imported
 * history (no XP); awards that exist here become ordinary awards with XP. Migrating into an account
 * earns «Новый AniMix — а списки-то старые», in the variant of how that account was entered.
 */
class LegacyMigration(
    private val scope: CoroutineScope,
    private val library: Library,
    private val achievements: Achievements,
    private val account: AniMixAccount?,
    private val titles: suspend (List<Int>) -> List<Anime>,
    private val localText: () -> String? = ::legacyPreferencesText,
) {
    private val mutable = MutableStateFlow(LegacyState())
    val state: StateFlow<LegacyState> = mutable.asStateFlow()
    private val running = Mutex()

    /** Whether this device has 2.x data to bring over. */
    fun hasLocalData(): Boolean = runCatching { localText()?.let { !LegacyFormat.parsePreferences(it).isEmpty } }.getOrNull() == true

    fun migrateLocal() {
        if (!running.tryLock()) return
        mutable.value = LegacyState(busy = true, stage = "Читаем данные AniMix 2.x", result = mutable.value.result)
        scope.launch {
            try {
                val text = localText() ?: throw LegacyMigrationException("Данные AniMix 2.x на этом устройстве не найдены.")
                val snapshot = LegacyFormat.parsePreferences(text)
                if (snapshot.isEmpty) throw LegacyMigrationException("В данных AniMix 2.x нет списков, часов или достижений.")
                apply(snapshot)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                AppLog.failure("Legacy", "migration", error)
                mutable.value = mutable.value.copy(error = (error as? LegacyMigrationException)?.message
                    ?: "Не удалось перенести данные AniMix 2.x. Проверьте сеть и повторите.")
            } finally {
                mutable.value = mutable.value.copy(busy = false, stage = "")
                running.unlock()
            }
        }
    }

    internal suspend fun apply(snapshot: LegacySnapshot) {
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

        // Into the online profile, when signed in: lists with a status, then the awards.
        val signedIn = account?.state?.value?.user
        if (account != null && signedIn != null) {
            val listed = imported.filter { it.status != null }
            listed.forEachIndexed { index, title ->
                stage("Переносим в аккаунт: ${index + 1} из ${listed.size}")
                account.putLibraryEntry(title.anime.id, title.status!!.apiValue, title.score ?: 0, title.episodesWatched)
            }
            account.mergeAchievements(awards)
        }
        val variant = if (signedIn != null) account?.state?.value?.path ?: "signin" else null
        val record = LegacyMigrationRecord("device", TrustedClock.now(), imported.size, awards.size, snapshot.watchSeconds, signedIn?.name)
        library.applyLegacy(snapshot.watchSeconds, record, snapshot.profileName ?: signedIn?.name)
        achievements.migrateLegacy(awards, variant)
        achievements.flush()
        library.flush()
        mutable.value = mutable.value.copy(result = library.state.value.legacyMigration ?: record, error = null)
    }

    private fun stage(text: String) { mutable.value = mutable.value.copy(stage = text) }
}
