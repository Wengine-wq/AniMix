package app.animix.ui.screens.wrapped

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.core.localDayIndex
import app.animix.core.nowMillis
import app.animix.core.weekdayOf
import app.animix.data.library.LocalDay
import app.animix.data.library.YearInReview
import app.animix.data.library.dayToDate
import app.animix.data.library.reviewAccess
import app.animix.data.library.yearInReview
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.screens.profile.AchievementIcon
import app.animix.ui.theme.AniMixMotion
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.LocalUiPerformance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import app.animix.data.playback.ReviewMusic
import app.animix.data.playback.createReviewMusic
import app.animix.resources.Res
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private enum class Slide { Intro, Time, Calendar, Count, Months, Title, Genres, Habits, Ways, Marathon, Awards, Outro }

private val monthNames = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
private val monthGenitive = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
private val weekdayShort = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
private val weekdayNames = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")

private fun plural(n: Int, one: String, few: String, many: String) = "$n " + when {
    n % 100 in 11..14 -> many
    n % 10 == 1 -> one
    n % 10 in 2..4 -> few
    else -> many
}

private fun hoursLabel(seconds: Int): String = if (seconds >= 3600) "${seconds / 3600} ч ${seconds % 3600 / 60} мин" else "${seconds / 60} мин"

/**
 * «Итоги года» as stories: tap right for the next slide, left to go back, hold to stay.
 * Every slide is a picture first: posters, a clock of the day, a year calendar, charts.
 * Only real AniMix viewing counts, online and offline.
 */
