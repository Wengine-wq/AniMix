package app.animix.data.pulse

import app.animix.core.AppJson
import app.animix.core.DiskStore
import app.animix.core.nowMillis
import app.animix.data.library.LibraryData
import app.animix.model.Anime
import app.animix.model.AnimeDetail
import app.animix.model.AnimeSource
import app.animix.model.AnimeStatus
import app.animix.model.AnimeTopic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Weekly issues: Shikimori events and news first (free, dated), then the AI editor on top when this device
 * has access. Without it, or when it fails, the issue is still built from Shikimori and can be rebuilt later.
 */
class PulseRepository(private val disk: DiskStore, private val scope: CoroutineScope,
    private val detail: suspend (Anime) -> AnimeDetail, private val editor: PulseEditor,
    private val topics: suspend (Anime) -> List<AnimeTopic> = { emptyList() },
    private val available: () -> Boolean = { true }, private val clock: () -> Long = ::nowMillis) {
    private val mutable = MutableStateFlow(PulseState())
    val state = mutable.asStateFlow()
    private val loadLock = Mutex()
    private val generation = Mutex()

    suspend fun load() = loadLock.withLock {
        if (mutable.value.loaded) return@withLock
        val text = try { disk.readChecked("anipulse.json") }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            mutable.update { it.copy(error = "Не удалось открыть архив AniPulse. Сохранённые данные не изменены.") }
            return@withLock
        }
        val archive = text?.let { runCatching { AppJson.decodeFromString(PulseArchive.serializer(), it) }.getOrNull() }
        if (text != null && archive == null) {
            mutable.update { it.copy(error = "Не удалось прочитать архив AniPulse. Сохранённый файл не изменён.") }
            return@withLock
        }
        mutable.update { it.copy(loaded = true, issues = archive?.issues.orEmpty().sortedByDescending { it.createdAt }) }
    }

    /** The finished issue of this week, if any. One built without the AI editor does not count. */
    fun current(issues: List<PulseIssue> = state.value.issues): PulseIssue? =
        pulseWindow(clock()).id.let { week -> issues.firstOrNull { it.window.id == week && it.aiEdited } }

    fun generate(library: LibraryData) {
        if(!available()){mutable.update{it.copy(error="AniPulse временно недоступен")};return}
        if (!generation.tryLock()) return
        // ATOMIC: the lock is released even if the scope is cancelled before the job starts.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                load()
                if (!state.value.loaded || current() != null) return@launch
                val window = pulseWindow(clock())
                val interests = pulseInterests(library)
                if (interests.isEmpty()) throw PulseException("Добавьте тайтлы в списки — так выпуск станет вашим.")
                mutable.update { it.copy(busy = true, stage = "Проверяем тайтлы из ваших списков", error = null) }
                val previous = state.value.issues.firstOrNull { it.window.id != window.id }
                    ?.takeIf { clock() - it.createdAt <= 14 * 86_400_000L }
                val details = interests.map { entry ->
                    entry to try { detail(entry.anime) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                }
                val snapshots = details.map { (entry, loaded) ->
                    PulseTitle(loaded?.anime ?: entry.anime, entry.status?.label ?: "Избранное", entry.episodesWatched,
                        fresh = loaded != null, nextEpisodeAt = loaded?.nextEpisodeAt,
                        previousAired = previous?.titles?.firstOrNull { it.anime.key == entry.anime.key && it.fresh }?.anime?.episodesAired,
                        studios = loaded?.studios.orEmpty())
                }
                mutable.update { it.copy(stage = "Смотрим новости Shikimori") }
                val found = collectTopics(details.mapNotNull { (_, loaded) -> loaded })
                val digest = pulseDigest(snapshots, found, window)

                var note: String? = null
                val edited = try { editor.edit(digest.titles, window, digest.news) { stage -> mutable.update { it.copy(stage = stage) } } }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    note = (error as? PulseException)?.message ?: "ИИ-обзор не удалось подготовить"
                    null
                }
                val written = edited?.let { validatePulse(it.editorial, it.sources, digest.titles, window) }
                // A paid run counts for the week even when nothing it found passed the checks; the reader is told so.
                if (written != null && written.articles.isEmpty()) note = "ИИ-обзор не нашёл подтверждённых новостей за неделю"
                // Written news the editor did not retell stay as plain Shikimori items.
                val retold = written?.articles.orEmpty().flatMap { it.sourceIds }.toSet()
                val plainNews = digest.newsArticles.filter { article -> article.sourceIds.none { it in retold } }
                val articles = digest.events + written?.articles.orEmpty() + plainNews
                val airing = digest.titles.any { it.weekEpisodes.isNotEmpty() || it.anime.status == AnimeStatus.Ongoing }
                if (articles.isEmpty() && !airing) throw PulseException(note?.let { "За неделю на Shikimori не нашлось новостей по вашим тайтлам. $it." }
                    ?: "Не нашли подтверждённых материалов для выпуска. Попробуйте позже.")
                val editorial = written?.takeIf { it.articles.isNotEmpty() && it.headline.isNotBlank() }?.copy(articles = articles)
                    ?: pulsePlainEditorial(digest.titles, articles)
                val used = articles.flatMap { it.sourceIds }.toSet()
                val issue = PulseIssue(window, clock(), digest.titles, editorial,
                    (digest.sources + edited?.sources.orEmpty()).distinctBy { it.id }.filter { it.id in used },
                    edited?.costRub, aiEdited = edited != null, note = note)
                val issues = (listOf(issue) + state.value.issues.filter { it.window.id != window.id }).take(12)
                // A failed write must leave the previous issue and archive intact.
                disk.writeChecked("anipulse.json", AppJson.encodeToString(PulseArchive.serializer(), PulseArchive(issues)))
                mutable.update { it.copy(issues = issues) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutable.update { it.copy(error = (error as? PulseException)?.message ?: "Выпуск не удалось собрать. Сохранённые выпуски доступны; можно повторить попытку.") }
            } finally { mutable.update { it.copy(busy = false, stage = "") }; generation.unlock() }
        }
    }

    /** Own topics of each title plus those of up to two related titles that are announced or airing. Sequential: Shikimori rate limit. */
    private suspend fun collectTopics(details: List<AnimeDetail>): List<PulseTopic> {
        val result = mutableListOf<PulseTopic>()
        for (detail in details.filter { it.anime.source == AnimeSource.Shikimori }) {
            val related = detail.related.filter { it.source == AnimeSource.Shikimori && it.id != detail.anime.id &&
                (it.status == AnimeStatus.Announced || it.status == AnimeStatus.Ongoing) }.take(2)
            for (anime in listOf(detail.anime) + related) {
                try { topics(anime).forEach { result += PulseTopic(detail.anime.key, anime, it) } }
                catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                delay(250)
            }
        }
        return result
    }
}
