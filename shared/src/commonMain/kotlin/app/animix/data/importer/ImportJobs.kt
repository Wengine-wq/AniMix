package app.animix.data.importer

import app.animix.core.AppLog
import app.animix.data.library.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where an import is; kept outside the screen so leaving settings does not stop it. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val label: String, val done: Int = 0, val total: Int = 0) : ImportState
    data class Finished(val added: Int, val updated: Int, val total: Int, val missing: List<AnixartRelease> = emptyList()) : ImportState
    data class Failed(val message: String) : ImportState
}

class ImportJobs(
    private val scope: CoroutineScope,
    private val library: Library,
    private val shikimori: ShikimoriAccount,
    private val anixart: AnixartClient,
    private val matcher: AnixartMatcher,
) {
    private val shikimoriState = MutableStateFlow<ImportState>(ImportState.Idle)
    private val anixartState = MutableStateFlow<ImportState>(ImportState.Idle)
    val shikimoriImport: StateFlow<ImportState> = shikimoriState.asStateFlow()
    val anixartImport: StateFlow<ImportState> = anixartState.asStateFlow()
    private var shikimoriJob: Job? = null
    private var anixartJob: Job? = null

    fun importShikimori() {
        if (shikimoriJob?.isActive == true) return
        shikimoriJob = scope.launch {
            shikimoriState.value = ImportState.Running("Читаем списки Shikimori")
            shikimoriState.value = try {
                val titles = shikimori.importTitles()
                val result = library.importTitles(titles)
                ImportState.Finished(result.added, result.updated, titles.size)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                AppLog.failure("Import", "list_import", error)
                ImportState.Failed(error.message?.takeIf { it.length < 160 && "http" !in it } ?: "Не удалось импортировать. Проверьте соединение.")
            }
        }
    }

    fun importAnixart(profile: AnixartProfile, lists: Set<AnixartList>) {
        if (anixartJob?.isActive == true) return
        anixartJob = scope.launch {
            anixartState.value = ImportState.Running("Читаем списки ${profile.login}")
            anixartState.value = try {
                val releases = lists.flatMap { anixart.releases(profile.id, it) }.distinctBy { it.id }
                val matches = matcher.match(releases) { done, total -> anixartState.value = ImportState.Running("Ищем тайтлы", done, total) }
                val result = library.importTitles(matcher.toImported(matches))
                ImportState.Finished(result.added, result.updated, releases.size, matches.filter { it.anime == null }.map { it.release })
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: AnixartUnavailable) {
                AppLog.failure("Import", "anixart_unavailable", error)
                ImportState.Failed(error.message!!)
            } catch (error: Exception) {
                AppLog.failure("Import", "anixart_import", error)
                ImportState.Failed("Не удалось импортировать. Проверьте соединение и попробуйте ещё раз.")
            }
        }
    }
}