@Composable
fun YearInReviewScreen(graph: AppGraph, navigator: Navigator, year: Int) {
    val library by graph.library.state.collectAsState()
    val achievements by graph.achievements.progress.collectAsState()
    val access = remember(library) { library.reviewAccess(dayToDate(localDayIndex())) }
    if (access.year == year && !access.open) {
        Locked(access.titlesWatched, access.titlesNeeded, access.opensOn, onClose = { navigator.back() })
        return
    }
    val review = remember(library, achievements, year) { library.yearInReview(year, achievements) }
    val slides = remember(review) {
        Slide.entries.filter { slide ->
            when (slide) {
                Slide.Intro, Slide.Outro -> true
                Slide.Time -> review.minutes > 0
                Slide.Calendar -> review.activeDays > 0
                Slide.Count -> review.episodes > 0
                Slide.Months -> review.bestMonth != null
                Slide.Title -> review.topTitles.isNotEmpty()
                Slide.Genres -> review.topGenres.isNotEmpty()
                Slide.Habits -> review.peakWeekday != null
                Slide.Ways -> review.usage.onlineSeconds + review.usage.offlineSeconds > 0
                Slide.Marathon -> review.marathon > 1 || review.longestStreak > 1 || review.usage.longestSessionSeconds >= 3600
                Slide.Awards -> review.achievements.isNotEmpty()
            }
        }
    }
    val motion = LocalUiPerformance.current.animations
    var index by remember { mutableIntStateOf(0) }
    var held by remember { mutableStateOf(false) }
    val settings by graph.settings.state.collectAsState()
    val foreground by graph.social.foreground.collectAsState()
    var music by remember { mutableStateOf<ReviewMusic?>(null) }
    var musicError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit){
        try{
            val created=withContext(NonCancellable){createReviewMusic(Res.readBytes("files/review_horizon.ogg"))}
            if(isActive)music=created else created.close()
        }catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}catch(_:Exception){musicError=true}
    }
    val currentMusic=music
    DisposableEffect(currentMusic){onDispose{currentMusic?.close()}}
    LaunchedEffect(music,settings.reviewMusicEnabled,foreground,held){if(settings.reviewMusicEnabled&&foreground&&!held)music?.play() else music?.pause()}
    val timer = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun go(step: Int) {
        val next = (index + step).coerceIn(0, slides.lastIndex)
        if (next != index) { index = next; scope.launch { timer.snapTo(0f) } }
    }
    LaunchedEffect(index, held, slides, motion, foreground) {
        if (held || !foreground || index >= slides.lastIndex) return@LaunchedEffect
        if (!motion) { timer.snapTo(0f); return@LaunchedEffect }
        timer.animateTo(1f, tween(((1f - timer.value) * 7_500).toInt(), easing = LinearEasing))
        index++
        timer.snapTo(0f)
    }
    val colors = AniMixTheme.colors
    val slide = slides.getOrElse(index) { Slide.Outro }
    LaunchedEffect(slide){if(slide==Slide.Outro)graph.library.markReviewSeen(year)}
    val poster = review.topTitles.firstOrNull()?.anime?.posterUrl
    Box(
        Modifier.fillMaxSize().background(colors.background)
            .pointerInput(slides) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    held = true
                    val started = nowMillis()
                    val up = waitForUpOrCancellation()
                    held = false
                    // A short tap moves; a long press only pauses.
                    if (up != null && nowMillis() - started < 350) {
                        if (down.position.x < size.width / 3f) go(-1) else go(1)
                    }
                }
            },
    ) {
        if (poster != null && !LocalUiPerformance.current.batterySaver) {
            RemoteImage(poster, Modifier.fillMaxSize().blur(70.dp).graphicsLayer { alpha = 0.55f }, shape = RectangleShape)
        }
        // The accent glow drifts with the slides, so moving on feels like moving through the year.
        val glowX by animateMotionFloat((index.toFloat() / slides.size.coerceAtLeast(1)).coerceIn(0f, 1f))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to colors.background.copy(alpha = 0.7f), 0.55f to colors.background.copy(alpha = 0.9f), 1f to colors.background,
        )))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(
                listOf(colors.accent.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(constraints.maxWidth * (0.15f + glowX * 0.7f), constraints.maxHeight * 0.3f),
                radius = constraints.maxWidth * 0.9f,
            )))
        }
        ReviewAtmosphere(motion && foreground)
        Txt("$year",AniMixTheme.type.display.copy(fontSize=AniMixTheme.type.display.fontSize*3.2f),
            color=colors.accent.copy(alpha=.06f),modifier=Modifier.align(Alignment.BottomEnd).padding(24.dp))
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
                IconCircle(if(settings.reviewMusicEnabled)AniMixIcons.Volume else AniMixIcons.Muted,
                    {graph.settings.update{it.copy(reviewMusicEnabled=!it.reviewMusicEnabled)}},
                    contentDescription=if(settings.reviewMusicEnabled)"Выключить музыку" else "Включить музыку")
                Spacer(Modifier.width(8.dp))
                IconCircle(AniMixIcons.Close, { navigator.back() }, contentDescription = "Закрыть итоги")
            }
            if(musicError)Txt("Музыка недоступна",AniMixTheme.type.micro)
            BoxWithConstraints(Modifier.weight(1f).widthIn(max=1100.dp).fillMaxWidth().align(Alignment.CenterHorizontally), contentAlignment = Alignment.CenterStart) {
              val wide=maxWidth>=840.dp
              Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(32.dp)){
                AnimatedContent(
                    targetState = slide,
                    modifier=Modifier.weight(1f),
                    transitionSpec = {
                        if (motion) (fadeIn(tween(AniMixMotion.Standard)) + slideInHorizontally(tween(AniMixMotion.Page)) { it / 6 })
                            .togetherWith(fadeOut(tween(AniMixMotion.Fast)))
                        else fadeIn(tween(0)).togetherWith(fadeOut(tween(0)))
                    },
                ) { current -> SlideContent(current, review, onRestart = { index = 0; scope.launch { timer.snapTo(0f) } }, onClose = { navigator.back() }) }
                if(wide && review.topTitles.isNotEmpty())Column(Modifier.weight(.8f),verticalArrangement=Arrangement.spacedBy(24.dp)){
                    if(slide!=Slide.Intro)PosterFan(review.topTitles.map{it.anime.posterUrl})
                    if(slide!=Slide.Outro)SummaryCard(review,Modifier.fillMaxWidth())
                }
              }
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                androidx.compose.material3.TextButton(onClick={go(-1)},enabled=index>0){Txt("Назад",AniMixTheme.type.label)}
                Txt("${index+1} / ${slides.size}",AniMixTheme.type.micro)
                androidx.compose.material3.TextButton(onClick={if(index==slides.lastIndex)navigator.back() else go(1)}){Txt(if(index==slides.lastIndex)"Закрыть" else "Далее",AniMixTheme.type.label)}
            }
        }
    }
}

