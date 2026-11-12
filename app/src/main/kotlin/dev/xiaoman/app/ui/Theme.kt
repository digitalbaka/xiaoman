package dev.xiaoman.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.xiaoman.app.settings.AppSettings

/** Accent colours offered in Settings; the chosen hex is stored in [AppSettings.themeColor]. */
data class ThemePreset(val name: String, val hex: String)

val THEME_PRESETS = listOf(
    ThemePreset("海蓝", "#3D7BF0"),
    ThemePreset("雾紫", "#7B5CF0"),
    ThemePreset("桃粉", "#E45C9A"),
    ThemePreset("落日", "#E2853C"),
    ThemePreset("森绿", "#2E8B70"),
    ThemePreset("石墨", "#455A64"),
)

/** Parses `#RRGGBB`; falls back to the default accent for anything malformed. */
fun themeAccent(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFF3D7BF0))

/**
 * Stable Xiaoman colors; readable light/dark surfaces independent of the phone wallpaper.
 * The accent comes from the user's theme choice and affects the whole app.
 */
@Composable
fun XiaomanTheme(accentHex: String = AppSettings.DEFAULT_THEME_COLOR, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val accent = themeAccent(accentHex)
    val scheme = if (dark) {
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
            onPrimary = Color.White,
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
    MaterialTheme(colorScheme = scheme, typography = Typography(
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
        titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    ), content = content)
}
