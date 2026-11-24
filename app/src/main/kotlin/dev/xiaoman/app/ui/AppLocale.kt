package dev.xiaoman.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.xiaoman.app.settings.AppSettings
import java.util.Locale

/**
 * The language picked in Settings. Dialogs open their own window with their own context, so a
 * custom local (unlike LocalContext) is what reliably survives into them.
 */
val LocalAppLanguage = staticCompositionLocalOf { AppSettings.LANGUAGE_SYSTEM }

/**
 * Wraps [base] but resolves strings with the chosen language. Keeping the wrapper chain means
 * Activity lookups (file pickers, window access) still find the activity behind the dialog.
 */
private class LocalizedContext(base: Context, private val localized: Resources) : ContextWrapper(base) {
    override fun getResources(): Resources = localized
}

/**
 * Runs [content] with strings and configuration of [language]; for "follow system" the current
 * context is passed through untouched. The provider is always emitted so that switching language
 * keeps the composition tree (and the state of the page the user is on) intact.
 */
@Composable
internal fun LocalizedContextProvider(language: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val currentConfig = LocalConfiguration.current
    val config = if (language == AppSettings.LANGUAGE_SYSTEM) {
        currentConfig
    } else {
        val locale = remember(language) { Locale.forLanguageTag(language) }
        remember(locale) { Configuration(currentConfig).apply { setLocale(locale) } }
    }
    val context = if (language == AppSettings.LANGUAGE_SYSTEM) {
        base
    } else {
        remember(language, base, config) {
            LocalizedContext(base, base.createConfigurationContext(config).resources)
        }
    }
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides config,
    ) { content() }
}

/**
 * Applies the language picked in Settings without recreating the activity, and flips the layout
 * direction for right-to-left languages such as Arabic.
 */
@Composable
fun LocalizedApp(language: String, content: @Composable () -> Unit) {
    // Messages built outside composition (notifications, session texts) also use this locale.
    val locale = remember(language) {
        if (language == AppSettings.LANGUAGE_SYSTEM) null else Locale.forLanguageTag(language)
    }
    DisposableEffect(locale) {
        if (locale == null) return@DisposableEffect onDispose { }
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        onDispose { Locale.setDefault(previous) }
    }
    val direction = remember(locale) {
        if (locale != null && android.text.TextUtils.getLayoutDirectionFromLocale(locale) == android.view.View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides direction,
        LocalAppLanguage provides language,
    ) {
        LocalizedContextProvider(language, content)
    }
}
