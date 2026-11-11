package app.animix.ui.screens.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.LocalUiPerformance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Rewards of the profile level: a design of the page background from level [DesignLevel], and from
 * [FrameLevel] an avatar frame in the same style. Both are drawn only with the app's own colours
 * (accent, its gradient and text), so a decorated profile stays part of one interface in every theme.
 *
 * Since 1.2.2 re the designs are drawn for where the page actually shows them: the band of the header
 * above the banner, the margins beside it and the name and «О себе» under it. A profile without its
 * own banner photo carries the design on the banner too. On one's own and a friend's page they move
 * slowly (petals fall, stars twinkle, waves and the neon floor run); battery saving and turned-off
 * animations keep them still, previews are always still.
 */
enum class ProfileDesign(val id: String, val label: String, val description: String) {
    Waves("waves", "Волны", "Линии горизонта, бегущие волнами"),
    Sakura("sakura", "Сакура", "Цветы и падающие лепестки"),
    Stars("stars", "Звёзды", "Мерцающее небо с созвездием"),
    Neon("neon", "Неон", "Ретро-солнце и светящаяся сетка"),
}

const val DesignLevel = 10
const val FrameLevel = 20

/** How tall the design behind the top of a profile page is: the header, the banner, the name and «О себе». */
val ProfileDesignHeight = 460.dp

fun profileDesign(id: String?): ProfileDesign? = ProfileDesign.entries.firstOrNull { it.id == id }

/** The palette of a design: the accent, the two ends of its gradient and the text colour. */
private class DesignInk(val accent: Color, val start: Color, val end: Color, val ink: Color)

@Composable
private fun designInk(): DesignInk {
    val colors = AniMixTheme.colors
    return DesignInk(colors.accent, colors.gradientStart, colors.gradientEnd, colors.textPrimary)
}

/**
 * Seconds since the design appeared, for slow motion; about 30 frames a second is plenty for drifting
 * petals and keeps the profile cheap. Stays at 0 for previews, battery saving and turned-off animations.
 */
@Composable
private fun rememberDesignClock(animated: Boolean): State<Float> {
    val performance = LocalUiPerformance.current
    val running = animated && performance.animations && !performance.batterySaver
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) { time.floatValue = 0f; return@LaunchedEffect }
        val start = withFrameNanos { it }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (now - last >= 33_000_000L) {
                    last = now
                    time.floatValue = ((now - start) / 1_000_000_000.0 % 3600.0).toFloat()
                }
            }
        }
    }
    return time
}

/**
 * The design behind the top of a profile page. With [fade] it melts into the page under the name;
 * without it (the default banner, previews) it fills its box evenly.
 */
@Composable
fun ProfileDesignLayer(design: ProfileDesign?, modifier: Modifier, animated: Boolean = false, fade: Boolean = true,
    /** Drawn over one's own banner photo: lighter at the top, where faces usually are, full by the lower edge. */
    overPhoto: Boolean = false) {
    if (design == null) return
    val ink = designInk()
    val clock = rememberDesignClock(animated)
    Canvas(modifier) {
        val t = clock.value
        val mask: (Float) -> Float = when {
            overPhoto -> { y -> 0.75f + 0.25f * (y / size.height).coerceIn(0f, 1f) }
            fade -> { y -> fadeMask(y) }
            else -> { _ -> 1f }
        }
        when (design) {
            ProfileDesign.Waves -> waves(ink, t, mask)
            ProfileDesign.Sakura -> sakura(ink, t, mask)
            ProfileDesign.Stars -> stars(ink, t, mask, animated = clock.value > 0f)
            ProfileDesign.Neon -> neon(ink, t, mask)
        }
    }
}

/** Full strength over the header, the banner and the name; it melts away over the last third. */
private fun DrawScope.fadeMask(y: Float): Float {
    val from = size.height * 0.62f
    return if (y <= from) 1f else (1f - (y - from) / (size.height - from)).coerceIn(0f, 1f)
}

