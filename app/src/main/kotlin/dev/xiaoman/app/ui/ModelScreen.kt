package dev.xiaoman.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.ModelRole
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.settings.ModelSettings
import dev.xiaoman.app.settings.SavedModel
import dev.xiaoman.app.settings.SettingsForm
import kotlinx.coroutines.launch

/** Card list of user-added OpenAI-compatible chat models, styled after the reference screenshot. */
@Composable
fun ModelScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SavedModel?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedModel?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.models_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.models_add), modifier = Modifier.padding(start = 4.dp))
            }
        }

        if (settings.chatModels.isEmpty()) {
            Text(
                stringResource(R.string.models_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(settings.chatModels, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        selected = model.id == settings.selectedChatModelId,
                        onSelect = { graph.settings.saveModels(settings.chatModels, model.id) },
                        onEdit = { editing = model },
                        onDelete = { deleting = model },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        val initial = editing
        ModelEditorDialog(
            initial = initial,
            onDismiss = { creating = false; editing = null },
            onSave = { saved ->
                val models = if (initial == null) {
                    settings.chatModels + saved
                } else {
                    settings.chatModels.map { if (it.id == saved.id) saved else it }
                }
                val selected = settings.selectedChatModelId ?: saved.id
                graph.settings.saveModels(models, selected)
                creating = false
                editing = null
            },
        )
    }

    deleting?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.models_delete_title)) },
            text = { Text(stringResource(R.string.models_delete_message, target.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    val left = settings.chatModels.filterNot { it.id == target.id }
                    graph.settings.saveModels(left, settings.selectedChatModelId?.takeIf { it != target.id } ?: left.firstOrNull()?.id)
                    deleting = null
                }) { Text(stringResource(R.string.models_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ModelCard(
    model: SavedModel,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 0.dp else 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        model.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        model.model.ifBlank { model.baseUrl },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                RadioButton(selected = selected, onClick = onSelect)
            }
            if (selected) {
                Text(
                    stringResource(R.string.models_default),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 58.dp, top = 2.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.models_edit)) }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.models_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ModelEditorDialog(initial: SavedModel?, onDismiss: () -> Unit, onSave: (SavedModel) -> Unit) {
    val context = LocalContext.current
    val appGraph = (context.applicationContext as dev.xiaoman.app.XiaomanApp).graph
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: ModelSettings.DEFAULT_BASE_URL) }
    var modelId by remember { mutableStateOf(initial?.model.orEmpty()) }
    var apiKey by remember { mutableStateOf(initial?.apiKey.orEmpty()) }
    var temperature by remember { mutableStateOf(initial?.temperature?.toString().orEmpty()) }
    var maxTokens by remember { mutableStateOf(initial?.maxTokens?.toString().orEmpty()) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    val temperatureValid = temperature.isBlank() || SettingsForm.parseTemperature(temperature) != null
    val maxTokensValid = maxTokens.isBlank() || maxTokens.trim().toIntOrNull()?.let { it > 0 } == true
    val canSave = baseUrl.isNotBlank() && modelId.isNotBlank() && apiKey.isNotBlank() && temperatureValid && maxTokensValid

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), tonalElevation = 4.dp) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (initial == null) stringResource(R.string.models_add) else stringResource(R.string.models_edit),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.models_name)) },
                    placeholder = { Text(stringResource(R.string.models_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.settings_base_url)) },
                    placeholder = { Text(ModelSettings.DEFAULT_BASE_URL) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text(stringResource(R.string.models_model_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.settings_api_key)) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(if (showKey) R.string.settings_hide_key else R.string.settings_show_key),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = temperature,
                        onValueChange = { temperature = it },
                        label = { Text(stringResource(R.string.settings_temperature)) },
                        isError = !temperatureValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = { maxTokens = it },
                        label = { Text(stringResource(R.string.models_max_tokens)) },
                        isError = !maxTokensValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                (testMessage ?: validationMessage)?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.models_testing),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    } else {
                        TextButton(
                            enabled = baseUrl.isNotBlank() && modelId.isNotBlank() && apiKey.isNotBlank(),
                            onClick = {
                                testing = true
                                testMessage = null
                                validationMessage = null
                                scope.launch {
                                    val s = ModelSettings(
                                        baseUrl = baseUrl.trim(),
                                        model = modelId.trim(),
                                        apiKey = apiKey.trim(),
                                        temperature = SettingsForm.parseTemperature(temperature),
                                        maxTokens = maxTokens.trim().toIntOrNull(),
                                    )
                                    val result = runCatching {
                                        appGraph.llm.create(s.toModelConfig(ModelRole.CHAT).copy(timeoutSeconds = 30))
                                            .complete(ChatRequest(listOf(ChatMessage.user("ping"))))
                                    }
                                    testing = false
                                    result.fold(
                                        onSuccess = {
                                            testOk = true
                                            testMessage = context.getString(R.string.models_test_ok)
                                        },
                                        onFailure = {
                                            testOk = false
                                            val reason = (it as? LlmException)?.let { e ->
                                                e.statusCode?.let { code -> "$code ${e.message}" } ?: e.message
                                            } ?: it.message ?: it.javaClass.simpleName
                                            testMessage = context.getString(R.string.models_test_failed, reason.take(160))
                                        },
                                    )
                                }
                            },
                        ) { Text(stringResource(R.string.models_test)) }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(
                        enabled = canSave && !testing,
                        onClick = {
                            if (!baseUrl.isNotBlank() || !modelId.isNotBlank()) {
                                validationMessage = context.getString(R.string.models_required)
                            } else if (!apiKey.isNotBlank()) {
                                validationMessage = context.getString(R.string.models_key_required)
                            } else {
                                onSave(
                                    SavedModel(
                                        id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                                        name = name.trim().ifEmpty { modelId.trim() },
                                        baseUrl = baseUrl.trim(),
                                        model = modelId.trim(),
                                        apiKey = apiKey.trim(),
                                        temperature = SettingsForm.parseTemperature(temperature),
                                        maxTokens = maxTokens.trim().toIntOrNull(),
                                    ),
                                )
                            }
                        },
                    ) { Text(stringResource(R.string.settings_save)) }
                }
            }
        }
    }
}
