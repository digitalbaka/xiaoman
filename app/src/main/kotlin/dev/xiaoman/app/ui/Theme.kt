package dev.xiaoman.app.ui

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.xiaoman.app.R
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.DarkMode

/** Accent colours offered on the theme page; the chosen hex is stored in [AppSettings.themeColor]. */
data class ThemePreset(@StringRes val name: Int, val hex: String)

val THEME_PRESETS = listOf(
    ThemePreset(R.string.theme_preset_ocean, "#3D7BF0"),
    ThemePreset(R.string.theme_preset_purple, "#7B5CF0"),
    ThemePreset(R.string.theme_preset_pink, "#E45C9A"),
    ThemePreset(R.string.theme_preset_sunset, "#E2853C"),
    ThemePreset(R.string.theme_preset_forest, "#2E8B70"),
    ThemePreset(R.string.theme_preset_graphite, "#455A64"),
    ThemePreset(R.string.theme_preset_celadon, "#2E9E9B"),
    ThemePreset(R.string.theme_preset_indigo, "#4355B9"),
    ThemePreset(R.string.theme_preset_rose, "#C94F6D"),
    ThemePreset(R.string.theme_preset_coffee, "#7A5C46"),
    ThemePreset(R.string.theme_preset_sakura, "#C2557B"),
    ThemePreset(R.string.theme_preset_lavender, "#7A66C4"),
    ThemePreset(R.string.theme_preset_mint, "#2F8A75"),
)

/** Parses `#RRGGBB`; falls back to the default accent for anything malformed. */
fun themeAccent(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFF3D7BF0))

/**
 * Stable Xiaoman colors; readable light/dark surfaces independent of the phone wallpaper.
 * The accent comes from the user's theme choice (preset / custom hex) or, on Android 12+,
 * the wallpaper's Material You palette.
 */
@Composable
fun XiaomanTheme(
    accentHex: String = AppSettings.DEFAULT_THEME_COLOR,
    fontScale: Float = 1f,
    darkMode: DarkMode = DarkMode.SYSTEM,
    wallpaperColors: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.DARK -> true
        DarkMode.LIGHT -> false
    }
    val scheme = if (wallpaperColors && AppSettings.supportsWallpaperTheme) {
        SystemDynamicScheme(dark)
    } else {
        val accent = themeAccent(accentHex)
        if (dark) {
            val primary = lerp(accent, Color.White, 0.32f)
            darkColorScheme(
                primary = primary,
                onPrimary = Color(0xFF101614),
                primaryContainer = lerp(accent, Color.Black, 0.45f),
                onPrimaryContainer = lerp(accent, Color.White, 0.82f),
                secondaryContainer = lerp(accent, Color.Black, 0.55f),
                onSecondaryContainer = lerp(accent, Color.White, 0.85f),
                background = Color(0xFF121518),
                surface = Color(0xFF1B2024),
                surfaceContainerLow = Color(0xFF20262B),
                outlineVariant = Color(0xFF354047),
            )
        } else {
            lightColorScheme(
                primary = accent,
                // Very light accents (custom hex) need dark text; every preset stays on white.
                onPrimary = if (accent.luminance() > 0.55f) Color(0xFF101614) else Color.White,
                primaryContainer = lerp(accent, Color.White, 0.84f),
                onPrimaryContainer = lerp(accent, Color.Black, 0.42f),
                secondaryContainer = lerp(accent, Color.White, 0.90f),
                onSecondaryContainer = lerp(accent, Color.Black, 0.45f),
                background = Color(0xFFF7F8FB),
                surface = Color.White,
                surfaceContainerLow = lerp(accent, Color.White, 0.955f),
                surfaceVariant = lerp(accent, Color.White, 0.94f),
                onSurface = Color(0xFF1D2329),
                onSurfaceVariant = Color(0xFF68727A),
                outlineVariant = lerp(accent, Color.White, 0.82f),
            )
        }
    }
    // Scaling the density's fontScale multiplies every sp in the app (and nothing in dp),
    // so one setting covers Material text plus any explicit fontSize.
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density = base.density, fontScale = base.fontScale * fontScale.coerceIn(AppSettings.FONT_SCALE_RANGE)),
    ) {
        MaterialTheme(colorScheme = scheme, typography = Typography(
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
        ), content = content)
    }
}

/**
 * Material You palette from the wallpaper. Re-registers on wallpaper changes so a new
 * wallpaper repaints the app without restarting it.
 */
@Composable
private fun SystemDynamicScheme(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val unregister: () -> Unit
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The deprecated broadcast is replaced by the wallpaper colour listener on API 31+.
            val manager = WallpaperManager.getInstance(context)
            val listener = WallpaperManager.OnColorsChangedListener { _, _ -> version++ }
            manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            unregister = { manager.removeOnColorsChangedListener(listener) }
        } else {
            // Below S the scheme ignores the wallpaper; keep the old refresh path unchanged.
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(incoming: Context?, intent: Intent?) {
                    version++
                }
            }
            @Suppress("DEPRECATION")
            val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            unregister = { runCatching { context.unregisterReceiver(receiver) } }
        }
        onDispose { unregister() }
    }
    return remember(version, dark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            lightColorScheme()
        }
    }
}
