package app.animix.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import app.animix.core.AppLog

/** Off-screen preview hook; production always uses zero and never delays requests. */
val LocalLoadPreviewDelay = staticCompositionLocalOf { 0L }

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

@Stable
class LoadState<T> internal constructor() {
    var value: Load<T> by mutableStateOf(Load.Loading)
        internal set
    internal var generation by mutableIntStateOf(0)
    internal var force = false
    /** True from [refresh] until that load ends: the data on screen stays while it is fetched again. */
    var refreshing by mutableStateOf(false)
        internal set

    /** Loads again, skipping caches. */
    fun refresh() {
        force = true
        refreshing = true
        generation++
    }
}

/** Runs [block] when [key] changes and exposes Loading / Ready / Failed. */
@Composable
fun <T> rememberLoad(key: Any?, block: suspend (forceRefresh: Boolean) -> T): LoadState<T> {
    val previewDelay = LocalLoadPreviewDelay.current
    val state = remember(key) { LoadState<T>() }
    LaunchedEffect(state, state.generation) {
        val force = state.force
        state.force = false
        if (state.value !is Load.Ready || force) {
            if (state.value is Load.Failed) state.value = Load.Loading
            state.value = try {
                if (previewDelay > 0) delay(previewDelay)
                Load.Ready(block(force))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppLog.failure("Loading", "request_failed", error)
                if (state.value is Load.Ready && force) state.value else Load.Failed(error)
            }
        }
        state.refreshing = false
    }
    return state
}

/** A short, human message for an error shown in the UI. */
fun Throwable.userMessage(): String {
    val name = this::class.simpleName.orEmpty()
    return when {
        name.contains("UnknownHost") || name.contains("Connect") || name.contains("UnresolvedAddress") ->
            "Нет соединения с интернетом"
        name.contains("Timeout") -> "Сервер долго не отвечает"
        // A raw «Client request(POST https://…) invalid: 429» says nothing to a person.
        this is io.ktor.client.plugins.ResponseException -> when (response.status.value) {
            429 -> "Каталог просит немного подождать. Повторите через минуту"
            in 500..599 -> "Сервер каталога сейчас недоступен"
            404 -> "Не найдено"
            else -> "Сервер ответил ошибкой ${response.status.value}"
        }
        else -> message?.takeIf { it.isNotBlank() && it.length < 120 && "http" !in it } ?: "Что-то пошло не так"
    }
}