/** A soft round light in the accent that gives each design its mood without a panel. */
private fun DrawScope.aura(color: Color, center: Offset, radius: Float, alpha: Float) {
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, radius), radius, center)
}

// ── Волны ───────────────────────────────────────────────────────────────────────────────────────

private fun DrawScope.wavePath(base: Float, amplitude: Float, cycles: Float, phase: Float): Path = Path().apply {
    moveTo(0f, base + amplitude * sin(phase))
    var x = 0f
    val step = 4.dp.toPx()
    while (x < size.width) {
        x = (x + step).coerceAtMost(size.width)
        lineTo(x, base + amplitude * sin(x / size.width * cycles * 2 * PI.toFloat() + phase))
    }
}

private fun DrawScope.waves(ink: DesignInk, t: Float, mask: (Float) -> Float) {
    aura(ink.end, Offset(size.width * 0.15f, size.height * 0.05f), size.width * 0.55f, 0.12f * mask(0f))
    val line = Brush.horizontalGradient(listOf(ink.start, ink.end, ink.start))
    for (i in 0 until 7) {
        val base = size.height * (0.06f + i * 0.135f)
        val visible = mask(base)
        if (visible <= 0f) continue
        val amplitude = size.height * 0.026f + (i * 0.8f).dp.toPx()
        val phase = i * 0.9f + t * (0.35f + i * 0.06f) * if (i % 2 == 0) 1f else -1f
        val crest = wavePath(base, amplitude, 1.4f + i * 0.22f, phase)
        // Every other line carries a faint ribbon of water under it: layered dunes rather than lone strokes.
        if (i % 2 == 0) {
            val body = Path().apply { addPath(crest); lineTo(size.width, base + size.height * 0.11f); lineTo(0f, base + size.height * 0.11f); close() }
            drawPath(body, Brush.verticalGradient(listOf(ink.accent.copy(alpha = 0.10f * visible), Color.Transparent), base - amplitude, base + size.height * 0.11f))
        }
        drawPath(crest, line, alpha = (0.62f - i * 0.04f) * visible, style = Stroke((if (i % 3 == 0) 2f else 1.25f).dp.toPx(), cap = StrokeCap.Round))
    }
}

// ── Сакура ─────────────────────────────────────────────────────────────────────────────────────

/** One sakura petal one unit long, its base at the origin and its notched tip up. */
private val PetalShape: Path = Path().apply {
    moveTo(0f, 0f)
    cubicTo(0.44f, -0.12f, 0.42f, -0.78f, 0.13f, -1f)
    lineTo(0f, -0.86f)
    lineTo(-0.13f, -1f)
    cubicTo(-0.42f, -0.78f, -0.44f, -0.12f, 0f, 0f)
    close()
}

private fun DrawScope.petal(ink: DesignInk, at: Offset, length: Float, degrees: Float, alpha: Float, centred: Boolean) {
    if (alpha <= 0f) return
    withTransform({
        translate(at.x, at.y)
        rotate(degrees, Offset.Zero)
        scale(length, length, Offset.Zero)
        if (centred) translate(0f, 0.5f)
    }) {
        drawPath(PetalShape, Brush.verticalGradient(listOf(ink.end, ink.start), -1f, 0f), alpha = alpha)
    }
}

/** A five-petal blossom with a light heart and tiny stamens. */
private fun DrawScope.blossom(ink: DesignInk, center: Offset, radius: Float, degrees: Float, alpha: Float) {
    if (alpha <= 0f) return
    for (k in 0 until 5) petal(ink, center, radius, degrees + k * 72f, alpha, centred = false)
    drawCircle(ink.end.copy(alpha = 0.95f * alpha), radius * 0.2f, center)
    for (k in 0 until 5) {
        val angle = ((degrees + 36f + k * 72f) * PI / 180f).toFloat()
        drawCircle(ink.ink.copy(alpha = 0.35f * alpha), radius * 0.05f + 0.5f, Offset(center.x + radius * 0.34f * cos(angle), center.y + radius * 0.34f * sin(angle)))
    }
}