@Composable private fun ReviewAtmosphere(motion:Boolean){
    val phase=remember{Animatable(0f)}
    LaunchedEffect(motion){if(!motion){phase.snapTo(0f);return@LaunchedEffect};while(isActive){phase.snapTo(0f);phase.animateTo(1f,tween(24000,easing=LinearEasing))}}
    val colors=AniMixTheme.colors
    Canvas(Modifier.fillMaxSize()){
        repeat(16){i->
            val x=((i*.173f+phase.value*.05f)%1f)*size.width
            val y=((i*.293f+sin((phase.value+i)*2*PI).toFloat()*.025f)%1f)*size.height
            drawCircle(colors.accent.copy(alpha=.06f+(i%3)*.025f),radius=(8+i%4*12).dp.toPx(),center=Offset(x,y))
        }
    }
}

@Composable
private fun animateMotionFloat(target: Float): State<Float> {
    val value = remember { Animatable(target) }
    val motion = LocalUiPerformance.current.animations
    LaunchedEffect(target, motion) { if (motion) value.animateTo(target, tween(1_200, easing = FastOutSlowInEasing)) else value.snapTo(target) }
    return value.asState()
}

/** Elements of a slide come in one after another, a little from below. */
@Composable
private fun Modifier.appear(order: Int): Modifier {
    val motion = LocalUiPerformance.current.animations
    val progress = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!motion) return@LaunchedEffect
        delay(120L + order * 110L)
        progress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow))
    }
    return graphicsLayer { alpha = progress.value; translationY = (1f - progress.value) * 36.dp.toPx() }
}

/** 0 → 1 once the slide is shown, for charts that grow. */
@Composable
private fun rememberGrowth(durationMs: Int = 1_100, delayMs: Long = 250): Float {
    val motion = LocalUiPerformance.current.animations
    val value = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) { delay(delayMs); value.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) } }
    return value.value
}

@Composable
private fun Kicker(text: String, order: Int = 0) =
    Txt(text.uppercase(), AniMixTheme.type.label, color = AniMixTheme.colors.accent, modifier = Modifier.appear(order))

@Composable
private fun CountUp(target: Int, suffix: String = "", order: Int = 1) {
    val motion = LocalUiPerformance.current.animations
    val value = remember(target) { Animatable(if (motion) 0f else target.toFloat()) }
    LaunchedEffect(target) { if (motion) { delay(200); value.animateTo(target.toFloat(), tween(1_200, easing = FastOutSlowInEasing)) } }
    Txt("${value.value.toInt()}$suffix", AniMixTheme.type.display.copy(fontSize = AniMixTheme.type.display.fontSize * 1.7f), maxLines = 1, modifier = Modifier.appear(order))
}

@Composable
private fun Caption(text: String, order: Int) = Txt(text, AniMixTheme.type.body, color = AniMixTheme.colors.textSecondary, modifier = Modifier.appear(order))

