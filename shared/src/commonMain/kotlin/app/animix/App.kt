package app.animix

import app.animix.ui.theme.animateMotionColorAsState as animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import app.animix.ui.theme.animateMotionFloatAsState as animateFloatAsState
import app.animix.ui.theme.LocalUiPerformance
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import app.animix.ui.components.AmbientBackdrop
import app.animix.ui.components.LocalHaze
import app.animix.ui.components.glass
import app.animix.ui.components.pressScale
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.unit.dp
import app.animix.core.Platform
import app.animix.ui.components.Txt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.navigation.Tab
import app.animix.ui.screens.catalog.CatalogScreen
import app.animix.ui.screens.detail.DetailScreen
import app.animix.ui.screens.detail.ReviewsScreen
import app.animix.ui.screens.detail.WatchScreen
import app.animix.ui.screens.home.HomeScreen
import app.animix.ui.screens.downloads.DownloadsScreen
import app.animix.ui.screens.downloads.TitleDownloadsScreen
import app.animix.ui.screens.downloads.OfflineWatchScreen
import app.animix.ui.screens.profile.ListsScreen
import app.animix.ui.screens.profile.SettingsSectionScreen
import app.animix.ui.screens.profile.AchievementsScreen
import app.animix.ui.screens.profile.HistoryScreen
import app.animix.ui.screens.profile.SupportScreen
import app.animix.ui.screens.profile.AchievementToastHost
import app.animix.ui.screens.profile.ProfileScreen
import app.animix.ui.screens.profile.EditProfileScreen
import app.animix.ui.screens.watchflow.WatchFlowScreen
import app.animix.ui.screens.profile.SettingsScreen
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.AniMixMotion
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
    LaunchedEffect(Unit) {
        graph.settings.load()
        graph.library.load()
        graph.achievements.load()
        graph.watchFlow.load()
        graph.downloads.load()
    }
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
            .crossfade(false)
            .build()
    }

    AniMixTheme(settings.themeMode, settings.animationsEnabled, settings.batterySaver) {
        AppShell(graph, navigator, onExit)
    }
}

/** What the ambient backdrop behind the current screen shows; screens set it with [Ambient]. */
@Stable
class AmbientState {
    /** The screen currently on top; only its image is shown. */
    var currentKey by mutableStateOf("")
    val images = mutableStateMapOf<String, String?>()
    val imageUrl: String? get() = images[currentKey]
}

val LocalAmbient = staticCompositionLocalOf { AmbientState() }
private val LocalScreenKey = staticCompositionLocalOf { "" }

/** Sets the soft blurred backdrop behind this screen. Screens that never call it show a quiet accent glow. */
@Composable
fun Ambient(imageUrl: String?) {
    val ambient = LocalAmbient.current
    val key = LocalScreenKey.current
    LaunchedEffect(key, imageUrl) { ambient.images[key] = imageUrl }
}

