package app.animix.ui.screens.profile

import app.animix.ui.theme.MotionVisibility as AnimatedVisibility
import app.animix.ui.theme.animateMotionFloatAsState as animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import app.animix.data.library.episodeDays
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.achievements.Achievement
import app.animix.data.achievements.AchievementCategory
import app.animix.data.achievements.AchievementProgress
import app.animix.resources.Res
import app.animix.resources.allDrawableResources
import app.animix.ui.components.Chip
import app.animix.ui.components.IconCircle
import app.animix.ui.components.Panel
import app.animix.ui.components.Txt
import app.animix.ui.components.glass
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

private enum class Filter(val label: String) { All("Все"), Unlocked("Получены"), History("Из истории"), Locked("Впереди"), Anime("Аниме"), Other("Другое") }

@Composable
fun AchievementsScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val all by graph.achievements.progress.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    val unlockedCount = all.count { it.unlocked }
    val shown = all.filter {
        when (filter) {
            Filter.All -> true
            Filter.Unlocked -> it.unlocked
            Filter.History -> it.fromHistory
            Filter.Locked -> !it.unlocked
            Filter.Anime -> it.achievement.category == AchievementCategory.Anime
            Filter.Other -> it.achievement.category != AchievementCategory.Anime
        }
    }.sortedWith(compareByDescending<AchievementProgress> { it.unlocked }.thenBy { it.achievement.number })

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(320.dp),
            modifier = Modifier.widthIn(max = 1100.dp).fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconCircle(AniMixIcons.Back, onClick = { navigator.back() })
                        Spacer(Modifier.width(12.dp))
                        Txt("Достижения", AniMixTheme.type.title)
                    }
                    Spacer(Modifier.height(20.dp))
                    Summary(unlockedCount, all.size)
                    Spacer(Modifier.height(8.dp))
                    Txt("В AniMix: ${all.count { it.earned }} · Из истории: ${all.count { it.fromHistory }}", AniMixTheme.type.caption)
                    Spacer(Modifier.height(4.dp))
                    Txt("История открывает награды за тайтлы без XP. Часы, марафоны и серии дней учитываются по просмотру в AniMix.", AniMixTheme.type.micro)
                    Spacer(Modifier.height(16.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Filter.entries.toList()) { item ->
                            Chip(item.label, selected = item == filter, onClick = { filter = item })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            items(shown, key = { it.achievement.id }) { AchievementRow(it) }
        }
    }
}

@Composable
private fun Summary(unlocked: Int, total: Int) {
    val colors = AniMixTheme.colors
    val fraction by animateFloatAsState(if (total == 0) 0f else unlocked.toFloat() / total, tween(700))
    Panel {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt("$unlocked", AniMixTheme.type.display)
                    Txt(" / $total", AniMixTheme.type.heading, color = colors.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
                }
                Spacer(Modifier.height(10.dp))
                ProgressBar(fraction)
            }
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    Box(modifier.fillMaxWidth().height(6.dp).clip(AniMixTheme.shapes.pill).background(colors.chip)) {
        Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(AniMixTheme.shapes.pill).background(colors.accentGradient))
    }
}

@Composable
fun AchievementIcon(achievement: Achievement, unlocked: Boolean, size: androidx.compose.ui.unit.Dp) {
    val resource = Res.allDrawableResources["ach_${achievement.id}"] ?: return
    val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Image(
        painterResource(resource),
        contentDescription = achievement.name,
        modifier = Modifier.size(size).alpha(if (unlocked) 1f else 0.35f),
        colorFilter = if (unlocked) null else grey,
    )
}

