package dev.xiaoman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.settings.McpBuiltin
import dev.xiaoman.app.settings.McpBuiltins
import dev.xiaoman.app.settings.McpCustomServer
import dev.xiaoman.app.settings.McpSettings

/** One entry of the MCP store that is added as a free-form server (not in the built-in catalog). */
private data class StoreEntry(
    val name: String,
    val description: String,
    val url: String,
    val needsKey: Boolean,
)

/**
 * Endpoints published by their vendors. They are added as custom servers, so the user can still
 * edit the address or add an authorization header on the external services screen afterwards.
 */
private val storeEntries = listOf(
    StoreEntry("DeepWiki", "按仓库名直接提问，读取 GitHub 项目的文档与结构", "https://mcp.deepwiki.com/mcp", needsKey = false),
    StoreEntry("Context7", "查主流开源库的最新文档和代码示例", "https://mcp.context7.com/mcp", needsKey = false),
    StoreEntry("Exa 搜索", "面向 AI 的网页搜索，返回正文内容", "https://mcp.exa.ai/mcp", needsKey = true),
    StoreEntry("GitHub", "搜索仓库、读取 issue 与 PR", "https://api.githubcopilot.com/mcp/", needsKey = true),
)

/** Settings → MCP 商店：browse the catalog and add a service to 外部服务 with one tap. */
@Composable
fun McpStoreScreen(graph: AppGraph, onBack: () -> Unit, onOpenServices: () -> Unit) {
    val store = graph.settings
    val settings by store.settings.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var added by remember { mutableStateOf<String?>(null) }

    fun save(next: McpSettings) = store.save(store.current.copy(mcp = next.normalized()))

    val keyword = query.trim()
    fun matches(text: String) = keyword.isEmpty() || text.contains(keyword, ignoreCase = true)

    val builtins = McpBuiltins.all.filter { matches(it.title + it.description) }
    val extras = storeEntries.filter { matches(it.name + it.description) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text("MCP 商店", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索服务") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    "添加后会出现在「外部服务」，可以随时开关、填写 Key 或修改地址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            added?.let { name ->
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("已添加「$name」", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "需要 Key 的服务请到外部服务里填写后才能调用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onOpenServices) { Text("去外部服务") }
                        }
                    }
                }
            }
            if (builtins.isEmpty() && extras.isEmpty()) {
                item {
                    Text(
                        "没有找到「$keyword」相关的服务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (builtins.isNotEmpty()) item { Text("官方服务", style = MaterialTheme.typography.titleMedium) }
            items(builtins, key = { it.id }) { builtin ->
                BuiltinStoreCard(
                    builtin = builtin,
                    installed = settings.mcp.isEnabled(builtin.id),
                    onAdd = {
                        val next = settings.mcp.normalized().let { mcp ->
                            mcp.copy(services = mcp.services.map { if (it.id == builtin.id) it.copy(enabled = true) else it })
                        }
                        save(next)
                        added = builtin.title
                    },
                )
            }
            if (extras.isNotEmpty()) item { Text("更多服务", style = MaterialTheme.typography.titleMedium) }
            items(extras, key = { it.url }) { entry ->
                val installed = settings.mcp.custom.any { it.url.trim() == entry.url }
                StoreCard(
                    title = entry.name,
                    description = entry.description,
                    tag = if (entry.needsKey) "需要 Key" else "免 Key",
                    installed = installed,
                    onAdd = {
                        if (!installed) {
                            save(settings.mcp.copy(custom = settings.mcp.custom + McpCustomServer(name = entry.name, url = entry.url)))
                            added = entry.name
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BuiltinStoreCard(builtin: McpBuiltin, installed: Boolean, onAdd: () -> Unit) {
    StoreCard(
        title = builtin.title,
        description = builtin.description,
        tag = if (builtin.needsKey) "需要 Key" else "免 Key",
        installed = installed,
        onAdd = onAdd,
    )
}

@Composable
private fun StoreCard(title: String, description: String, tag: String, installed: Boolean, onAdd: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tag == "免 Key") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = onAdd,
                enabled = !installed,
                modifier = Modifier.align(Alignment.End),
            ) { Text(if (installed) "已添加" else "添加") }
        }
    }
}
