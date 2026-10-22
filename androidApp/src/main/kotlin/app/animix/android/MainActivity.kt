package app.animix.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.animix.App
import app.animix.core.ThemeMode
import app.animix.ui.navigation.Navigator
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as AniMixApplication).graph
        pendingLink = intent?.dataString
        setContent {
            val settings by graph.settings.state.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Dusk -> true
                ThemeMode.Dawn -> false
            }
            // Status and navigation bar icons follow the app theme, not only the system one.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val navigator = remember { Navigator() }
            val link = pendingLink
            LaunchedEffect(link) {
                if (link != null) {
                    navigator.openLink(link)
                    pendingLink = null
                }
            }
            App(graph, navigator = navigator, onExit = { finish() })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLink = intent.dataString
    }

    override fun onStop() {
        super.onStop()
        val graph = (application as AniMixApplication).graph
        graph.watchFlow.pause()
        graph.scope.launch { graph.flushUserData() }
    }
}