@Composable
private fun SlideContent(slide: Slide, review: YearInReview, onRestart: () -> Unit, onClose: () -> Unit) {
    val type = AniMixTheme.type
    val colors = AniMixTheme.colors
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical=24.dp)) {
        when (slide) {
            Slide.Intro -> {
                if (review.topTitles.isNotEmpty()) {
                    PosterFan(review.topTitles.map { it.anime.posterUrl })
                    Spacer(Modifier.height(28.dp))
                }
                Kicker("Итоги ${review.year}")
                Spacer(Modifier.height(10.dp))
                Txt(if (review.empty) "Год только начинается" else "Ваш год\nв AniMix", type.display, modifier = Modifier.appear(1))
                Spacer(Modifier.height(14.dp))
                review.persona?.let {
                    Txt(it, type.label, color = colors.onAccent, modifier = Modifier.appear(2).clip(AniMixTheme.shapes.pill)
                        .background(colors.accentGradient).padding(horizontal = 14.dp, vertical = 8.dp))
                    Spacer(Modifier.height(14.dp))
                }
                Caption(if (review.empty) "Посмотрите пару серий в AniMix, и здесь появится ваша история."
                    else "Нажимайте справа, чтобы листать, слева — чтобы вернуться. Удерживайте, чтобы задержаться.", 3)
            }
            Slide.Time -> {
                Kicker("Время в AniMix")
                Spacer(Modifier.height(8.dp))
                if (review.minutes >= 120) CountUp(review.minutes / 60, " ч") else CountUp(review.minutes, " мин")
                Spacer(Modifier.height(20.dp))
                val description: @Composable () -> Unit = {
                    Column {
                        review.persona?.let { Txt(it, type.title, modifier = Modifier.appear(3)) }
                        review.peakHour?.let {
                            Spacer(Modifier.height(6.dp))
                            Caption("Чаще всего вы смотрите около ${it}:00.", 4)
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if(maxWidth < 480.dp * LocalDensity.current.fontScale) {
                        Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {
                            DayClock(review.usage.hours,Modifier.size(180.dp).appear(2))
                            description()
                        }
                    }else Row(verticalAlignment=Alignment.CenterVertically) {
                        DayClock(review.usage.hours,Modifier.size(180.dp).appear(2))
                        Spacer(Modifier.width(18.dp));Box(Modifier.weight(1f)){description()}
                    }
                }
            }
            Slide.Calendar -> {
                Kicker("Год по дням")
                Spacer(Modifier.height(8.dp))
                CountUp(review.activeDays)
                Txt(plural(review.activeDays, "день", "дня", "дней").substringAfter(' ') + " с аниме", type.title, modifier = Modifier.appear(2))
                Spacer(Modifier.height(18.dp))
                YearHeatmap(review.year, review.days, Modifier.fillMaxWidth().appear(3))
                if (review.longestStreak > 1) {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.appear(4), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AniMixIcons.Flame, null, Modifier.size(18.dp), tint = colors.accent)
                        Spacer(Modifier.width(6.dp))
                        Txt("Лучшая серия — ${plural(review.longestStreak, "день", "дня", "дней")} подряд", type.bodyStrong)
                    }
                }
            }
            Slide.Count -> {
                Kicker("Посмотрено")
                Spacer(Modifier.height(8.dp))
                CountUp(review.episodes)
                Txt(plural(review.episodes, "серия", "серии", "серий").substringAfter(' '), type.title, modifier = Modifier.appear(2))
                Spacer(Modifier.height(18.dp))
                EpisodeTiles(review.episodes, Modifier.appear(3))
                Spacer(Modifier.height(14.dp))
                Caption("${plural(review.titles, "тайтл", "тайтла", "тайтлов")}" +
                    (if (review.newTitles > 0) ", из них ${plural(review.newTitles, "новый", "новых", "новых")} для вас в этом году" else "") + ".", 4)
            }
            Slide.Months -> {
                Kicker("Самый аниме-месяц")
                Spacer(Modifier.height(10.dp))
                Txt(monthNames[review.bestMonth!! - 1].replaceFirstChar { it.uppercase() }, type.display, modifier = Modifier.appear(1))
                Caption("${hoursLabel(review.bestMonthMinutes * 60)} просмотра", 2)
                Spacer(Modifier.height(24.dp))
                val grow = rememberGrowth()
                val max = review.minutesByMonth.maxOrNull()?.coerceAtLeast(1) ?: 1
                Row(Modifier.fillMaxWidth().height(220.dp).appear(3), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    review.minutesByMonth.forEachIndexed { i, minutes ->
                        Box(Modifier.weight(1f).fillMaxHeight((minutes.toFloat() / max * grow).coerceAtLeast(0.03f)).clip(AniMixTheme.shapes.small)
                            .background(if (i + 1 == review.bestMonth) colors.accentGradient else Brush.verticalGradient(
                                listOf(colors.accent.copy(alpha = if (minutes > 0) 0.4f else 0f), colors.outline))))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("янв", "апр", "июл", "окт", "дек").forEach { Txt(it, type.micro) }
                }
            }
            Slide.Title -> {
                val top = review.topTitles.first()
                Kicker("Тайтл года")
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().appear(1), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(220.dp).background(Brush.radialGradient(listOf(colors.accent.copy(alpha = 0.35f), Color.Transparent))))
                    RemoteImage(top.anime.posterUrl, Modifier.width(220.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.large)
                }
                Spacer(Modifier.height(18.dp))
                Txt(top.anime.title, type.title, maxLines = 3, modifier = Modifier.appear(2))
                val seconds = review.titlesByTime.firstOrNull { it.first.key == top.anime.key }?.second
                Caption("${plural(top.episodes, "серия", "серии", "серий")}" + (seconds?.let { " и ${hoursLabel(it)}" } ?: "") + " за год. Похоже, это любовь.", 3)
                if (review.topTitles.size > 1) {
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.appear(4), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        review.topTitles.drop(1).forEachIndexed { i, item ->
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                RemoteImage(item.anime.posterUrl, Modifier.width(40.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.small)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Txt("#${i + 2}", type.label, color = colors.accent)
                                    Txt(item.anime.title, type.micro, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
            Slide.Genres -> {
                Kicker("Любимые жанры")
                Spacer(Modifier.height(10.dp))
                Txt(review.topGenres.first().first, type.display, maxLines = 2, modifier = Modifier.appear(1))
                Spacer(Modifier.height(20.dp))
                GenreBubbles(review.topGenres, Modifier.fillMaxWidth().height(230.dp).appear(2))
            }
            Slide.Habits -> {
                Kicker("Привычки")
                Spacer(Modifier.height(10.dp))
                Txt("Ваш день — ${weekdayNames[review.peakWeekday!!]}", type.title, modifier = Modifier.appear(1))
                Spacer(Modifier.height(18.dp))
                WeekBars(review.usage.weekdays, review.peakWeekday!!, Modifier.fillMaxWidth().height(130.dp).appear(2))
                review.favoriteStudio?.let { (studio, seconds) ->
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth().appear(3).clip(AniMixTheme.shapes.large).background(colors.surfaceRaised.copy(alpha = 0.7f)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                            Icon(AniMixIcons.Mic, null, Modifier.size(20.dp), tint = colors.onAccent)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Txt("Любимая озвучка", type.micro)
                            Txt(studio, type.heading, maxLines = 1)
                            Txt(hoursLabel(seconds), type.caption)
                        }
                    }
                }
            }
            Slide.Ways -> {
                Kicker("Как вы смотрели")
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShareDonut(review.offlineShare, Modifier.size(140.dp).appear(1))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f).appear(2)) {
                        Txt("${((1 - review.offlineShare) * 100).toInt()}% онлайн", type.title)
                        Txt("${(review.offlineShare * 100).toInt()}% из загрузок", type.bodyStrong, color = colors.textSecondary)
                    }
                }
                val platforms = review.usage.platforms
                if (platforms.size > 1) {
                    Spacer(Modifier.height(18.dp))
                    Caption(platforms.entries.sortedByDescending { it.value }.joinToString(" · ") { "${if (it.key == "Android") "телефон" else "ПК"} ${hoursLabel(it.value)}" }, 3)
                }
                if (review.usage.skips > 0) {
                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.appear(4), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AniMixIcons.SkipAhead, null, Modifier.size(20.dp), tint = colors.accent)
                        Spacer(Modifier.width(8.dp))
                        Txt("${plural(review.usage.skips, "пропуск", "пропуска", "пропусков")} опенингов и эндингов — сэкономлено ${hoursLabel(review.usage.skippedSeconds)}", type.bodyStrong)
                    }
                }
                if (review.usage.sessions > 0) {
                    Spacer(Modifier.height(10.dp))
                    Caption("${plural(review.usage.sessions, "сеанс", "сеанса", "сеансов")} просмотра за год.", 5)
                }
            }
            Slide.Marathon -> {
                Kicker("Марафоны")
                Spacer(Modifier.height(8.dp))
                if (review.marathon > 1) {
                    CountUp(review.marathon)
                    Txt("серий за один день" + (review.marathonDay?.let { " — ${it.day} ${monthGenitive[it.month - 1]}" } ?: ""), type.title, modifier = Modifier.appear(2))
                    Spacer(Modifier.height(16.dp))
                    EpisodeTiles(review.marathon, Modifier.appear(3), columns = 8, size = 28.dp)
                    Spacer(Modifier.height(12.dp))
                    Caption("«Ещё одну и спать» — мы помним.", 4)
                }
                if (review.usage.longestSessionSeconds >= 3600) {
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.appear(5), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AniMixIcons.Clock, null, Modifier.size(20.dp), tint = colors.accent)
                        Spacer(Modifier.width(8.dp))
                        Txt("Самый долгий сеанс без перерыва — ${hoursLabel(review.usage.longestSessionSeconds)}", type.bodyStrong)
                    }
                }
            }
            Slide.Awards -> {
                Kicker("Достижения года")
                Spacer(Modifier.height(8.dp))
                CountUp(review.achievements.size)
                Txt(plural(review.achievements.size, "награда", "награды", "наград").substringAfter(' '), type.title, modifier = Modifier.appear(2))
                Spacer(Modifier.height(20.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    review.achievements.take(12).forEachIndexed { i, progress ->
                        Box(Modifier.appear(3 + i)) { AchievementIcon(progress.achievement, true, 64.dp) }
                    }
                }
            }
            Slide.Outro -> {
                Kicker("${review.year}")
                Spacer(Modifier.height(12.dp))
                SummaryCard(review, Modifier.fillMaxWidth().appear(1))
                Spacer(Modifier.height(18.dp))
                Txt(if (review.empty) "Итоги ждут вас" else "Спасибо, что смотрели вместе с AniMix", type.title, modifier = Modifier.appear(2))
                Caption("Увидимся в ${review.year + 1}.", 3)
                Spacer(Modifier.height(22.dp))
                Row(Modifier.appear(4), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Ещё раз", onRestart, Modifier.weight(1f), icon = AniMixIcons.Retry)
                    SecondaryButton("Закрыть", onClose, Modifier.weight(1f))
                }
            }
        }
    }
}

