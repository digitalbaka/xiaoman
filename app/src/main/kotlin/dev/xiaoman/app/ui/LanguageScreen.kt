package dev.xiaoman.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.R
import dev.xiaoman.app.settings.AppSettings

/** One row per shipped language; [label] null means "follow the system". */
internal val LANGUAGE_OPTIONS = listOf(
    LanguageOption(AppSettings.LANGUAGE_SYSTEM, null),
    LanguageOption("zh-CN", "简体中文"),
    LanguageOption("zh-TW", "繁體中文"),
    LanguageOption("en", "English"),
    LanguageOption("ja", "日本語"),
    LanguageOption("ko", "한국어"),
    LanguageOption("ar", "العربية"),
    LanguageOption("de", "Deutsch"),
)

internal data class LanguageOption(val tag: String, val label: String?)

/** Native name of [tag], or the "follow system" label. */
@Composable
internal fun languageName(tag: String): String =
    LANGUAGE_OPTIONS.firstOrNull { it.tag == tag }?.label ?: stringResource(R.string.language_system)

/** Picking a row applies immediately; the caller stores it. */
@Composable
internal fun LanguageScreen(current: String, onSelect: (String) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) }
            Column {
                Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.language_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            LANGUAGE_OPTIONS.forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                LanguageRow(
                    label = option.label ?: stringResource(R.string.language_system),
                    hint = if (option.tag == AppSettings.LANGUAGE_SYSTEM) stringResource(R.string.language_system_hint) else null,
                    selected = current == option.tag,
                    onClick = { onSelect(option.tag) },
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(label: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}
