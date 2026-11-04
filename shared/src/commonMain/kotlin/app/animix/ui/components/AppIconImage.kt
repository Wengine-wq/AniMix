package app.animix.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import app.animix.core.AppIcon
import app.animix.resources.Res
import app.animix.resources.app_icon_classic
import app.animix.resources.app_icon_coal
import app.animix.resources.app_icon_dawn
import app.animix.resources.app_icon_ice
import org.jetbrains.compose.resources.painterResource

/** The rounded app icon in one of its four colourings: the Windows window icon, the picker and «О приложении». */
@Composable
fun appIconPainter(icon: AppIcon): Painter = painterResource(when (icon) {
    AppIcon.Classic -> Res.drawable.app_icon_classic
    AppIcon.Dawn -> Res.drawable.app_icon_dawn
    AppIcon.Ice -> Res.drawable.app_icon_ice
    AppIcon.Coal -> Res.drawable.app_icon_coal
})

/** The icon shown for [chosen] at [level]: a turquoise icon opens with its theme. */
fun AppIcon.at(level: Int): AppIcon = if (level >= unlockLevel) this else AppIcon.Classic
