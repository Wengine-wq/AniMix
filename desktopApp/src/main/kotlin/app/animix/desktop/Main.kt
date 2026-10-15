package app.animix.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.animix.App
import app.animix.AppGraph
import app.animix.core.ThemeMode
import app.animix.ui.theme.HorizonDawn
import app.animix.ui.theme.HorizonDusk
import java.awt.Dimension
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val graph = AppGraph()
    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 820.dp), position = WindowPosition.Aligned(Alignment.Center))
        val icon = remember {
            javaClass.getResourceAsStream("/animix_icon.png")?.use { ImageIO.read(it) }?.let { BitmapPainter(it.toComposeImageBitmap()) }
        }
        val exit: () -> Unit = {
            runBlocking { graph.library.flush() } // never lose the last list change
            exitApplication()
        }
        Window(onCloseRequest = exit, state = state, title = "AniMix", icon = icon) {
            val settings by graph.settings.state.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Dusk -> true
                ThemeMode.Dawn -> false
            }
            LaunchedEffect(dark) {
                val colors = if (dark) HorizonDusk else HorizonDawn
                WindowsTitleBar.apply(window, dark, colors.background.toArgb(), colors.textPrimary.toArgb())
            }
            LaunchedEffect(Unit) { window.minimumSize = Dimension(400, 640) }
            App(graph, startLink = args.firstOrNull { it.startsWith("animix://") }, onExit = exit)
        }
    }
}
