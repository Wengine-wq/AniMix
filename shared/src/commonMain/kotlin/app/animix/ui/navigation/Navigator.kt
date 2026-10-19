package app.animix.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import app.animix.data.shikimori.CatalogQuery
import app.animix.model.Anime
import app.animix.model.AnimeSource
import io.ktor.http.decodeURLQueryComponent
import app.animix.ui.icons.AniMixIcons

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Главная", AniMixIcons.Home),
    Catalog("Каталог", AniMixIcons.Catalog),
    Lists("Списки", AniMixIcons.Bookmark),
    Downloads("Загрузки", AniMixIcons.Download),
    Profile("Профиль", AniMixIcons.User),
}

/** A screen pushed on top of a tab. */
sealed interface Route {
    val key: String

    data class Detail(val anime: Anime) : Route {
        override val key get() = "detail:${anime.key}"
    }

    data class Catalog(val preset: CatalogQuery, val title: String) : Route {
        override val key get() = "catalog:${preset.cacheKey}"
    }

    data class Search(val query: String = "") : Route {
        override val key get() = "search"
    }

    /** [episode] opens that episode right away (links and history); otherwise the screen offers where the viewer stopped. */
    data class Watch(val anime: Anime, val episode: String? = null) : Route {
        override val key get() = "watch:${anime.key}"
    }

    data class Offline(val downloadId: String) : Route {
        override val key get() = "offline:$downloadId"
    }

    data class Downloads(val anime: Anime) : Route {
        override val key get() = "downloads:${anime.key}"
    }

    data class Reviews(val anime: Anime) : Route {
        override val key get() = "reviews:${anime.key}"
    }

    data object History : Route {
        override val key get() = "history"
    }

    data object Support : Route {
        override val key get() = "support"
    }

    data object Achievements : Route {
        override val key get() = "achievements"
    }

    data object Lists : Route {
        override val key get() = "lists"
    }

    data class SettingsSection(val id: String) : Route {
        override val key get() = "settings:$id"
    }

    data object Settings : Route {
        override val key get() = "settings"
    }

    data class WatchFlow(val anime: Anime? = null, val episode: String? = null) : Route {
        override val key get() = "watch-flow:${anime?.key ?: "personal"}"
    }

    data class EditProfile(val sessionId: String = "${app.animix.core.nowMillis()}-${kotlin.random.Random.nextInt(1_000_000)}") : Route {
        override val key get() = "edit-profile:$sessionId"
    }
}

/** Each tab keeps its own back stack, so switching tabs never loses your place. */
@Stable
class Navigator {
    var tab by mutableStateOf(Tab.Home)
        private set

    private val stacks: Map<Tab, MutableList<Route>> = Tab.entries.associateWith { mutableStateListOf() }

    val current: Route? get() = stacks.getValue(tab).lastOrNull()

    val canGoBack: Boolean get() = stacks.getValue(tab).isNotEmpty() || tab != Tab.Home

    fun select(target: Tab) {
        if (target == tab) stacks.getValue(tab).clear() else tab = target
    }

    fun push(route: Route) {
        val stack = stacks.getValue(tab)
        if (stack.lastOrNull()?.key != route.key) stack.add(route)
    }

    /**
     * Opens an animix:// link: animix://anime/<shikimori id>, animix://yummy/<yummy id>, animix://search/<text>,
     * animix://settings or animix://<tab> (home, catalog, favorites, downloads, profile).
     */
    fun openLink(link: String): Boolean {
        val path = link.trim().removePrefix("animix://").trim('/').split('/')
        when (path.firstOrNull()?.lowercase()) {
            "anime" -> {
                val id = path.getOrNull(1)?.toIntOrNull() ?: return false
                push(Route.Detail(Anime.placeholder(id)))
            }
            "yummy" -> {
                val id = path.getOrNull(1)?.toIntOrNull() ?: return false
                push(Route.Detail(Anime.placeholder(id, AnimeSource.Yummy)))
            }
            "watch" -> {
                val id = path.getOrNull(1)?.toIntOrNull() ?: return false
                push(Route.Watch(Anime.placeholder(id), episode = path.getOrNull(2)?.ifBlank { null }))
            }
            "downloads" -> {
                val id = path.getOrNull(1)?.toIntOrNull() ?: run { tab = Tab.Downloads; return true }
                tab = Tab.Downloads
                push(Route.Downloads(Anime.placeholder(id)))
            }
            "reviews" -> {
                val id = path.getOrNull(1)?.toIntOrNull() ?: return false
                push(Route.Reviews(Anime.placeholder(id)))
            }
            "search" -> push(Route.Search(path.drop(1).joinToString("/").decodeUrlComponent()))
            "settings" -> {
                tab = Tab.Profile
                push(Route.Settings)
                path.getOrNull(1)?.let { section -> push(Route.SettingsSection(section.replaceFirstChar { it.uppercase() })) }
            }
            "lists", "favorites" -> tab = Tab.Lists
            "achievements" -> { tab = Tab.Profile; push(Route.Achievements) }
            "watch-flow" -> push(Route.WatchFlow())
            "edit-profile" -> { tab = Tab.Profile; push(Route.EditProfile()) }
            "history" -> { tab = Tab.Profile; push(Route.History) }
            "support" -> { tab = Tab.Profile; push(Route.Support) }
            else -> tab = Tab.entries.firstOrNull { it.name.equals(path.firstOrNull(), ignoreCase = true) } ?: return false
        }
        return true
    }

    private fun String.decodeUrlComponent(): String =
        runCatching { decodeURLQueryComponent() }.getOrDefault(this)

    /** Returns false when there is nowhere left to go back to. */
    fun back(): Boolean {
        val stack = stacks.getValue(tab)
        return when {
            stack.isNotEmpty() -> { stack.removeAt(stack.lastIndex); true }
            tab != Tab.Home -> { tab = Tab.Home; true }
            else -> false
        }
    }
}
