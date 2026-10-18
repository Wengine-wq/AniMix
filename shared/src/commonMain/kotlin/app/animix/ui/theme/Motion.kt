package app.animix.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.*
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize

@Immutable
data class UiPerformance(val animations: Boolean = true, val batterySaver: Boolean = false)

val LocalUiPerformance = staticCompositionLocalOf { UiPerformance() }

@Composable
fun animateMotionFloatAsState(targetValue: Float, animationSpec: AnimationSpec<Float> = spring()): State<Float> =
    if (LocalUiPerformance.current.animations) animateFloatAsState(targetValue, animationSpec) else rememberUpdatedState(targetValue)

@Composable
fun animateMotionColorAsState(targetValue: Color, animationSpec: AnimationSpec<Color> = spring()): State<Color> =
    if (LocalUiPerformance.current.animations) animateColorAsState(targetValue, animationSpec) else rememberUpdatedState(targetValue)

@Composable
fun MotionVisibility(visible: Boolean, modifier: Modifier = Modifier, enter: EnterTransition = fadeIn() + expandVertically(),
    exit: ExitTransition = fadeOut() + shrinkVertically(), content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val motion = LocalUiPerformance.current.animations
    AnimatedVisibility(visible, modifier, if (motion) enter else EnterTransition.None, if (motion) exit else ExitTransition.None, content = content)
}

@Composable
fun Modifier.motionContentSize(animationSpec: FiniteAnimationSpec<IntSize>): Modifier =
    if (LocalUiPerformance.current.animations) animateContentSize(animationSpec) else this

/** Quiet, consistent motion for navigation, selection and expanding content. */
object AniMixMotion {
    const val Fast = 180
    const val Standard = 280
    const val Page = 360

    @Composable
    fun <T> selectionSpring(): FiniteAnimationSpec<T> = if (!LocalUiPerformance.current.animations) snap() else spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