private fun DrawScope.sakura(ink: DesignInk, t: Float, mask: (Float) -> Float) {
    aura(ink.accent, Offset(size.width * 0.88f, size.height * 0.08f), size.width * 0.5f, 0.14f * mask(0f))
    val sway = sin(t * 0.5f) * 6f
    blossom(ink, Offset(size.width * 0.9f, size.height * 0.07f), 18.dp.toPx(), 12f + sway, 0.62f * mask(size.height * 0.07f))
    blossom(ink, Offset(size.width * 0.05f, size.height * 0.6f), 13.dp.toPx(), -20f - sway, 0.5f * mask(size.height * 0.6f))
    blossom(ink, Offset(size.width * 0.8f, size.height * 0.72f), 9.dp.toPx(), 40f + sway, 0.42f * mask(size.height * 0.72f))
    val random = Random(7)
    val travel = size.height + 24.dp.toPx()
    repeat(26) {
        val x0 = random.nextFloat() * size.width
        val y0 = random.nextFloat() * travel
        val length = (6 + random.nextFloat() * 8).dp.toPx()
        val fall = (8 + random.nextFloat() * 14).dp.toPx()
        val drift = (6 + random.nextFloat() * 12).dp.toPx()
        val spin = (random.nextFloat() - 0.5f) * 50f
        val tilt = random.nextFloat() * 360f
        val phase = random.nextFloat() * 6.28f
        val strength = 0.3f + random.nextFloat() * 0.32f
        val y = (y0 + t * fall) % travel - 12.dp.toPx()
        val x = x0 + sin(t * 0.6f + phase) * drift
        petal(ink, Offset(x, y), length, tilt + t * spin + sin(t + phase) * 18f, strength * mask(y), centred = true)
    }
}

// ── Звёзды ─────────────────────────────────────────────────────────────────────────────────────

private fun DrawScope.sparkle(center: Offset, radius: Float, brush: Brush, alpha: Float) {
    if (alpha <= 0f) return
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x, center.y, center.x + radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + radius)
        quadraticTo(center.x, center.y, center.x - radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - radius)
        close()
    }
    drawPath(path, brush, alpha = alpha)
}

/** A small constellation between the page title and the header buttons: five stars and the lines between them. */
private val Constellation = listOf(0.36f to 0.10f, 0.41f to 0.04f, 0.47f to 0.08f, 0.52f to 0.03f, 0.56f to 0.12f)

private fun DrawScope.stars(ink: DesignInk, t: Float, mask: (Float) -> Float, animated: Boolean) {
    aura(ink.start, Offset(size.width * 0.5f, 0f), size.width * 0.7f, 0.10f * mask(0f))
    val random = Random(11)
    repeat(84) {
        val point = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height * 0.95f)
        val base = 0.2f + random.nextFloat() * 0.45f
        val speed = 0.6f + random.nextFloat() * 1.6f
        val phase = random.nextFloat() * 6.28f
        val twinkle = 0.55f + 0.45f * sin(t * speed + phase)
        drawCircle(ink.ink.copy(alpha = (base * twinkle * mask(point.y)).coerceIn(0f, 1f)), (0.6f + random.nextFloat() * 1.2f).dp.toPx(), point)
    }
    val glow = Brush.linearGradient(listOf(ink.start, ink.end))
    val nodes = Constellation.map { (x, y) -> Offset(size.width * x, size.height * y) }
    nodes.zipWithNext().forEach { (a, b) -> drawLine(ink.ink.copy(alpha = 0.2f * mask(a.y)), a, b, 0.8.dp.toPx()) }
    nodes.forEachIndexed { i, node -> sparkle(node, (3.5f + (i % 2) * 1.5f).dp.toPx(), glow, 0.85f * mask(node.y)) }
    repeat(8) {
        val point = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height * 0.75f)
        val pulse = 0.75f + 0.25f * sin(t * (0.8f + it * 0.15f) + it)
        sparkle(point, (5 + random.nextFloat() * 6).dp.toPx() * pulse, glow, 0.6f * mask(point.y))
    }
    // Now and then a falling star crosses the top: one second in every nine, only while the design moves.
    if (animated) {
        val cycle = t / 9f
        val progress = (t - floor(cycle) * 9f) / 1.1f
        if (progress in 0f..1f) {
            val lane = Random(floor(cycle).toInt()).nextFloat()
            val start = Offset(size.width * (0.15f + lane * 0.6f), size.height * 0.02f)
            val head = Offset(start.x + size.width * 0.28f * progress, start.y + size.height * 0.1f * progress)
            val tail = Offset(head.x - size.width * 0.09f, head.y - size.height * 0.032f)
            val alpha = sin(progress * PI.toFloat())
            drawLine(Brush.linearGradient(listOf(Color.Transparent, ink.end.copy(alpha = 0.9f * alpha)), tail, head), tail, head, 1.6.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(ink.ink.copy(alpha = 0.9f * alpha), 1.4.dp.toPx(), head)
        }
    }
}

