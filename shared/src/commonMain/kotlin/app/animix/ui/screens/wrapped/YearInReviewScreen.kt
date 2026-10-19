package app.animix.ui.screens.wrapped

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.library.YearInReview
import app.animix.data.library.yearInReview
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.screens.profile.AchievementIcon
import app.animix.ui.theme.AniMixMotion
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.LocalUiPerformance
import kotlinx.coroutines.launch

private enum class Slide { Intro, Time, Count, Months, Title, Genres, Marathon, Awards, Outro }

private val monthNames = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
private val monthGenitive = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

private fun plural(n: Int, one: String, few: String, many: String) = "$n " + when {
    n % 100 in 11..14 -> many
    n % 10 == 1 -> one
    n % 10 in 2..4 -> few
    else -> many
}

/**
 * «Итоги года» as stories: tap the right side for the next slide, the left side to go back,
 * hold to stay. Only real AniMix viewing counts: confirmed episodes and playback time.
 */
@Composable
fun YearInReviewScreen(graph: AppGraph, navigator: Navigator, year: Int) {
    val library by graph.library.state.collectAsState()
    val achievements by graph.achievements.progress.collectAsState()
    val review = remember(library, achievements, year) { library.yearInReview(year, achievements) }
    val slides = remember(review) {
        Slide.entries.filter { slide ->
            when (slide) {
                Slide.Intro, Slide.Outro -> true
                Slide.Time -> review.minutes > 0
                Slide.Count -> review.episodes > 0
                Slide.Months -> review.bestMonth != null
                Slide.Title -> review.topTitles.isNotEmpty()
                Slide.Genres -> review.topGenres.isNotEmpty()
                Slide.Marathon -> review.marathon > 1 || review.longestStreak > 1
                Slide.Awards -> review.achievements.isNotEmpty()
            }
        }
    }
    val motion = LocalUiPerformance.current.animations
    var index by remember { mutableIntStateOf(0) }
    var held by remember { mutableStateOf(false) }
    val timer = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun go(step: Int) {
        val next = (index + step).coerceIn(0, slides.lastIndex)
        if (next != index) { index = next; scope.launch { timer.snapTo(0f) } }
    }
    LaunchedEffect(index, held, slides) {
        if (held || index >= slides.lastIndex) return@LaunchedEffect
        if (!motion) { timer.snapTo(0f); return@LaunchedEffect }
        timer.animateTo(1f, tween(((1f - timer.value) * 6_500).toInt(), easing = LinearEasing))
        index++
        timer.snapTo(0f)
    }
    val colors = AniMixTheme.colors
    val poster = review.topTitles.firstOrNull()?.anime?.posterUrl
    Box(
        Modifier.fillMaxSize().background(colors.background)
            .pointerInput(slides) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    held = true
                    val started = System_now()
                    val up = waitForUpOrCancellation()
                    held = false
                    // A short tap moves; a long press only pauses.
                    if (up != null && System_now() - started < 350) {
                        if (down.position.x < size.width / 3f) go(-1) else go(1)
                    }
                }
            },
    ) {
        // A quiet mood from the title of the year, under a scrim in the app's own colors.
        if (poster != null && !LocalUiPerformance.current.batterySaver) {
            RemoteImage(poster, Modifier.fillMaxSize().blur(60.dp), shape = RectangleShape)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to colors.background.copy(alpha = 0.75f), 0.5f to colors.background.copy(alpha = 0.88f), 1f to colors.background,
        )))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(
            listOf(colors.accent.copy(alpha = 0.16f), Color.Transparent), radius = 900f,
        )))
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    slides.indices.forEach { i ->
                        val fill = when { i < index -> 1f; i == index -> if (index == slides.lastIndex) 1f else timer.value; else -> 0f }
                        Box(Modifier.weight(1f).height(3.dp).clip(AniMixTheme.shapes.pill).background(colors.outline)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(colors.accent))
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                IconCircle(AniMixIcons.Close, { navigator.back() }, contentDescription = "Закрыть итоги")
            }
            Box(Modifier.weight(1f).fillMaxWidth().widthIn(max = 560.dp).align(Alignment.CenterHorizontally), contentAlignment = Alignment.CenterStart) {
                AnimatedContent(
                    targetState = slides.getOrElse(index) { Slide.Outro },
                    transitionSpec = {
                        if (motion) (fadeIn(tween(AniMixMotion.Standard)) + slideInHorizontally(tween(AniMixMotion.Page)) { it / 8 })
                            .togetherWith(fadeOut(tween(AniMixMotion.Fast)))
                        else fadeIn(tween(0)).togetherWith(fadeOut(tween(0)))
                    },
                ) { slide -> SlideContent(slide, review, onRestart = { index = 0; scope.launch { timer.snapTo(0f) } }, onClose = { navigator.back() }) }
            }
        }
    }
}

