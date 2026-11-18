package dev.xiaoman.app

import android.content.Intent
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
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

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
        // ADJUST_RESIZE is deprecated on API 30+ (WindowInsets is preferred), but this flag pair is
        // what keeps the IME hidden at launch; switching would change runtime behaviour.
        @Suppress("DEPRECATION")
        val softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        window.setSoftInputMode(softInputMode)
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
        handleLink(intent)
    }

    /** Internal routes (alarm / yunhu) are parsed here; the UI shows them and clears the slot. */
    private fun handleLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val link = AppLink.parse(intent.data) ?: return
        (application as XiaomanApp).graph.pendingLink.value = link
    }
}
