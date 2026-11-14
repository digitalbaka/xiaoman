package dev.xiaoman.app.ui

import android.content.res.Configuration
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.xiaoman.app.settings.AppSettings
import java.util.Locale

/**
 * Applies the language picked in Settings without recreating the activity. A configuration
 * context makes `stringResource` resolve the chosen locale, and the default locale is set so
 * dates and numbers follow the choice too.
 */
@Composable
fun LocalizedApp(language: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    if (language == AppSettings.LANGUAGE_SYSTEM) {
        content()
        return
    }
    val locale = remember(language) { Locale.forLanguageTag(language) }
    // Messages built outside composition (notifications, session texts) also use this locale.
    DisposableEffect(locale) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        onDispose { Locale.setDefault(previous) }
    }
    val config = remember(locale) {
        Configuration(context.resources.configuration).apply { setLocale(locale) }
    }
    val localized = remember(locale) { context.createConfigurationContext(config) }
    // createConfigurationContext drops the Activity chain, so rememberLauncherForActivityResult
    // could no longer find the activity's registry and crashed; hand it down explicitly.
    val registryOwner = LocalActivityResultRegistryOwner.current
    if (registryOwner != null) {
        CompositionLocalProvider(
            LocalContext provides localized,
            LocalConfiguration provides config,
            LocalActivityResultRegistryOwner provides registryOwner,
        ) { content() }
    } else {
        CompositionLocalProvider(
            LocalContext provides localized,
            LocalConfiguration provides config,
        ) { content() }
    }
}