private fun System_now(): Long = app.animix.core.nowMillis()

@Composable
private fun Kicker(text: String) = Txt(text.uppercase(), AniMixTheme.type.label, color = AniMixTheme.colors.accent)

@Composable
private fun Big(text: String) = Txt(text, AniMixTheme.type.display.copy(fontSize = AniMixTheme.type.display.fontSize * 1.6f), maxLines = 1)

@Composable
private fun CountUp(target: Int, suffix: String = "") {
    val motion = LocalUiPerformance.current.animations
    val value = remember(target) { Animatable(if (motion) 0f else target.toFloat()) }
    LaunchedEffect(target) { if (motion) value.animateTo(target.toFloat(), tween(1_100)) }
    Big("${value.value.toInt()}$suffix")
}

@Composable
private fun SlideContent(slide: Slide, review: YearInReview, onRestart: () -> Unit, onClose: () -> Unit) {
    val type = AniMixTheme.type
    val colors = AniMixTheme.colors
    Column(Modifier.fillMaxWidth()) {
        when (slide) {
            Slide.Intro -> {
                Kicker("Итоги ${review.year}")
                Spacer(Modifier.height(12.dp))
                Txt(if (review.empty) "Год только начинается" else "Ваш год\nв AniMix", type.display)
                Spacer(Modifier.height(14.dp))
                Txt(
                    if (review.empty) "Посмотрите пару серий в AniMix, и здесь появится ваша история."
                    else "Нажимайте справа, чтобы листать, слева, чтобы вернуться. Удерживайте, чтобы задержаться.",
                    type.body, color = colors.textSecondary,
                )
            }
            Slide.Time -> {
                Kicker("Время в AniMix")
                Spacer(Modifier.height(12.dp))
                if (review.minutes >= 120) CountUp(review.minutes / 60, " ч") else CountUp(review.minutes, " мин")
                Spacer(Modifier.height(10.dp))
                Txt(
                    when {
                        review.minutes >= 24 * 60 -> "Это больше ${plural(review.minutes / (24 * 60), "суток", "суток", "суток")} аниме без перерыва."
                        review.minutes >= 300 -> "Несколько вечеров наедине с хорошими историями."
                        else -> "Хорошее начало. Дальше будет больше."
                    },
                    type.body, color = colors.textSecondary,
                )
            }
            Slide.Count -> {
                Kicker("Посмотрено")
                Spacer(Modifier.height(12.dp))
                CountUp(review.episodes)
                Txt(plural(review.episodes, "серия", "серии", "серий").substringAfter(' '), type.title)
                Spacer(Modifier.height(10.dp))
                Txt("${plural(review.titles, "тайтл", "тайтла", "тайтлов")} и ${plural(review.activeDays, "день", "дня", "дней")} с аниме.", type.body, color = colors.textSecondary)
            }
            Slide.Months -> {
                Kicker("Самый аниме-месяц")
                Spacer(Modifier.height(12.dp))
                Txt(monthNames[review.bestMonth!! - 1].replaceFirstChar { it.uppercase() }, type.display)
                Spacer(Modifier.height(6.dp))
                Txt("${review.bestMonthMinutes / 60} ч ${review.bestMonthMinutes % 60} мин просмотра", type.body, color = colors.textSecondary)
                Spacer(Modifier.height(24.dp))
                val max = review.minutesByMonth.maxOrNull()?.coerceAtLeast(1) ?: 1
                Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    review.minutesByMonth.forEachIndexed { i, minutes ->
                        Box(Modifier.weight(1f).fillMaxHeight((minutes.toFloat() / max).coerceAtLeast(0.04f)).clip(AniMixTheme.shapes.small)
                            .background(if (i + 1 == review.bestMonth) colors.accent else if (minutes > 0) colors.accent.copy(alpha = 0.35f) else colors.outline))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Txt("янв", type.micro); Txt("июнь", type.micro); Txt("дек", type.micro)
                }
            }
            Slide.Title -> {
                val top = review.topTitles.first()
                Kicker("Тайтл года")
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    RemoteImage(top.anime.posterUrl, Modifier.width(132.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.large)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(top.anime.title, type.title, maxLines = 4)
                        Spacer(Modifier.height(6.dp))
                        Txt("${plural(top.episodes, "серия", "серии", "серий")} за год. Похоже, это любовь.", type.body, color = colors.textSecondary)
                    }
                }
                if (review.topTitles.size > 1) {
                    Spacer(Modifier.height(20.dp))
                    review.topTitles.drop(1).forEachIndexed { i, item ->
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt("${i + 2}", type.heading, color = colors.accent, modifier = Modifier.width(28.dp))
                            Txt(item.anime.title, type.bodyStrong, maxLines = 1, modifier = Modifier.weight(1f))
                            Txt(plural(item.episodes, "серия", "серии", "серий"), type.caption)
                        }
                    }
                }
            }
            Slide.Genres -> {
                Kicker("Любимые жанры")
                Spacer(Modifier.height(16.dp))
                val max = review.topGenres.first().second.coerceAtLeast(1)
                review.topGenres.forEachIndexed { i, (genre, weight) ->
                    Txt(genre, if (i == 0) type.title else type.bodyStrong)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(AniMixTheme.shapes.pill).background(colors.outline)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(weight.toFloat() / max).clip(AniMixTheme.shapes.pill)
                            .background(if (i == 0) colors.accentGradient else Brush.linearGradient(listOf(colors.accent.copy(alpha = 0.5f), colors.accent.copy(alpha = 0.5f)))))
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
            Slide.Marathon -> {
                Kicker("Марафоны")
                Spacer(Modifier.height(12.dp))
                if (review.marathon > 1) {
                    CountUp(review.marathon)
                    Txt("серий за один день" + (review.marathonDay?.let { " — ${it.day} ${monthGenitive[it.month - 1]}" } ?: ""), type.title)
                    Spacer(Modifier.height(8.dp))
                    Txt("«Ещё одну и спать» — мы помним.", type.body, color = colors.textSecondary)
                }
                if (review.longestStreak > 1) {
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(AniMixIcons.Flame, null, Modifier.size(22.dp), tint = colors.accent)
                        Spacer(Modifier.width(8.dp))
                        Txt("${plural(review.longestStreak, "день", "дня", "дней")} подряд — самая длинная серия дней", type.bodyStrong)
                    }
                }
            }
            Slide.Awards -> {
                Kicker("Достижения года")
                Spacer(Modifier.height(12.dp))
                CountUp(review.achievements.size)
                Txt(plural(review.achievements.size, "награда", "награды", "наград").substringAfter(' '), type.title)
                Spacer(Modifier.height(20.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    review.achievements.take(12).forEach { AchievementIcon(it.achievement, true, 64.dp) }
                }
            }
            Slide.Outro -> {
                Kicker("${review.year}")
                Spacer(Modifier.height(12.dp))
                Txt(if (review.empty) "Итоги ждут вас" else "Спасибо, что смотрели\nвместе с AniMix", type.display)
                Spacer(Modifier.height(10.dp))
                Txt("Увидимся в ${review.year + 1}.", type.body, color = colors.textSecondary)
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Ещё раз", onRestart, Modifier.weight(1f), icon = AniMixIcons.Retry)
                    SecondaryButton("Закрыть", onClose, Modifier.weight(1f))
                }
            }
        }
    }
}