// ── Неон ───────────────────────────────────────────────────────────────────────────────────────

private fun DrawScope.neon(ink: DesignInk, t: Float, mask: (Float) -> Float) {
    // The sun rises over the top of the banner; the floor runs out from under it.
    val horizon = size.height * 0.58f
    val sunCenter = Offset(size.width * 0.5f, size.height * 0.2f)
    val sunRadius = size.minDimension * 0.24f
    aura(ink.accent, sunCenter, sunRadius * 2.2f, 0.16f * mask(sunCenter.y))
    val sun = Brush.verticalGradient(listOf(ink.end, ink.accent, ink.start), sunCenter.y - sunRadius, sunCenter.y + sunRadius)
    // Retro slits: bands of the sun, the gaps widening towards its lower edge.
    var top = sunCenter.y - sunRadius
    var band = sunRadius * 0.42f
    var gap = 1.5.dp.toPx()
    while (top < sunCenter.y + sunRadius) {
        clipRect(0f, top, size.width, top + band) { drawCircle(sun, sunRadius, sunCenter, alpha = 0.42f * mask(sunCenter.y)) }
        top += band + gap
        band *= 0.72f
        gap *= 1.35f
    }
    // A glowing horizon line, then the perspective floor moving towards the viewer.
    drawLine(ink.accent.copy(alpha = 0.18f * mask(horizon)), Offset(0f, horizon), Offset(size.width, horizon), 6.dp.toPx())
    drawLine(ink.end.copy(alpha = 0.75f * mask(horizon)), Offset(0f, horizon), Offset(size.width, horizon), 1.5.dp.toPx())
    val vanishing = Offset(size.width / 2, horizon - size.height * 0.2f)
    val stroke = 1.dp.toPx()
    clipRect(0f, horizon, size.width, size.height) {
        for (i in -12..12) {
            val bottom = Offset(size.width / 2 + i * size.width / 7f, size.height)
            drawLine(Brush.verticalGradient(listOf(ink.accent.copy(alpha = 0.5f), Color.Transparent), horizon, size.height), vanishing, bottom, stroke)
        }
        val lines = 9
        val shift = (t * 0.35f) % 1f
        for (k in 0 until lines) {
            val s = (k + shift) / lines
            val y = horizon + (size.height - horizon) * s * s
            drawLine(ink.accent.copy(alpha = 0.5f * mask(y) * kotlin.math.sqrt(s)), Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

// ── Рамки ──────────────────────────────────────────────────────────────────────────────────────

/**
 * The level-20 frame around an avatar, in the style of the chosen design, just outside the level ring.
 * On a profile page it turns or sways slowly; previews are still.
 */
@Composable
fun ProfileFrameLayer(design: ProfileDesign?, modifier: Modifier, animated: Boolean = false) {
    if (design == null) return
    val ink = designInk()
    val clock = rememberDesignClock(animated)
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val t = clock.value
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2 + 4.dp.toPx()
            val sweep = Brush.sweepGradient(listOf(ink.start, ink.end, ink.start), center)
            when (design) {
                ProfileDesign.Waves -> {
                    fun ring(lobes: Int, depth: Float, phase: Float): Path = Path().apply {
                        for (step in 0..240) {
                            val angle = step / 240f * 2 * PI.toFloat()
                            val r = radius + depth * sin(angle * lobes + phase)
                            val point = Offset(center.x + r * cos(angle), center.y + r * sin(angle))
                            if (step == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                        }
                        close()
                    }
                    drawPath(ring(9, 1.6.dp.toPx(), 1f - t * 0.6f), sweep, alpha = 0.5f, style = Stroke(1.dp.toPx()))
                    drawPath(ring(12, 2.2.dp.toPx(), t * 0.8f), sweep, alpha = 0.95f, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
                }
                ProfileDesign.Sakura -> {
                    // A small wreath on the lower left of the avatar and one blossom opposite it.
                    val sway = sin(t * 0.7f) * 5f
                    for (i in 0 until 6) {
                        val degrees = 100f + i * 19f
                        val angle = degrees * PI.toFloat() / 180f
                        val at = Offset(center.x + (radius + 1.dp.toPx()) * cos(angle), center.y + (radius + 1.dp.toPx()) * sin(angle))
                        petal(ink, at, (7 + (i % 2) * 2).dp.toPx(), degrees + 90f + (if (i % 2 == 0) 35f else -35f) + sway, 0.9f, centred = true)
                    }
                    listOf(125f to 7f, 190f to 6f, -40f to 5.5f).forEach { (degrees, bloom) ->
                        val angle = degrees * PI.toFloat() / 180f
                        val at = Offset(center.x + (radius + 2.dp.toPx()) * cos(angle), center.y + (radius + 2.dp.toPx()) * sin(angle))
                        blossom(ink, at, bloom.dp.toPx(), degrees + sway * 2, 1f)
                    }
                }
                ProfileDesign.Stars -> {
                    for (i in 0 until 44) {
                        val angle = i / 44f * 2 * PI.toFloat()
                        val r = radius + 2.dp.toPx()
                        drawCircle(ink.ink.copy(alpha = 0.25f + 0.3f * ((i % 3) / 2f)), 0.9.dp.toPx(), Offset(center.x + r * cos(angle), center.y + r * sin(angle)))
                    }
                    val glow = Brush.linearGradient(listOf(ink.start, ink.end))
                    val turn = t * 8f
                    listOf(-60f to 7f, 150f to 5f, 50f to 3.5f).forEachIndexed { i, (degrees, spark) ->
                        val angle = (degrees + turn) * PI.toFloat() / 180f
                        val r = radius + 3.dp.toPx()
                        val pulse = 0.85f + 0.15f * sin(t * 1.4f + i * 2f)
                        sparkle(Offset(center.x + r * cos(angle), center.y + r * sin(angle)), spark.dp.toPx() * pulse, glow, 1f)
                    }
                }
                ProfileDesign.Neon -> {
                    drawCircle(ink.accent.copy(alpha = 0.10f), radius, center, style = Stroke(8.dp.toPx()))
                    drawCircle(ink.accent.copy(alpha = 0.22f), radius, center, style = Stroke(4.dp.toPx()))
                    rotate(t * 24f, center) { drawCircle(sweep, radius, center, style = Stroke(1.8.dp.toPx())) }
                    rotate(-t * 16f, center) {
                        val box = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
                        for (i in 0 until 4) drawArc(ink.end, i * 90f + 10f, 28f, false, box.topLeft, Size(box.width, box.height),
                            style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
            }
        }
    }
}
