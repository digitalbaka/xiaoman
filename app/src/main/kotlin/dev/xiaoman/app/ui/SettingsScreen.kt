package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.DarkMode
import dev.xiaoman.app.settings.SettingsForm

@Composable
fun SettingsScreen(graph: AppGraph) {
    val store = graph.settings
    val settings by store.settings.collectAsStateWithLifecycle()
    var showModels by remember { mutableStateOf(false) }
    if (showModels) {
        ModelScreen(graph) { showModels = false }
        return
    }
    var form by remember { mutableStateOf(SettingsForm.of(store.current)) }
    var saved by remember { mutableStateOf(false) }
    var showMcp by remember { mutableStateOf(false) }
    var showMcpStore by remember { mutableStateOf(false) }
    var showStores by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    // Inside a settings sub-page, back returns to the settings list instead of leaving the tab.
    BackHandler(enabled = showModels || showMcp || showMcpStore || showStores || showTheme || showLanguage || showAbout) {
        showModels = false
        showMcp = false
        showMcpStore = false
        showStores = false
        showTheme = false
        showLanguage = false
        showAbout = false
    }
    if (showAbout) {
        AboutScreen(onBack = { showAbout = false })
        return
    }
    if (showTheme) {
        ThemeScreen(settings, onSave = { store.save(it) }, onBack = { showTheme = false })
        return
    }
    if (showLanguage) {
        LanguageScreen(
            current = settings.language,
            onSelect = { store.save(store.current.copy(language = it)) },
            onBack = { showLanguage = false },
        )
        return
    }
    if (showStores) {
        SkillStoresScreen(graph) { showStores = false }
        return
    }
    if (showMcpStore) {
        McpStoreScreen(graph, onBack = { showMcpStore = false }, onOpenServices = {
            showMcpStore = false
            showMcp = true
        })
        return
    }
    if (showMcp) {
        McpScreen(graph) { showMcp = false }
        return
    }
    val parsed = form.toSettings(settings)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)

        Text(stringResource(R.string.settings_chat_model), style = MaterialTheme.typography.titleMedium)
        Text(
            settings.selectedChatModel?.displayName?.let { stringResource(R.string.settings_selected_model, it) }
                ?: stringResource(R.string.settings_no_model),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { showModels = true }) { Text(stringResource(R.string.settings_manage_models)) }
        TextButton(onClick = { showMcp = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.settings_mcp_entry))
                Text(
                    stringResource(R.string.settings_mcp_enabled, settings.mcp.enabledCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EntryChevron()
        }
        TextButton(onClick = { showMcpStore = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.mcp_store_entry))
                Text(
                    stringResource(R.string.mcp_store_entry_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EntryChevron()
        }
        TextButton(onClick = { showStores = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.settings_skill_store_entry))
                Text(
                    stringResource(R.string.settings_skill_store_current, settings.selectedSkillSource.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EntryChevron()
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = form.operatorSameAsChat,
                onCheckedChange = {
                    form = form.copy(operatorSameAsChat = it)
                    saved = false
                },
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_copy_chat))
                Text(
                    stringResource(R.string.settings_same_as_chat_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ModelSection(
            title = stringResource(R.string.settings_operator_model),
            model = if (form.operatorSameAsChat) form.chat else form.operator,
            enabled = !form.operatorSameAsChat,
        ) {
            if (!form.operatorSameAsChat) {
                form = form.copy(operator = it)
                saved = false
            }
        }
        HorizontalDivider()

        Text(stringResource(R.string.settings_agent), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = form.maxSteps,
            onValueChange = {
                form = form.copy(maxSteps = it)
                saved = false
            },
            label = { Text(stringResource(R.string.settings_max_steps)) },
            isError = !form.maxStepsValid,
            supportingText = { if (!form.maxStepsValid) Text(stringResource(R.string.settings_invalid_number)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_learn), modifier = Modifier.weight(1f))
            Switch(checked = form.learnFromSuccess, onCheckedChange = {
                form = form.copy(learnFromSuccess = it)
                saved = false
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_show_suggestions))
                Text(
                    stringResource(R.string.settings_show_suggestions_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.showTaskSuggestions,
                onCheckedChange = { store.save(store.current.copy(showTaskSuggestions = it)) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_speak_replies))
                Text(
                    stringResource(R.string.settings_speak_replies_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.speakReplies,
                onCheckedChange = { store.save(store.current.copy(speakReplies = it)) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_start_fresh))
                Text(
                    stringResource(R.string.settings_start_fresh_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.startFreshChat,
                onCheckedChange = { store.save(store.current.copy(startFreshChat = it)) },
            )
        }

        TextButton(onClick = { showLanguage = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.settings_language))
                Text(
                    languageName(settings.language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EntryChevron()
        }

        Text(stringResource(R.string.settings_dark_mode), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                DarkMode.SYSTEM to R.string.dark_mode_system,
                DarkMode.LIGHT to R.string.dark_mode_light,
                DarkMode.DARK to R.string.dark_mode_dark,
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = settings.darkMode == mode,
                    onClick = { store.save(store.current.copy(darkMode = mode)) },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        TextButton(onClick = { showTheme = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.theme_entry))
                Text(
                    stringResource(R.string.theme_entry_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EntryChevron()
        }

        Text(stringResource(R.string.settings_font_size), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_font_size_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FONT_STEPS.forEach { (scale, label) ->
                FilterChip(
                    selected = kotlin.math.abs(settings.fontScale - scale) < 0.01f,
                    onClick = { store.save(store.current.copy(fontScale = scale)) },
                    label = { Text(label) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = parsed != null, onClick = {
                parsed?.let {
                    store.save(it)
                    form = SettingsForm.of(it)
                    saved = true
                }
            }) { Text(stringResource(R.string.settings_save)) }
            if (saved) {
                Text(
                    stringResource(R.string.settings_saved),
                    modifier = Modifier.padding(start = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        HorizontalDivider()
        PersonalizationSection(settings) { updated ->
            store.save(updated)
            saved = false
        }

        HorizontalDivider()
        Text(stringResource(R.string.settings_folders), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_folder_skills, graph.skillsDir.path), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.settings_folder_memory, graph.memoryDir.path), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.settings_folder_traces, graph.tracesDir.path), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        TextButton(onClick = { showAbout = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.about_entry))
                Text(
                    stringResource(R.string.about_entry_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EntryChevron()
        }
    }
}

@Composable
private fun ModelSection(
    title: String,
    model: SettingsForm.ModelForm,
    enabled: Boolean = true,
    onChange: (SettingsForm.ModelForm) -> Unit,
) {
    var showKey by remember { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = model.baseUrl,
        onValueChange = { onChange(model.copy(baseUrl = it)) },
        label = { Text(stringResource(R.string.settings_base_url)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.model,
        onValueChange = { onChange(model.copy(model = it)) },
        label = { Text(stringResource(R.string.settings_model)) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.apiKey,
        onValueChange = { onChange(model.copy(apiKey = it)) },
        label = { Text(stringResource(R.string.settings_api_key)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(enabled = enabled, onClick = { showKey = !showKey }) {
                Icon(
                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (showKey) R.string.settings_hide_key else R.string.settings_show_key),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.temperature,
        onValueChange = { onChange(model.copy(temperature = it)) },
        label = { Text(stringResource(R.string.settings_temperature)) },
        isError = !model.temperatureValid,
        supportingText = { if (!model.temperatureValid) Text(stringResource(R.string.settings_invalid_number)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Row affordance that points to the reading direction's end, so it mirrors under RTL. */
@Composable
private fun EntryChevron() {
    Text(
        if (LocalLayoutDirection.current == LayoutDirection.Rtl) "‹" else "›",
        style = MaterialTheme.typography.titleMedium,
    )
}
