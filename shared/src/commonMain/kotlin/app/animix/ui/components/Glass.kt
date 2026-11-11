package app.animix.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import app.animix.ui.theme.animateMotionFloatAsState as animateFloatAsState
import app.animix.ui.theme.LocalUiPerformance
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.animix.ui.theme.AniMixTheme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** The backdrop that glass surfaces blur. Screens draw into it; bars and overlays read from it. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * AniMix glass, the one material of everything that floats: frosted (whatever lies behind is blurred and
 * tinted with the theme surface) and liquid (a specular edge, bright where the light falls from the top
 * left and catching it again at the lower right, over a soft sheen in the upper half). [source] is the
 * layer to blur: the app's content for bars over it, or a page's own layer for controls lying on that
 * page. With [backdrop] off, or with nothing to blur, the glass is a translucent fill with the same edge.
 * Battery saving makes it a solid surface with the plain rim.
 */
@Composable
fun Modifier.glass(shape: Shape, tint: Float = 0.62f, radius: Dp = 28.dp, backdrop: Boolean = true, source: HazeState? = null): Modifier {
    val colors = AniMixTheme.colors
    if (LocalUiPerformance.current.batterySaver) return clip(shape).background(colors.surface).rim(shape, accent = false)
    // Controls inside the scrolling content can't blur the layer they belong to; they use a translucent fill.
    val haze = (source ?: LocalHaze.current).takeIf { backdrop }
    val base = softShadow(shape).clip(shape)
    val surface = if (haze != null) {
        base.hazeBlur(
            input = HazeInput.Sources(haze),
            style = HazeBlurStyle {
                blurRadius(radius)
                backgroundColor(colors.background)
                noiseFactor(0.04f)
                colorEffects(listOf(HazeColorEffect.tint(colors.surface.copy(alpha = tint))))
            },
        )
    } else {
        base.background(colors.surfaceGlass)
    }
    return surface.liquidSheen().rim(shape, accent = false)
}

/**
 * Frosted fill without an edge, for full-width bars: [source] blurred and tinted with the theme surface.
 * Battery saving and a missing source give the plain page colour.
 */
@Composable
fun Modifier.frosted(source: HazeState?, tint: Float = 0.7f, radius: Dp = 24.dp): Modifier {
    val colors = AniMixTheme.colors
    if (LocalUiPerformance.current.batterySaver || source == null) return background(colors.background.copy(alpha = 0.94f))
    return hazeBlur(
        input = HazeInput.Sources(source),
        style = HazeBlurStyle {
            blurRadius(radius)
            backgroundColor(colors.background)
            noiseFactor(0.04f)
            colorEffects(listOf(HazeColorEffect.tint(colors.background.copy(alpha = tint))))
        },
    )
}

/** A page's own layer for glass that lies on the page (its bar, a floating button): blur it without reading itself. */
@Composable
fun rememberPageHaze(): HazeState? = if (LocalUiPerformance.current.batterySaver) null else dev.chrisbanes.haze.rememberHazeState()

/** Marks [state] as the page layer that [frosted] and page-level glass blur. */
fun Modifier.pageHazeSource(state: HazeState?): Modifier = if (state == null) this else hazeSource(state)


/**
 * The sheen of glass: light gathered in the upper part of the surface, gone by the middle. Drawn over the
 * fill and under the content; never on solid surfaces in battery saving.
 */
@Composable
fun Modifier.liquidSheen(strength: Float = 1f): Modifier {
    if (!LocalUiPerformance.current.liquid) return this
    val light = Color.White.copy(alpha = (if (AniMixTheme.colors.isDark) 0.06f else 0.22f) * strength)
    return drawBehind {
        drawRect(Brush.verticalGradient(0f to light, 0.55f to Color.Transparent, startY = 0f, endY = size.height))
    }
}

/** A gentle shrink while pressed, so taps feel physical without shouting. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.97f): Modifier {
    if (!LocalUiPerformance.current.animations) return this
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The soft, blurred mood behind a screen: the current title's art, heavily blurred and faded
 * into the background, so screens feel airy instead of flat black or white.
 */
@Composable
fun AmbientBackdrop(imageUrl: String?, modifier: Modifier = Modifier, height: Dp = 520.dp) {
    val colors = AniMixTheme.colors
    if (LocalUiPerformance.current.batterySaver) return
    val intensity by animateFloatAsState(if (imageUrl != null) 1f else 0f, tween(600))
    Box(modifier.fillMaxWidth().height(height)) {
        // Without an image, a soft accent dawn at the top keeps the screen from feeling flat.
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(colors.accent.copy(alpha = if (colors.isDark) 0.10f else 0.14f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(0f, 0f),
                    radius = 1400f,
                ),
            ),
        )
        Crossfade(targetState = imageUrl, animationSpec = tween(if (LocalUiPerformance.current.animations) 900 else 0), modifier = Modifier.fillMaxSize()) { url ->
            if (url != null) {
                RemoteImage(
                    url,
                    Modifier.fillMaxSize().blur(90.dp).alpha(if (colors.isDark) 0.32f * intensity else 0.4f * intensity),
                    shape = RectangleShape,
                    placeholder = false,
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to colors.background.copy(alpha = if (colors.isDark) 0.35f else 0.2f),
                    0.6f to colors.background.copy(alpha = 0.82f),
                    1f to colors.background,
                ),
            ),
        )
    }
}

/** Fades content into the background at the edges of a scrolling area. */
fun edgeFade(color: Color, toTop: Boolean): Brush =
    if (toTop) Brush.verticalGradient(listOf(color, Color.Transparent))
    else Brush.verticalGradient(listOf(Color.Transparent, color))
