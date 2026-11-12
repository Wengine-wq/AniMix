package app.animix

import app.animix.ui.theme.animateMotionColorAsState as animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import app.animix.ui.theme.animateMotionFloatAsState as animateFloatAsState
import app.animix.ui.theme.LocalUiPerformance
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import app.animix.ui.components.AmbientBackdrop
import app.animix.ui.components.LocalHaze
import app.animix.ui.components.glass
import app.animix.ui.components.pressScale
import app.animix.ui.components.focusOutline
import app.animix.ui.components.MessageState
import app.animix.ui.components.LoadingIndicator
import app.animix.ui.icons.AniMixIcons
import app.animix.core.AppLog
import kotlinx.coroutines.CancellationException
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.animix.ui.components.Txt
import app.animix.ui.components.liquidSheen
import app.animix.ui.components.rim
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.unit.dp
import app.animix.core.Platform
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
import app.animix.ui.screens.profile.AchievementToastHost
import app.animix.ui.screens.profile.ProfileScreen
import app.animix.ui.screens.profile.EditProfileScreen
import app.animix.ui.screens.watchflow.WatchFlowScreen
import app.animix.ui.screens.profile.SettingsScreen
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.at
import app.animix.ui.theme.AniMixMotion
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import io.ktor.client.HttpClient
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import kotlin.math.roundToInt
import okio.Path.Companion.toPath

