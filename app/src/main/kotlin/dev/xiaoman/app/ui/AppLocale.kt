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

/** Runs [content] with strings and configuration of [language]; no-op for "follow system". */
@Composable
internal fun LocalizedContextProvider(language: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    if (language == AppSettings.LANGUAGE_SYSTEM) {
        content()
        return
    }
    val locale = remember(language) { Locale.forLanguageTag(language) }
    val config = remember(locale) {
        Configuration(base.resources.configuration).apply { setLocale(locale) }
    }
    val resources = remember(language, base) { base.createConfigurationContext(config).resources }
    val localized = remember(language, base) { LocalizedContext(base, resources) }
    CompositionLocalProvider(
        LocalContext provides localized,
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