/** The three titles of the year as a fanned stack of posters. */
@Composable
private fun PosterFan(posters: List<String?>) {
    val motion = LocalUiPerformance.current.animations
    val open = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) { delay(150); open.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)) } }
    Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
        val shown = posters.take(3)
        shown.forEachIndexed { i, url ->
            val side = i - (shown.size - 1) / 2f
            RemoteImage(url, Modifier.width(120.dp).aspectRatio(0.7f).graphicsLayer {
                rotationZ = side * 12f * open.value
                translationX = side * 74.dp.toPx() * open.value
                translationY = kotlin.math.abs(side) * 14.dp.toPx() * open.value
                alpha = 0.4f + 0.6f * open.value
            }, shape = AniMixTheme.shapes.large)
        }
    }
}

/** 24 rays around a dial: the longer, the more you watch at that hour. */
@Composable
private fun DayClock(hours: List<Int>, modifier: Modifier) {
    val colors = AniMixTheme.colors
    val grow = rememberGrowth()
    val max = (hours.maxOrNull() ?: 0).coerceAtLeast(1)
    val peak = hours.indexOf(hours.maxOrNull() ?: 0)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val inner = size.minDimension * 0.2f
            val outer = size.minDimension * 0.5f
            drawCircle(colors.outline, inner, center, style = Stroke(1.dp.toPx()))
            hours.forEachIndexed { hour, seconds ->
                val angle = (hour / 24f) * 2 * PI - PI / 2
                val length = inner + (outer - inner) * (seconds.toFloat() / max).coerceAtLeast(0.06f) * grow
                val start = Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner)
                val end = Offset(center.x + cos(angle).toFloat() * length, center.y + sin(angle).toFloat() * length)
                drawLine(if (hour == peak && seconds > 0) colors.accent else colors.accent.copy(alpha = if (seconds > 0) 0.45f else 0.15f),
                    start, end, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Txt("24 ч", AniMixTheme.type.micro)
    }
}

