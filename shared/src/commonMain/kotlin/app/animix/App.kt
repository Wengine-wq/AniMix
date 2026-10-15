package app.animix

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.animix.core.Platform
import app.animix.ui.components.Txt
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.navigation.Tab
import app.animix.ui.screens.catalog.CatalogScreen
import app.animix.ui.screens.detail.DetailScreen
import app.animix.ui.screens.home.AniMixWordmark
import app.animix.ui.screens.home.HomeScreen
import app.animix.ui.screens.profile.DownloadsScreen
import app.animix.ui.screens.profile.FavoritesScreen
import app.animix.ui.screens.profile.ProfileScreen
import app.animix.ui.screens.profile.SettingsScreen
import app.animix.ui.theme.AniMixTheme
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import io.ktor.client.HttpClient
import okio.Path.Companion.toPath

@Composable
fun App(graph: AppGraph, navigator: Navigator = remember { Navigator() }, startLink: String? = null, onExit: () -> Unit = {}) {
    val settings by graph.settings.state.collectAsState()
    LaunchedEffect(Unit) { graph.settings.load() }
    LaunchedEffect(startLink) { startLink?.let(navigator::openLink) }

    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = { HttpClient(app.animix.core.createHttpEngine()) })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory("${Platform.cacheDir}/images".toPath())
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    AniMixTheme(settings.themeMode) {
        AppShell(graph, navigator, onExit)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppShell(graph: AppGraph, navigator: Navigator, onExit: () -> Unit) {
    val colors = AniMixTheme.colors
    BackHandler(enabled = navigator.canGoBack) { if (!navigator.back()) onExit() }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val wide = maxWidth >= 840.dp
        val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                SideRail(navigator)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Content(graph, navigator, PaddingValues(top = statusBar, bottom = navBar))
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                Content(graph, navigator, PaddingValues(top = statusBar, bottom = navBar + 84.dp))
                BottomBar(navigator, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun Content(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val holder = rememberSaveableStateHolder()
    val screenKey = navigator.tab.name + "/" + (navigator.current?.key ?: "root")
    // Remember which route each key showed, so a screen can still draw while it animates out.
    val routes = remember { HashMap<String, Route?>() }
    routes[screenKey] = navigator.current
    AnimatedContent(
        targetState = screenKey,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }) togetherWith fadeOut(tween(140))
        },
        modifier = Modifier.fillMaxSize(),
    ) { key ->
        holder.SaveableStateProvider(key) {
            val route = routes[key]
            val tab = Tab.valueOf(key.substringBefore('/'))
            Box(Modifier.fillMaxSize()) {
                when {
                    route is Route.Detail -> DetailScreen(graph, navigator, route.anime, padding)
                    route is Route.Catalog -> CatalogScreen(graph, navigator, padding, title = route.title, preset = route.preset, showBack = true)
                    route is Route.Search -> CatalogScreen(graph, navigator, padding, searchMode = true, showBack = true, initialSearch = route.query)
                    route == Route.Settings -> SettingsScreen(graph, navigator, padding)
                    key.endsWith("/root") -> when (tab) {
                        Tab.Home -> HomeScreen(graph, navigator, padding)
                        Tab.Catalog -> CatalogScreen(graph, navigator, padding)
                        Tab.Favorites -> FavoritesScreen(padding)
                        Tab.Downloads -> DownloadsScreen(padding)
                        Tab.Profile -> ProfileScreen(navigator, padding)
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(navigator: Navigator, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.background.copy(alpha = 0.92f))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(AniMixTheme.shapes.extraLarge)
            .background(colors.surface)
            .border(1.dp, colors.outline, AniMixTheme.shapes.extraLarge)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { tab ->
            val selected = navigator.tab == tab
            Column(
                Modifier.weight(1f).clip(AniMixTheme.shapes.medium)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { navigator.select(tab) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tab.icon, tab.label, Modifier.size(22.dp), tint = if (selected) colors.accent else colors.textMuted)
                Spacer(Modifier.height(4.dp))
                Txt(tab.label, AniMixTheme.type.micro, color = if (selected) colors.accent else colors.textMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SideRail(navigator: Navigator) {
    val colors = AniMixTheme.colors
    Column(
        Modifier.fillMaxHeight().width(220.dp).background(colors.surface)
            .padding(horizontal = 14.dp, vertical = 20.dp),
    ) {
        AniMixWordmark(Modifier.padding(start = 10.dp, bottom = 28.dp))
        Tab.entries.forEach { tab ->
            val selected = navigator.tab == tab
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(AniMixTheme.shapes.medium)
                    .background(if (selected) colors.accentSoft else colors.surface)
                    .clickable { navigator.select(tab) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(tab.icon, tab.label, Modifier.size(20.dp), tint = if (selected) colors.accent else colors.textSecondary)
                Spacer(Modifier.width(12.dp))
                Txt(tab.label, AniMixTheme.type.bodyStrong, color = if (selected) colors.textPrimary else colors.textSecondary)
            }
        }
        Spacer(Modifier.weight(1f))
        Txt("3.0 New Horizons", AniMixTheme.type.micro, modifier = Modifier.padding(start = 12.dp))
    }
}
