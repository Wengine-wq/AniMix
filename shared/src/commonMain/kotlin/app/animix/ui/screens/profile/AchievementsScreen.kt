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

/** A quiet glass toast at the top when an achievement unlocks. */
@Composable
fun AchievementToastHost(graph: AppGraph, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<Achievement?>(null) }
    LaunchedEffect(Unit) {
        graph.achievements.unlockedNow.collect { achievement ->
            current = achievement
            delay(3_500)
            current = null
            delay(400)
        }
    }
    val shown = current
    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier,
    ) {
        val a = shown ?: return@AnimatedVisibility
        Row(
            Modifier.statusBarsPadding().padding(16.dp).widthIn(max = 420.dp)
                .glass(AniMixTheme.shapes.large).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AchievementIcon(a, unlocked = true, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Txt("Достижение получено", AniMixTheme.type.micro, color = AniMixTheme.colors.accent)
                Txt(a.name, AniMixTheme.type.bodyStrong, maxLines = 2)
            }
        }
    }
}
