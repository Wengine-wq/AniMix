package app.animix.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.animix.data.mascot.MascotCosplay
import app.animix.resources.Res
import app.animix.resources.animix_cosplay_demon
import app.animix.resources.animix_cosplay_quintuplets
import app.animix.resources.animix_cosplay_titan
import app.animix.resources.animix_cosplay_hunter
import app.animix.ui.theme.AniMixTheme
import org.jetbrains.compose.resources.painterResource

/** A single quiet character illustration in the title's content, outside playback/actions. */
@Composable
fun MascotCosplayNote(cosplay: MascotCosplay, modifier: Modifier = Modifier) {
    if (!MascotShown) return
    val artwork = when (cosplay) {
        MascotCosplay.Nezuko -> Res.drawable.animix_cosplay_demon
        MascotCosplay.Miku -> Res.drawable.animix_cosplay_quintuplets
        MascotCosplay.Scout -> Res.drawable.animix_cosplay_titan
        MascotCosplay.Gon -> Res.drawable.animix_cosplay_hunter
    }
    Row(modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(artwork), null, Modifier.size(width = 112.dp, height = 184.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Txt("Косплей маскота", AniMixTheme.type.caption)
            Spacer(Modifier.height(6.dp))
            Txt(cosplay.character, AniMixTheme.type.subheading)
        }
    }
}
