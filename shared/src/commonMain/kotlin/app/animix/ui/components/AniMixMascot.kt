package app.animix.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.animix.resources.Res
import app.animix.resources.animix_mascot_v2
import app.animix.resources.animix_mascot_thinking
import app.animix.resources.animix_mascot_relaxed
import app.animix.resources.animix_mascot_happy
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme
import org.jetbrains.compose.resources.painterResource

enum class MascotPose { Welcome, Thinking, Relaxed, Happy }

/** The mascot is being redrawn: until then no screen shows it, and empty states use a quiet icon. */
const val MascotShown = false

/** Bundled transparent artwork: no network, animation, panel or theme-specific tint. */
@Composable
fun AniMixMascot(modifier: Modifier = Modifier, contentDescription: String? = null, pose: MascotPose = MascotPose.Welcome) {
    if (!MascotShown) return
    val artwork = when (pose) {
        MascotPose.Welcome -> Res.drawable.animix_mascot_v2
        MascotPose.Thinking -> Res.drawable.animix_mascot_thinking
        MascotPose.Relaxed -> Res.drawable.animix_mascot_relaxed
        MascotPose.Happy -> Res.drawable.animix_mascot_happy
    }
    Image(painterResource(artwork), contentDescription,
        modifier = modifier, contentScale = ContentScale.Fit)
}

/** Empty content only: loading and failures keep their own state and recovery actions. */
@Composable
fun MascotMessage(title: String, message: String, pose: MascotPose, modifier: Modifier = Modifier,
    onExplore: (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (MascotShown) {
                AniMixMascot(Modifier.size(width = 160.dp, height = 208.dp), pose = pose)
                Spacer(Modifier.height(16.dp))
            } else {
                val colors = AniMixTheme.colors
                Box(Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(when (pose) {
                        MascotPose.Thinking -> AniMixIcons.Search
                        MascotPose.Relaxed -> AniMixIcons.Download
                        MascotPose.Happy -> AniMixIcons.Check
                        MascotPose.Welcome -> AniMixIcons.Bookmark
                    }, null, Modifier.size(28.dp), tint = colors.accent)
                }
                Spacer(Modifier.height(16.dp))
            }
            Txt(title, AniMixTheme.type.subheading, align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Txt(message, AniMixTheme.type.caption, align = TextAlign.Center)
            if (onExplore != null) {
                Spacer(Modifier.height(20.dp))
                SecondaryButton("Открыть каталог", onExplore, icon = AniMixIcons.Search)
            }
        }
    }
}
