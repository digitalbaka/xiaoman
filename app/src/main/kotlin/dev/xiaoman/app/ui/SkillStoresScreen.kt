package dev.xiaoman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.settings.SkillSource

/**
 * Picks which Skill store the Skills tab uses, and lets the user add stores that speak the same
 * SkillHub HTTP API. The built-in source cannot be removed.
 */
@Composable
internal fun SkillStoresScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val errorName = stringResource(R.string.skill_store_error_name)
    val errorUrl = stringResource(R.string.skill_store_error_url)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) }
            Text(
                stringResource(R.string.skill_store_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            stringResource(R.string.skill_store_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(settings.allSkillSources, key = { it.id }) { source ->
                val selected = source.id == settings.selectedSkillSourceId
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = {
                        graph.settings.save(graph.settings.current.copy(selectedSkillSourceId = source.id))
                    })
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(source.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (source.builtin) {
                                Spacer(Modifier.width(6.dp))
                                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text(
                                        stringResource(R.string.skill_store_builtin),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            source.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!source.builtin) {
                        IconButton(onClick = {
                            val sources = graph.settings.current.skillSources.filterNot { it.id == source.id }
                            val selectedId = if (settings.selectedSkillSourceId == source.id) SkillSource.BUILTIN_ID
                            else settings.selectedSkillSourceId
                            graph.settings.save(
                                graph.settings.current.copy(skillSources = sources, selectedSkillSourceId = selectedId),
                            )
                        }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.skill_store_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.skill_store_add), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text(stringResource(R.string.skill_store_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text(stringResource(R.string.skill_store_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        Button(
            onClick = {
                val trimmedName = name.trim()
                val trimmedUrl = url.trim().trimEnd('/')
                error = when {
                    trimmedName.isEmpty() -> errorName
                    !trimmedUrl.startsWith("http://", true) && !trimmedUrl.startsWith("https://", true) ->
                        errorUrl
                    else -> null
                }
                if (error == null) {
                    val source = SkillSource(
                        id = "custom-${System.currentTimeMillis()}",
                        name = trimmedName,
                        baseUrl = trimmedUrl,
                    )
                    graph.settings.save(
                        graph.settings.current.copy(
                            skillSources = graph.settings.current.skillSources + source,
                            selectedSkillSourceId = source.id,
                        ),
                    )
                    name = ""
                    url = ""
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
        ) { Text(stringResource(R.string.skill_store_add_button)) }
    }
}
