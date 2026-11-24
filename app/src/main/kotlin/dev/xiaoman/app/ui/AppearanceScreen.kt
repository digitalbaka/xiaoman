package dev.xiaoman.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.R
import dev.xiaoman.app.settings.AppSettings
import kotlin.math.roundToInt

/**
 * Appearance page: fonts, brightness, chat background and opacity plus the palette.
 * Every control writes through immediately, so the change is visible on this page at once.
 */
@Composable
internal fun AppearanceScreen(settings: AppSettings, onSave: (AppSettings) -> Unit, onBack: () -> Unit) {
    var customText by remember { mutableStateOf(settings.themeColor) }
    val customValid = hexPattern.matches(customText.trim())
    val wallpaperOk = AppSettings.supportsWallpaperTheme

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) }
            Column {
                Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.appearance_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SliderRow(
                title = stringResource(R.string.appearance_font_size),
                valueText = percentText(settings.fontScale),                value = settings.fontScale,
                range = AppSettings.FONT_SCALE_RANGE,
                steps = 7,
                onChange = { onSave(settings.copy(fontScale = it)) },
            )
            SliderRow(
                title = stringResource(R.string.appearance_brightness),
                valueText = if (settings.screenBrightness < 0f) {
                    stringResource(R.string.appearance_brightness_follow)
                } else {
                    percentText(settings.screenBrightness)
                },
                value = if (settings.screenBrightness < 0f) 1f else settings.screenBrightness,
                range = AppSettings.SCREEN_BRIGHTNESS_RANGE,
                steps = 17,
                onChange = { onSave(settings.copy(screenBrightness = it)) },
            )
            if (settings.screenBrightness >= 0f) {
                TextButton(onClick = { onSave(settings.copy(screenBrightness = AppSettings.BRIGHTNESS_FOLLOW_SYSTEM)) }) {
                    Text(stringResource(R.string.appearance_brightness_follow))
                }
            }
            SliderRow(
                title = stringResource(R.string.appearance_background_dim),
                valueText = percentText(settings.backgroundDim),
                value = settings.backgroundDim,
                range = AppSettings.BACKGROUND_DIM_RANGE,
                steps = 15,
                onChange = { onSave(settings.copy(backgroundDim = it)) },
            )
            SliderRow(
                title = stringResource(R.string.appearance_opacity),
                valueText = percentText(settings.uiOpacity),
                value = settings.uiOpacity,
                range = AppSettings.UI_OPACITY_RANGE,
                steps = 9,
                onChange = { onSave(settings.copy(uiOpacity = it)) },
            )

            Text(stringResource(R.string.theme_preview), style = MaterialTheme.typography.titleMedium)
            XiaomanTheme(
                accentHex = settings.themeColor,
                fontScale = settings.fontScale,
                darkMode = settings.darkMode,
                wallpaperColors = settings.themeStyle == AppSettings.THEME_STYLE_WALLPAPER,
            ) {
                ThemePreview(settings.assistantName)
            }

            Text(stringResource(R.string.appearance_theme), style = MaterialTheme.typography.titleMedium)
            THEME_PRESETS.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { preset ->
                        PresetCard(
                            label = stringResource(preset.name),
                            color = themeAccent(preset.hex),
                            selected = settings.themeStyle == AppSettings.THEME_STYLE_ACCENT &&
                                settings.themeColor.equals(preset.hex, ignoreCase = true),
                            enabled = true,
                            modifier = Modifier.weight(1f),
                        ) {
                            onSave(settings.copy(themeStyle = AppSettings.THEME_STYLE_ACCENT, themeColor = preset.hex))
                            customText = preset.hex
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text(
                stringResource(R.string.appearance_note_pure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PresetCard(
                label = stringResource(R.string.theme_wallpaper),
                color = MaterialTheme.colorScheme.primary,
                selected = settings.themeStyle == AppSettings.THEME_STYLE_WALLPAPER,
                enabled = wallpaperOk,
                modifier = Modifier.fillMaxWidth(),
                icon = true,
            ) {
                if (wallpaperOk) onSave(settings.copy(themeStyle = AppSettings.THEME_STYLE_WALLPAPER))
            }
            Text(
                stringResource(if (wallpaperOk) R.string.theme_wallpaper_hint else R.string.theme_wallpaper_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = if (wallpaperOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )

            Text(stringResource(R.string.theme_custom), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.trim() },
                    label = { Text(stringResource(R.string.theme_custom_label)) },
                    singleLine = true,
                    isError = customText.isNotBlank() && !customValid,
                    supportingText = {
                        if (customText.isNotBlank() && !customValid) Text(stringResource(R.string.theme_custom_invalid))
                    },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = customValid,
                    onClick = {
                        onSave(settings.copy(themeStyle = AppSettings.THEME_STYLE_ACCENT, themeColor = customText.trim().uppercase()))
                    },
                ) { Text(stringResource(R.string.theme_custom_use)) }
            }

            TextButton(
                onClick = {
                    val initial = AppSettings()
                    onSave(
                        settings.copy(
                            fontScale = initial.fontScale,
                            screenBrightness = initial.screenBrightness,
                            backgroundDim = initial.backgroundDim,
                            uiOpacity = initial.uiOpacity,
                            themeStyle = initial.themeStyle,
                            themeColor = initial.themeColor,
                        ),
                    )
                    customText = initial.themeColor
                },
            ) { Text(stringResource(R.string.appearance_reset)) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One labelled slider with its current value on the trailing side. */
@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun percentText(value: Float): String = "${(value * 100).roundToInt()}%"

/** Small chat mock so a palette can be judged without leaving the page. */
@Composable
private fun ThemePreview(assistantName: String) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("满", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    assistantName,
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                    Text(
                        stringResource(R.string.chat_status_ready),
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(5.dp, 16.dp, 16.dp, 16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                Text(
                    stringResource(R.string.theme_preview_reply),
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp),
                modifier = Modifier.fillMaxWidth(0.6f).align(Alignment.End),
            ) {
                Text(
                    stringResource(R.string.theme_preview_user),
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** One palette tile: colour wash on top, label below, check when selected. */
@Composable
private fun PresetCard(
    label: String,
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    icon: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier.alpha(if (enabled) 1f else 0.45f).clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(color),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !enabled -> Icon(Icons.Outlined.Wallpaper, null, tint = Color.White)
                    selected -> Icon(Icons.Filled.Check, label, tint = Color.White)
                    icon -> Icon(Icons.Outlined.Wallpaper, label, tint = Color.White)
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private val hexPattern = Regex("^#[0-9A-Fa-f]{6}$")