/** The whole year as a calendar grid: one cell per day, brighter for more watching. */
@Composable
private fun YearHeatmap(year: Int, days: Map<Long, Int>, modifier: Modifier) {
    val colors = AniMixTheme.colors
    val grow = rememberGrowth(1_400, 200)
    val first = remember(year) { firstDayOf(year) }
    val last = remember(year) { firstDayOf(year + 1) - 1 }
    val today = remember { localDayIndex() }
    val max = (days.values.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(modifier.aspectRatio(53f / 7f)) {
        val cell = size.width / 53f
        val gap = cell * 0.18f
        val startWeekday = weekdayOf(first)
        val revealed = first + ((last - first) * grow).toLong()
        var day = first
        while (day <= last) {
            val offset = (day - first + startWeekday)
            val column = (offset / 7).toInt()
            val row = (offset % 7).toInt()
            val seconds = days[day] ?: 0
            val color = when {
                day > today || day > revealed -> colors.outline.copy(alpha = 0.35f)
                seconds <= 0 -> colors.outline
                else -> colors.accent.copy(alpha = 0.3f + 0.7f * (seconds.toFloat() / max).coerceIn(0f, 1f))
            }
            drawRoundRect(color, Offset(column * cell + gap / 2, row * cell + gap / 2), Size(cell - gap, cell - gap), CornerRadius(cell * 0.25f))
            day++
        }
    }
}

internal fun firstDayOf(year: Int): Long {
    // Days from civil (Howard Hinnant), for 1 January of [year].
    val y = year - 1
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (1 + 9) + 2) / 5
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468
}