@Composable
fun App(graph: AppGraph, navigator: Navigator = remember { Navigator() }, startLink: String? = null, onExit: () -> Unit = {}) {
    val settings by graph.settings.state.collectAsState()
    var initialized by remember(graph) { mutableStateOf(false) }
    var startupError by remember(graph) { mutableStateOf<Throwable?>(null) }
    var retry by remember(graph) { mutableStateOf(0) }
    LaunchedEffect(graph, retry) {
        startupError = null
        try {
            graph.initialize()
            initialized = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            AppLog.failure("Startup", "data_load_failed", error)
            startupError = error
        }
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

    val level by graph.level.collectAsState()
    AniMixTheme(settings.themeMode.at(level.level), settings.animationsEnabled, settings.batterySaver, settings.liquidGlass) {
        if (initialized) {
            AppShell(graph, navigator, onExit)
            app.animix.ui.screens.profile.HorizonIncoming(graph,navigator)
            // Once per launch, over any screen; «Позже» leaves it in Settings until the next start.
            app.animix.ui.screens.home.UpdateDialog(graph)
        }
        else Box(Modifier.fillMaxSize().background(AniMixTheme.colors.background).padding(20.dp), contentAlignment = Alignment.Center) {
            if (startupError == null) LoadingIndicator()
            else MessageState("Не удалось загрузить данные",
                message = "Сохранённые файлы не сброшены. Проверьте доступ к хранилищу или восстановите резервную копию и повторите загрузку.",
                icon = AniMixIcons.Refresh, actionLabel = "Повторить", onAction = { retry++ })
        }
    }
}

/** The list of each title as its poster says it; a title only in favourites is «Избранное». */
internal fun listMarks(entries: Collection<app.animix.data.library.LibraryEntry>): Map<String, app.animix.ui.components.ListMark> = buildMap {
    for (entry in entries) {
        val label = entry.status?.label ?: if (entry.favorite) "Избранное" else continue
        val total = entry.anime.availableEpisodes
        val progress = if (entry.status == app.animix.data.library.ListStatus.Watching && total > 0 && entry.episodesWatched > 0)
            (entry.episodesWatched.toFloat() / total).coerceIn(0f, 1f) else null
        put(entry.anime.key, app.animix.ui.components.ListMark(label, progress))
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
    // Predictive back (Android 14+): while the finger draws the gesture, the page shrinks a little toward
    // the far edge with rounded corners; letting go goes back, a cancel lets the page settle again.
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backFromRight by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    androidx.compose.ui.backhandler.PredictiveBackHandler(enabled = navigator.canGoBack) { events ->
        try {
            events.collect { event -> backFromRight = event.swipeEdge == 1; backProgress = event.progress }
            backProgress = 0f
            if (!navigator.back()) onExit()
        } finally { backProgress = 0f }
    }

    // Every poster knows the viewer's lists; equal marks do not recompose the cards when only a position is saved.
    val library by graph.library.state.collectAsState()
    val marks = remember(library.entries) { listMarks(library.entries.values) }
    val posterActions = remember(graph, navigator) {
        app.animix.ui.components.PosterActions(
            entry = { graph.library.state.value.entry(it) },
            watch = { navigator.push(Route.Watch(it)) },
            plan = { graph.library.setStatus(it, app.animix.data.library.ListStatus.Planned) },
            favorite = { graph.library.toggleFavorite(it) },
            download = { navigator.push(Route.Downloads(it, add = true)) },
            open = { navigator.push(Route.Detail(it)) },
        )
    }
    val posterMenu = remember { app.animix.ui.components.PosterMenuState() }
    // Leaving the screen closes the menu of a poster that is no longer there.
    LaunchedEffect(navigator.current, navigator.tab) { posterMenu.request = null }
    CompositionLocalProvider(LocalHaze provides haze, LocalAmbient provides ambient, app.animix.ui.components.LocalListMarks provides marks,
        app.animix.ui.components.LocalPosterActions provides posterActions, app.animix.ui.components.LocalPosterMenu provides posterMenu) {
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
            val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            // A phone turned sideways on a player screen is for the video: no tab bar over it,
            // the same as fullscreen. Desktop windows are wide by nature and keep the bar.
            val current = navigator.current
            val playerLandscape = Platform.name == "Android" && maxWidth > maxHeight &&
                (current is Route.Watch || current is Route.Offline || current is Route.WatchFlow)
            val pip by app.animix.data.playback.PictureInPicture.active.collectAsState()
            val showBar = current !is Route.YearInReview && !playerLandscape && !pip
            Box(Modifier.fillMaxSize().then(if (LocalUiPerformance.current.batterySaver) Modifier else Modifier.hazeSource(haze))) {
                AmbientBackdrop(ambient.imageUrl)
                val motion = LocalUiPerformance.current.animations
                val backShape = RoundedCornerShape((28 * backProgress).dp)
                Box(Modifier.fillMaxSize().then(if (motion && backProgress > 0f) Modifier.graphicsLayer {
                    val p = backProgress
                    scaleX = 1f - 0.08f * p; scaleY = scaleX
                    translationX = (if (backFromRight) -1f else 1f) * 18.dp.toPx() * p
                    shape = backShape; clip = true
                } else Modifier)) {
                    Content(graph, navigator, if (pip) PaddingValues(0.dp) else PaddingValues(top = statusBar, bottom = navBar + if (showBar) BottomBarSpace else 0.dp))
                }
            }
            // Keeps the status bar readable over scrolled content.
            Box(
                Modifier.fillMaxWidth().height(statusBar + 12.dp)
                    .background(Brush.verticalGradient(listOf(colors.background.copy(alpha = 0.85f), Color.Transparent))),
            )
            // «Итоги года» are full screen, like stories.
            if (showBar) BottomBar(navigator, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            app.animix.ui.components.PosterMenuHost(posterMenu, posterActions, topInset = statusBar, bottomInset = navBar)
            AchievementToastHost(graph, Modifier.align(Alignment.TopCenter))
            SaveErrorBar(graph, Modifier.align(Alignment.TopCenter).padding(top = statusBar + 8.dp))
        }
    }
}

/** Saving the library keeps failing: say so plainly instead of losing edits on the next start. */
@Composable
private fun SaveErrorBar(graph: AppGraph, modifier: Modifier = Modifier) {
    val error by graph.library.saveError.collectAsState()
    val text = error ?: return
    val colors = AniMixTheme.colors
    Row(modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth().clip(AniMixTheme.shapes.medium)
        .background(colors.surfaceRaised).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AniMixIcons.Info, null, Modifier.size(18.dp), tint = colors.accent)
        Spacer(Modifier.width(10.dp))
        Txt(text, AniMixTheme.type.caption, color = colors.textPrimary)
    }
}

private val BottomBarSpace = 92.dp

/** How much wider than the others the chosen tab is, for its name. */
private const val SelectedShare = 2.3f

@Composable
private fun Content(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val holder = rememberSaveableStateHolder()
    val screenKey = navigator.tab.name + "/" + (navigator.current?.key ?: "root")
    // Remember which route each key showed, so a screen can still draw while it animates out.
    val routes = remember { HashMap<String, Route?>() }
    routes[screenKey] = navigator.current
    // How deep each screen sits in its tab: a deeper one arrives from the right, «Назад» returns to the left.
    val depths = remember { HashMap<String, Int>() }
    depths[screenKey] = navigator.depth
    val ambient = LocalAmbient.current
    val motion = LocalUiPerformance.current.animations
    val liquid = LocalUiPerformance.current.liquid
    SideEffect { ambient.currentKey = screenKey }
    AnimatedContent(
        targetState = screenKey,
        transitionSpec = {
            val enter = tween<Float>(AniMixMotion.Page, easing = FastOutSlowInEasing)
            val slide = tween<androidx.compose.ui.unit.IntOffset>(AniMixMotion.Page, easing = FastOutSlowInEasing)
            val sameTab = initialState.substringBefore('/') == targetState.substringBefore('/')
            val step = (depths[targetState] ?: 0) - (depths[initialState] ?: 0)
            when {
                !motion -> EnterTransition.None togetherWith ExitTransition.None
                // Calm motion with frosted glass: every page simply fades in place.
                !liquid -> (fadeIn(enter) + scaleIn(enter, initialScale = 0.985f)) togetherWith fadeOut(tween(AniMixMotion.Fast))
                // A short travel, not a full-width push: the page settles into place while it fades in.
                sameTab && step > 0 -> (fadeIn(enter) + slideInHorizontally(slide) { it / 12 }) togetherWith
                    (fadeOut(tween(AniMixMotion.Fast)) + slideOutHorizontally(slide) { -it / 24 })
                sameTab && step < 0 -> ((fadeIn(enter) + slideInHorizontally(slide) { -it / 24 }) togetherWith
                    (fadeOut(tween(AniMixMotion.Fast)) + slideOutHorizontally(slide) { it / 12 })).apply { targetContentZIndex = -1f }
                // Tabs are neighbours, not steps: they cross-fade in place.
                else -> (fadeIn(enter) + scaleIn(enter, initialScale = 0.985f)) togetherWith fadeOut(tween(AniMixMotion.Fast))
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { key ->
        holder.SaveableStateProvider(key) {
            val route = routes[key]
            val tab = Tab.valueOf(key.substringBefore('/'))
            CompositionLocalProvider(LocalScreenKey provides key) {
            Box(Modifier.fillMaxSize()) {
                when {
                    route == Route.Pulse -> app.animix.ui.screens.pulse.PulseScreen(graph, navigator, padding)
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
                    route == Route.Friends -> app.animix.ui.screens.profile.FriendsScreen(graph,navigator,padding)
                    route == Route.Menu -> app.animix.ui.screens.profile.MenuScreen(graph, navigator, padding)
                    route == Route.Level -> app.animix.ui.screens.profile.LevelScreen(graph, navigator, padding)
                    route == Route.Notifications -> app.animix.ui.screens.profile.NotificationsScreen(graph, navigator, padding)
                    route is Route.FriendProfile -> app.animix.ui.screens.profile.FriendProfileScreen(graph,navigator,route.id,padding)
                    route == Route.Collections -> app.animix.ui.screens.collections.CollectionsScreen(graph, navigator, padding)
                    route is Route.Collection -> app.animix.ui.screens.collections.CollectionScreen(graph, navigator, route.id, padding)
                    route is Route.FriendCollection -> app.animix.ui.screens.profile.FriendCollectionScreen(graph, navigator, route.id, padding)
                    route is Route.FriendAchievements -> app.animix.ui.screens.profile.FriendAchievementsScreen(graph, navigator, route.id, padding)
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

/**
 * Floating glass navigation. The chosen tab is a wider slot with its icon and name, so where one is
 * reads in words; the others are icons. Everything that moves is driven by one value per tab (how
 * chosen it is, 0..1): the slot widths, the pill and the name are all read from it while laying out
 * and drawing, so they can never drift apart. The pill is a blend of the slots it is leaving and
 * entering; with liquid glass its edge in the direction of travel runs ahead and the other lags, so it
 * stretches like a drop and settles without bouncing. The name unrolls from the icon as its slot
 * widens instead of appearing at full width.
 */
@Composable
private fun BottomBar(navigator: Navigator, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    val shape = AniMixTheme.shapes.pill
    val tabs = Tab.entries
    val liquid = LocalUiPerformance.current.liquid
    val haptics = app.animix.ui.components.rememberHaptics()
    // How chosen each tab is. One spring for all of them: what one tab gives up, the other takes.
    val chosen = tabs.map { tab -> animateFloatAsState(if (navigator.tab == tab) 1f else 0f, AniMixMotion.selectionSpring()) }
    fun share(index: Int) = 1f + (SelectedShare - 1f) * chosen[index].value
    Box(
        modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .dropShadow(shape, Shadow(radius = 24.dp, color = Color.Black.copy(alpha = if (colors.isDark) 0.35f else 0.08f), offset = DpOffset(0.dp, 8.dp)))
            .glass(shape, tint = 0.7f, lens = true)
            .padding(6.dp),
    ) {
        // The pill: measured and placed from the same values as the slots, in the layout pass.
        Box(Modifier.layout { measurable, constraints ->
                val total = tabs.indices.sumOf { share(it).toDouble() }.toFloat()
                val bar = constraints.maxWidth
                var left = 0f; var right = 0f; var leftWeight = 0f; var rightWeight = 0f; var plain = 0f
                var x = 0f
                // A drop: its left edge keeps to the leftmost tab that is still chosen, its right edge to the rightmost.
                val pull = if (liquid) 3f else 0f
                val before = FloatArray(tabs.size); val after = FloatArray(tabs.size)
                for (i in tabs.indices) before[i] = (if (i > 0) before[i - 1] + chosen[i - 1].value else 0f)
                for (i in tabs.indices.reversed()) after[i] = (if (i < tabs.lastIndex) after[i + 1] + chosen[i + 1].value else 0f)
                for (i in tabs.indices) {
                    val width = bar * share(i) / total
                    val f = chosen[i].value.coerceAtLeast(0f)
                    val l = f * (1f + pull * after[i].coerceAtLeast(0f)); val r = f * (1f + pull * before[i].coerceAtLeast(0f))
                    left += l * x; leftWeight += l
                    right += r * (x + width); rightWeight += r
                    plain += f * width
                    x += width
                }
                val start = if (leftWeight > 0f) left / leftWeight else 0f
                val end = if (rightWeight > 0f) right / rightWeight else bar.toFloat()
                val width = (end - start).coerceAtLeast(1f)
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width.roundToInt(), 48.dp.roundToPx()))
                layout(bar, placeable.height) {
                    placeable.placeWithLayer(0, 0) {
                        translationX = start
                        // A stretched drop gets a touch thinner.
                        scaleY = 1f - ((width / plain.coerceAtLeast(1f)) - 1f).coerceIn(0f, 0.6f) * 0.14f
                    }
                }
            }
            .clip(shape).background(colors.accentSoft).liquidSheen().rim(shape, accent = true))
        Layout(
            content = {
                tabs.forEachIndexed { index, tab ->
                    val selected = navigator.tab == tab
                    val interaction = remember { MutableInteractionSource() }
                    val tint by animateColorAsState(if (selected) colors.accent else colors.textSecondary, tween(AniMixMotion.Standard))
                    Row(
                        Modifier.selectable(selected, interaction, indication = null, role = Role.Tab) { if (!selected) haptics.tick(); navigator.select(tab) }
                            .focusOutline(interaction, shape),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(tab.icon, tab.label, Modifier.pressScale(interaction, pressed = 0.86f).size(22.dp), tint = tint)
                        // The name unrolls with the slot: its width and opacity follow how chosen the tab is, so the
                        // icon glides aside instead of jumping, and nothing ever overflows a narrow slot.
                        Txt(tab.label, AniMixTheme.type.label, color = colors.accentText, maxLines = 1,
                            modifier = Modifier.clearAndSetSemantics { }.clipToBounds().layout { measurable, _ ->
                                val text = measurable.measure(androidx.compose.ui.unit.Constraints())
                                val shown = ((chosen[index].value - 0.3f) / 0.7f).coerceIn(0f, 1f)
                                val gap = 6.dp.roundToPx()
                                layout(((text.width + gap) * shown).roundToInt(), text.height) {
                                    text.placeWithLayer(gap, 0) { alpha = shown * shown }
                                }
                            })
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) { measurables, constraints ->
            val total = tabs.indices.sumOf { share(it).toDouble() }.toFloat()
            val height = 48.dp.roundToPx()
            var x = 0f
            val placed = measurables.mapIndexed { index, measurable ->
                val left = x.roundToInt()
                x += constraints.maxWidth * share(index) / total
                measurable.measure(androidx.compose.ui.unit.Constraints.fixed((x.roundToInt() - left).coerceAtLeast(0), height)) to left
            }
            layout(constraints.maxWidth, height) { placed.forEach { (placeable, left) -> placeable.place(left, 0) } }
        }
    }
}