/**
 * One layout for every platform: the screen fills the window over a soft ambient backdrop,
 * and navigation is a floating glass bar at the bottom, on phones and desktop alike.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppShell(graph: AppGraph, navigator: Navigator, onExit: () -> Unit) {
    val colors = AniMixTheme.colors
    val haze = rememberHazeState()
    val ambient = remember { AmbientState() }
    BackHandler(enabled = navigator.canGoBack) { if (!navigator.back()) onExit() }

    CompositionLocalProvider(LocalHaze provides haze, LocalAmbient provides ambient) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Box(Modifier.fillMaxSize().then(if (LocalUiPerformance.current.batterySaver) Modifier else Modifier.hazeSource(haze))) {
                AmbientBackdrop(ambient.imageUrl)
                Content(graph, navigator, PaddingValues(top = statusBar, bottom = navBar + BottomBarSpace))
            }
            // Keeps the status bar readable over scrolled content.
            Box(
                Modifier.fillMaxWidth().height(statusBar + 12.dp)
                    .background(Brush.verticalGradient(listOf(colors.background.copy(alpha = 0.85f), Color.Transparent))),
            )
            // «Итоги года» are full screen, like stories.
            if (navigator.current !is Route.YearInReview) BottomBar(navigator, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            AchievementToastHost(graph, Modifier.align(Alignment.TopCenter))
        }
    }
}

private val BottomBarSpace = 92.dp

@Composable
private fun Content(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val holder = rememberSaveableStateHolder()
    val screenKey = navigator.tab.name + "/" + (navigator.current?.key ?: "root")
    // Remember which route each key showed, so a screen can still draw while it animates out.
    val routes = remember { HashMap<String, Route?>() }
    routes[screenKey] = navigator.current
    val ambient = LocalAmbient.current
    val motion = LocalUiPerformance.current.animations
    SideEffect { ambient.currentKey = screenKey }
    AnimatedContent(
        targetState = screenKey,
        transitionSpec = {
            val enter = tween<Float>(AniMixMotion.Page, easing = FastOutSlowInEasing)
            if (motion) (fadeIn(enter) + scaleIn(enter, initialScale = 0.985f)) togetherWith fadeOut(tween(AniMixMotion.Fast))
            else EnterTransition.None togetherWith ExitTransition.None
        },
        modifier = Modifier.fillMaxSize(),
    ) { key ->
        holder.SaveableStateProvider(key) {
            val route = routes[key]
            val tab = Tab.valueOf(key.substringBefore('/'))
            CompositionLocalProvider(LocalScreenKey provides key) {
            Box(Modifier.fillMaxSize()) {
                when {
                    route is Route.Detail -> DetailScreen(graph, navigator, route.anime, padding)
                    route is Route.Catalog -> CatalogScreen(graph, navigator, padding, title = route.title, preset = route.preset, showBack = true)
                    route is Route.Search -> CatalogScreen(graph, navigator, padding, searchMode = true, showBack = true, initialSearch = route.query)
                    route == Route.Settings -> SettingsScreen(graph, navigator, padding)
                    route is Route.EditProfile -> EditProfileScreen(graph, navigator, padding)
                    route is Route.WatchFlow -> WatchFlowScreen(graph, navigator, padding, route.anime, route.episode)
                    route is Route.YearInReview -> app.animix.ui.screens.wrapped.YearInReviewScreen(graph, navigator, route.year)
                    route is Route.SettingsSection -> SettingsSectionScreen(graph, navigator, padding, route.id)
                    route == Route.Lists -> ListsScreen(graph, navigator, padding, showBack = true)
                    route == Route.Achievements -> AchievementsScreen(graph, navigator, padding)
                    route == Route.History -> HistoryScreen(graph, navigator, padding)
                    route == Route.Support -> SupportScreen(graph, navigator, padding)
                    route is Route.Reviews -> ReviewsScreen(graph, navigator, route.anime, padding)
                    route is Route.Watch -> WatchScreen(graph, navigator, route.anime, padding, route.episode)
                    route is Route.Offline -> OfflineWatchScreen(graph, navigator, route.downloadId, padding)
                    route is Route.Downloads -> TitleDownloadsScreen(graph, navigator, route.anime, padding, add = route.add)
                    key.endsWith("/root") -> when (tab) {
                        Tab.Home -> HomeScreen(graph, navigator, padding)
                        Tab.Catalog -> CatalogScreen(graph, navigator, padding)
                        Tab.Lists -> ListsScreen(graph, navigator, padding)
                        Tab.Downloads -> DownloadsScreen(graph, navigator, padding)
                        Tab.Profile -> ProfileScreen(graph, navigator, padding)
                    }
                }
            }
            }
        }
    }
}

/** Floating frosted-glass navigation, icons only; the selected tab gets a quiet tinted pill. */
@Composable
private fun BottomBar(navigator: Navigator, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    val shape = AniMixTheme.shapes.pill
    BoxWithConstraints(
        modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .dropShadow(shape, Shadow(radius = 24.dp, color = Color.Black.copy(alpha = if (colors.isDark) 0.35f else 0.08f), offset = DpOffset(0.dp, 8.dp)))
            .glass(shape)
            .padding(6.dp),
    ) {
        val slot = maxWidth / Tab.entries.size
        val position by animateFloatAsState(navigator.tab.ordinal.toFloat(), AniMixMotion.selectionSpring())
        Box(Modifier.offset(x = slot * position)
            .size(width = slot, height = 48.dp).clip(shape).background(colors.accentSoft))
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { tab ->
                val selected = navigator.tab == tab
                val interaction = remember { MutableInteractionSource() }
                val focus by animateFloatAsState(if (selected) 1f else 0f, AniMixMotion.selectionSpring())
                val tint by animateColorAsState(if (selected) colors.accent else colors.textMuted, tween(AniMixMotion.Standard))
                Box(
                    Modifier.weight(1f).height(48.dp)
                        .pressScale(interaction, pressed = 0.9f)
                        .selectable(selected, interaction, indication = null, role = Role.Tab) { navigator.select(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(tab.icon, tab.label, Modifier.size(22.dp).graphicsLayer {
                        scaleX = 1f + 0.08f * focus
                        scaleY = scaleX
                        translationY = -1.5.dp.toPx() * focus
                    }, tint = tint)
                }
            }
        }
    }
}