/** Episodes as small tiles, filling in one by one. */
@Composable
private fun EpisodeTiles(count: Int, modifier: Modifier, columns: Int = 12, size: Dp = 18.dp) {
    val colors = AniMixTheme.colors
    val grow = rememberGrowth(1_300, 300)
    val shown = count.coerceAtMost(columns * 5)
    val lit = (shown * grow).toInt()
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = columns) {
        repeat(shown) { i ->
            Box(Modifier.size(size).clip(AniMixTheme.shapes.small).background(if (i < lit) colors.accent else colors.outline))
        }
    }
}

/** Genres as bubbles sized by how much they were watched. */
@Composable
private fun GenreBubbles(genres: List<Pair<String, Int>>, modifier: Modifier) {
    val colors = AniMixTheme.colors
    val max = genres.first().second.coerceAtLeast(1)
    BoxWithConstraints(modifier) {
        val width = maxWidth
        // A fixed, pleasant arrangement for up to five bubbles; sizes follow the weights.
        val spots = listOf(0.28f to 0.40f, 0.76f to 0.24f, 0.74f to 0.80f, 0.16f to 0.92f, 0.50f to 0.95f)
        genres.take(5).forEachIndexed { i, (genre, weight) ->
            val diameter = (width * (0.22f + 0.24f * sqrt(weight.toFloat() / max))).coerceAtMost(maxHeight)
            val (x, y) = spots[i]
            Box(
                // Kept inside the area, so no bubble is cut by the screen edge.
                Modifier.offset(x = (width * x - diameter / 2).coerceIn(0.dp, (width - diameter).coerceAtLeast(0.dp)), y = (maxHeight * y - diameter / 2).coerceIn(0.dp, (maxHeight - diameter).coerceAtLeast(0.dp)))
                    .size(diameter).appear(2 + i)
                    .clip(CircleShape).background(if (i == 0) colors.accentGradient else Brush.linearGradient(
                        listOf(colors.accent.copy(alpha = 0.32f - i * 0.04f), colors.accent.copy(alpha = 0.18f)))),
                contentAlignment = Alignment.Center,
            ) {
                Txt(genre, if (i == 0) AniMixTheme.type.bodyStrong else AniMixTheme.type.label,
                    color = if (i == 0) colors.onAccent else colors.textPrimary, maxLines = 2,
                    align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun WeekBars(seconds: List<Int>, peak: Int, modifier: Modifier) {
    val colors = AniMixTheme.colors
    val grow = rememberGrowth()
    val max = (seconds.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            seconds.forEachIndexed { i, value ->
                Box(Modifier.weight(1f).fillMaxHeight((value.toFloat() / max * grow).coerceAtLeast(0.04f)).clip(AniMixTheme.shapes.small)
                    .background(if (i == peak) colors.accentGradient else Brush.verticalGradient(listOf(colors.accent.copy(alpha = 0.4f), colors.accent.copy(alpha = 0.2f)))))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            weekdayShort.forEachIndexed { i, label ->
                Txt(label, AniMixTheme.type.micro, color = if (i == peak) colors.accent else colors.textSecondary, modifier = Modifier.weight(1f),
                    align = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Online versus downloads, as one ring. */
@Composable
private fun ShareDonut(offline: Float, modifier: Modifier) {
    val colors = AniMixTheme.colors
    val grow = rememberGrowth()
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = 16.dp.toPx()
            val box = Size(size.width - stroke, size.height - stroke)
            val origin = Offset(stroke / 2, stroke / 2)
            drawArc(colors.outline, 0f, 360f, false, origin, box, style = Stroke(stroke))
            drawArc(colors.accent, -90f, 360f * (1 - offline) * grow, false, origin, box, style = Stroke(stroke, cap = StrokeCap.Round))
            if (offline > 0f) drawArc(colors.star, -90f + 360f * (1 - offline), 360f * offline * grow, false, origin, box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Icon(AniMixIcons.Wifi, null, Modifier.size(26.dp), tint = colors.textSecondary)
    }
}

/** The year on one card: the top poster and the main numbers. */
@Composable
private fun SummaryCard(review: YearInReview, modifier: Modifier) {
    val colors = AniMixTheme.colors
    Row(modifier.clip(AniMixTheme.shapes.large).background(colors.surfaceRaised.copy(alpha = 0.85f)).rim(AniMixTheme.shapes.large, accent = true).padding(16.dp)) {
        review.topTitles.firstOrNull()?.let {
            RemoteImage(it.anime.posterUrl, Modifier.width(96.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.medium)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("${review.minutes / 60} ч", "в AniMix")
            Stat("${review.episodes}", plural(review.episodes, "серия", "серии", "серий").substringAfter(' '))
            Stat("${review.activeDays}", plural(review.activeDays, "день", "дня", "дней").substringAfter(' ') + " с аниме")
            review.persona?.let { Txt(it, AniMixTheme.type.label, color = colors.accentText) }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Txt(value, AniMixTheme.type.title)
        Spacer(Modifier.width(6.dp))
        Txt(label, AniMixTheme.type.caption, modifier = Modifier.padding(bottom = 3.dp))
    }
}

/** Before the end of December: when the review opens and how to open it early. */
@Composable
private fun Locked(titles: Int, needed: Int, opensOn: LocalDay, onClose: () -> Unit) {
    val colors = AniMixTheme.colors
    Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().padding(20.dp)) {
        IconCircle(AniMixIcons.Close, onClose, Modifier.align(Alignment.TopEnd), contentDescription = "Закрыть")
        Column(Modifier.align(Alignment.Center).widthIn(max = 480.dp)) {
            Kicker("Итоги ${opensOn.year}")
            Spacer(Modifier.height(10.dp))
            Txt("Откроются ${opensOn.day} ${monthGenitive[opensOn.month - 1]}", AniMixTheme.type.display)
            Spacer(Modifier.height(14.dp))
            Caption("Или раньше: посмотрите в AniMix $needed разных тайтла — онлайн или из загрузок.", 1)
            Spacer(Modifier.height(18.dp))
            ProgressLine(titles.toFloat() / needed)
            Txt("$titles из $needed", AniMixTheme.type.label, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
