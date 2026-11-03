package app.animix.ui.screens.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import app.animix.ui.theme.AniMixTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Rewards of the profile level: a design of the page background from level [DesignLevel], and from
 * [FrameLevel] an avatar frame in the same style. Both are drawn only with the app's own colours
 * (accent and text), so a decorated profile stays part of one interface in both themes.
 */
enum class ProfileDesign(val id: String, val label: String, val description: String) {
    Waves("waves", "Волны", "Мягкие линии горизонта"),
    Sakura("sakura", "Сакура", "Лепестки в акцентном цвете"),
    Stars("stars", "Звёзды", "Ночное небо с искрами"),
    Neon("neon", "Неон", "Светящаяся сетка до горизонта"),
}

const val DesignLevel = 10
const val FrameLevel = 20

fun profileDesign(id: String?): ProfileDesign? = ProfileDesign.entries.firstOrNull { it.id == id }

/** The design behind the top of a profile page; it fades into the page towards the plates. */
@Composable
fun ProfileDesignLayer(design: ProfileDesign?, modifier: Modifier) {
    if (design == null) return
    val accent = AniMixTheme.colors.accent
    val ink = AniMixTheme.colors.textPrimary
    Canvas(modifier) {
        when (design) {
            ProfileDesign.Waves -> waves(accent)
            ProfileDesign.Sakura -> petals(accent)
            ProfileDesign.Stars -> stars(accent, ink)
            ProfileDesign.Neon -> neon(accent)
        }
    }
}

/** How much a mark at [y] shows: full at the top, gone at the bottom of the layer. */
private fun DrawScope.fade(y: Float): Float = (1f - y / size.height).coerceIn(0f, 1f)

private fun DrawScope.waves(accent: Color) {
    val stroke = 1.5.dp.toPx()
    for (i in 0 until 5) {
        val base = size.height * (0.12f + i * 0.13f)
        val amplitude = size.height * (0.035f + i * 0.006f)
        val path = Path().apply {
            moveTo(0f, base)
            var x = 0f
            while (x <= size.width) {
                lineTo(x, base + amplitude * sin((x / size.width * 2.2f + i * 0.35f) * 2 * PI).toFloat())
                x += 6f
            }
        }
        drawPath(path, accent.copy(alpha = 0.4f * fade(base)), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

private fun DrawScope.petals(accent: Color) {
    val random = Random(7)
    repeat(26) {
        val center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height * 0.85f)
        val length = (7 + random.nextFloat() * 9).dp.toPx()
        rotate(random.nextFloat() * 360f, center) {
            drawOval(accent.copy(alpha = (0.3f + random.nextFloat() * 0.28f) * fade(center.y)),
                Offset(center.x - length / 2, center.y - length / 4), Size(length, length / 2))
        }
    }
}

private fun DrawScope.sparkle(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x, center.y, center.x + radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + radius)
        quadraticTo(center.x, center.y, center.x - radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - radius)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.stars(accent: Color, ink: Color) {
    val random = Random(11)
    repeat(70) {
        val point = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height * 0.9f)
        drawCircle(ink.copy(alpha = (0.2f + random.nextFloat() * 0.4f) * fade(point.y)), (0.6f + random.nextFloat() * 1.3f).dp.toPx(), point)
    }
    repeat(6) {
        val point = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height * 0.6f)
        sparkle(point, (5 + random.nextFloat() * 5).dp.toPx(), accent.copy(alpha = 0.55f * fade(point.y)))
    }
}

private fun DrawScope.neon(accent: Color) {
    val horizon = size.height * 0.42f
    val stroke = 1.dp.toPx()
    val vanishing = Offset(size.width / 2, horizon)
    drawCircle(accent.copy(alpha = 0.35f), size.minDimension * 0.18f, Offset(size.width / 2, horizon), style = Stroke(1.5.dp.toPx()))
    drawLine(accent.copy(alpha = 0.4f), Offset(0f, horizon), Offset(size.width, horizon), 1.5.dp.toPx())
    for (i in -10..10) {
        val bottom = Offset(size.width / 2 + i * size.width / 9f, size.height)
        drawLine(Brush.verticalGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent), horizon, size.height), vanishing, bottom, stroke)
    }
    var y = horizon
    var step = 6.dp.toPx()
    while (y < size.height) {
        y += step
        step *= 1.35f
        drawLine(accent.copy(alpha = 0.45f * fade(y)), Offset(0f, y), Offset(size.width, y), stroke)
    }
}

/** The level-20 frame around an avatar, in the style of the chosen design. Drawn just outside the level ring. */
@Composable
fun ProfileFrameLayer(design: ProfileDesign?, modifier: Modifier) {
    if (design == null) return
    val accent = AniMixTheme.colors.accent
    val ink = AniMixTheme.colors.textPrimary
    Box(modifier) {
        Canvas(androidx.compose.ui.Modifier.matchParentSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2 + 4.dp.toPx()
            when (design) {
                ProfileDesign.Waves -> {
                    val path = Path()
                    for (step in 0..180) {
                        val angle = step / 180f * 2 * PI
                        val r = radius + 2.dp.toPx() * sin(angle * 12).toFloat()
                        val point = Offset(center.x + r * cos(angle).toFloat(), center.y + r * sin(angle).toFloat())
                        if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    }
                    drawPath(path, accent.copy(alpha = 0.85f), style = Stroke(1.5.dp.toPx()))
                }
                ProfileDesign.Sakura -> for (i in 0 until 5) {
                    val angle = (-150f + i * 26f) * PI.toFloat() / 180f
                    val point = Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
                    val length = 9.dp.toPx()
                    rotate(i * 37f, point) {
                        drawOval(accent.copy(alpha = 0.9f - i * 0.1f), Offset(point.x - length / 2, point.y - length / 4), Size(length, length / 2))
                    }
                }
                ProfileDesign.Stars -> {
                    for (i in 0 until 36) {
                        val angle = i / 36f * 2 * PI.toFloat()
                        drawCircle(ink.copy(alpha = 0.45f), 1.dp.toPx(), Offset(center.x + radius * cos(angle), center.y + radius * sin(angle)))
                    }
                    listOf(-60f to 7f, 200f to 5f, 35f to 4f).forEach { (degrees, spark) ->
                        val angle = degrees * PI.toFloat() / 180f
                        sparkle(Offset(center.x + (radius + 3.dp.toPx()) * cos(angle), center.y + (radius + 3.dp.toPx()) * sin(angle)), spark.dp.toPx(), accent)
                    }
                }
                ProfileDesign.Neon -> {
                    drawCircle(accent.copy(alpha = 0.22f), radius, center, style = Stroke(5.dp.toPx()))
                    for (i in 0 until 8) drawArc(accent, i * 45f + 6f, 33f, false, Offset(center.x - radius, center.y - radius),
                        Size(radius * 2, radius * 2), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
    }
}
