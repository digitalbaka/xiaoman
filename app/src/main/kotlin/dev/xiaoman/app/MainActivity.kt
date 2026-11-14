package dev.xiaoman.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.ui.LocalizedApp
import dev.xiaoman.app.ui.XiaomanRoot
import dev.xiaoman.app.ui.XiaomanTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as XiaomanApp).graph.uiVisible.value = true
    }

    override fun onStop() {
        (application as XiaomanApp).graph.uiVisible.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The input field used to grab focus and pop the IME on launch; let the user tap instead.
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
        )
        enableEdgeToEdge()
        val graph = (application as XiaomanApp).graph
        // Read settings here (not in AppGraph.start) so unit tests that only create the
        // Application never touch the encrypted preferences / keystore.
        if (graph.settings.current.startFreshChat) graph.session.newConversation()
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            XiaomanTheme(
                accentHex = settings.themeColor,
                fontScale = settings.fontScale,
                darkMode = settings.darkMode,
                wallpaperColors = settings.themeStyle == AppSettings.THEME_STYLE_WALLPAPER,
            ) {
                LocalizedApp(settings.language) { XiaomanRoot(graph) }
            }
        }
    }
}
