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
import app.animix.ui.components.at
import app.animix.ui.theme.at
import app.animix.ui.theme.themeColors
import java.awt.Dimension
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val graph = AppGraph()
    if (Snapshot.runIfRequested(args, graph)) kotlin.system.exitProcess(0)
    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 820.dp), position = WindowPosition.Aligned(Alignment.Center))
        val icon = remember {
            javaClass.getResourceAsStream("/animix_icon.png")?.use { ImageIO.read(it) }?.let { BitmapPainter(it.toComposeImageBitmap()) }
        }
        val exit: () -> Unit = {
            graph.social.setActive(false)
            graph.watchFlow.pause()
            runBlocking { graph.downloads.shutdown(); graph.flushUserData() }
            exitApplication()
        }
        val settings by graph.settings.state.collectAsState()
        val level by graph.level.collectAsState()
        // «Значок приложения»: the window and taskbar take the chosen icon; the classic one until data loads.
        val chosenIcon = settings.appIcon.at(level.level)
        val windowIcon = if (chosenIcon == app.animix.core.AppIcon.Classic) icon else app.animix.ui.components.appIconPainter(chosenIcon)
        Window(onCloseRequest = exit, state = state, title = "AniMix", icon = windowIcon) {
            LaunchedEffect(state.isMinimized) { graph.social.setActive(!state.isMinimized) }
            val colors = themeColors(settings.themeMode.at(level.level), isSystemInDarkTheme())
            LaunchedEffect(colors) {
                WindowsTitleBar.apply(window, colors.isDark, colors.background.toArgb(), colors.textPrimary.toArgb())
            }
            LaunchedEffect(Unit) { window.minimumSize = Dimension(400, 640) }
            App(graph, startLink = args.firstOrNull { it.startsWith("animix://") }, onExit = exit)
        }
    }
}