@Composable
internal fun AchievementRow(progress: AchievementProgress) {
    val colors = AniMixTheme.colors
    val a = progress.achievement
    val hidden = a.secret && !progress.unlocked
    Panel {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hidden) SecretIcon(64.dp) else AchievementIcon(a, progress.unlocked, 64.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(if (hidden) "Секретное достижение" else a.name, AniMixTheme.type.bodyStrong,
                    color = if (progress.unlocked) colors.textPrimary else colors.textSecondary, maxLines = 2)
                Spacer(Modifier.height(2.dp))
                Txt(if (hidden) "Условие откроется вместе с наградой." else a.description, AniMixTheme.type.caption, maxLines = 3)
                if (progress.earned && progress.variant != null) {
                    Spacer(Modifier.height(4.dp))
                    Txt(if (progress.variant == "register") "Получено регистрацией онлайн-профиля" else "Получено входом в аккаунт",
                        AniMixTheme.type.micro, color = colors.textSecondary)
                }
                if (progress.fromHistory) {
                    Spacer(Modifier.height(4.dp))
                    Txt("Из истории просмотров · без XP", AniMixTheme.type.micro, color = colors.textSecondary)
                }
                if (!progress.unlocked && progress.target > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressBar(progress.fraction, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Txt(progressLabel(progress), AniMixTheme.type.micro)
                    }
                }
            }
        }
    }
}

/** A locked secret: a quiet circle with a question mark instead of the badge that would give it away. */
@Composable
private fun SecretIcon(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(AniMixTheme.colors.chip), contentAlignment = Alignment.Center) {
        Txt("?", AniMixTheme.type.title, color = AniMixTheme.colors.textMuted)
    }
}

/**
 * "3 / 10" for counts; watch time is measured in seconds but always shown in hours on both sides,
 * with tenths below 100 hours ("9,4 / 10 ч"), so a raw second count never reads as hours.
 */
internal fun progressLabel(progress: app.animix.data.achievements.AchievementProgress): String {
    val current = progress.current.coerceIn(0, progress.target)
    if (progress.achievement.trigger.metric != "watch_seconds") return "$current / ${progress.target}"
    fun hours(seconds: Int): String {
        val tenths = seconds / 360
        return if (tenths >= 1000 || tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"
    }
    return "${hours(current)} / ${hours(progress.target)} ч"
}

/** What the toast at the top says: an award, or a day added to the run of days. */
private sealed interface Toast {
    class Award(val achievement: Achievement) : Toast
    class Streak(val days: Int) : Toast
}

/**
 * A quiet glass toast at the top: an achievement unlocks, or today's first finished episode adds a day
 * to a run of two or more (that day raises the level, so it is worth a word). One at a time, in order.
 */
@Composable
fun AchievementToastHost(graph: AppGraph, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<Toast?>(null) }
    val queue = remember { kotlinx.coroutines.channels.Channel<Toast>(kotlinx.coroutines.channels.Channel.UNLIMITED) }
    LaunchedEffect(Unit) { graph.achievements.unlockedNow.collect { queue.send(Toast.Award(it)) } }
    LaunchedEffect(Unit) {
        var known: Set<Long>? = null
        graph.library.state.map { it.episodeDays() }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default).collect { days ->
            val today = app.animix.core.localDayIndex()
            val before = known
            known = days
            if (before != null && today !in before && today in days) {
                val run = app.animix.data.library.streaks(days.filterTo(HashSet()) { it <= today }, today).first
                if (run >= 2) queue.send(Toast.Streak(run))
            }
        }
    }
    LaunchedEffect(Unit) {
        for (toast in queue) {
            current = toast
            delay(3_500)
            current = null
            delay(400)
        }
    }
    // The last toast keeps its content while it slides away.
    var last by remember { mutableStateOf<Toast?>(null) }
    current?.let { last = it }
    AnimatedVisibility(
        visible = current != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier,
    ) {
        val toast = last ?: return@AnimatedVisibility
        val colors = AniMixTheme.colors
        Row(
            Modifier.statusBarsPadding().padding(16.dp).widthIn(max = 420.dp)
                .glass(AniMixTheme.shapes.large, tint = 1f, lens = true).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (toast) {
                is Toast.Award -> {
                    AchievementIcon(toast.achievement, unlocked = true, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Txt("Достижение получено", AniMixTheme.type.micro, color = colors.accent)
                        Txt(toast.achievement.name, AniMixTheme.type.bodyStrong, maxLines = 2)
                    }
                }
                is Toast.Streak -> {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                        Icon(AniMixIcons.Flame, null, Modifier.size(22.dp), tint = colors.accent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Txt("Серия дней", AniMixTheme.type.micro, color = colors.accent)
                        Txt("${toast.days} ${profileDaysWord(toast.days)} подряд · уровень растёт", AniMixTheme.type.bodyStrong, maxLines = 2)
                    }
                }
            }
        }
    }
}
