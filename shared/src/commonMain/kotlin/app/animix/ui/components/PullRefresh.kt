package app.animix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.LocalUiPerformance

/**
 * Pull down to load again. The indicator is a small drop of the app's glass that comes down with the
 * finger: the arrow turns as the pull grows, the drop stretches a little when pulled past the point of
 * refresh (with liquid glass on) and holds a spinner while the page loads. [topInset] keeps it under
 * the status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassPullToRefresh(refreshing: Boolean, onRefresh: () -> Unit, topInset: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val state = rememberPullToRefreshState()
    val liquid = LocalUiPerformance.current.liquid
    val colors = AniMixTheme.colors
    PullToRefreshBox(refreshing, onRefresh, modifier.fillMaxSize(), state = state, indicator = {
        val shown = refreshing || state.distanceFraction > 0f
        if (shown) Box(
            Modifier.align(Alignment.TopCenter).padding(top = topInset).graphicsLayer {
                val pull = if (refreshing) 1f else state.distanceFraction
                translationY = (pull.coerceAtMost(1.4f) * 72.dp.toPx()) - 44.dp.toPx()
                alpha = pull.coerceIn(0f, 1f)
                val over = if (liquid) (pull - 1f).coerceIn(0f, 0.4f) else 0f
                scaleY = 1f + over * 0.35f
                scaleX = 1f - over * 0.15f
            }.size(44.dp).clip(CircleShape).background(colors.surfaceRaised.copy(alpha = 0.94f)).liquidSheen().rim(CircleShape, accent = refreshing)
                .semantics { contentDescription = if (refreshing) "Обновляется" else "Потяните, чтобы обновить" },
            contentAlignment = Alignment.Center,
        ) {
            if (refreshing) LoadingIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(AniMixIcons.Refresh, null, Modifier.size(20.dp).graphicsLayer { rotationZ = state.distanceFraction * 270f }, tint = colors.accent)
        }
    }, content = content)
}
