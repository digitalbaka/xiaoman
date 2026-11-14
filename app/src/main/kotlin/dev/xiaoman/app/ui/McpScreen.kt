package dev.xiaoman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.api.McpConfirmPolicy
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.mcp.McpRegistry
import dev.xiaoman.app.settings.McpBuiltin
import dev.xiaoman.app.settings.McpBuiltins
import dev.xiaoman.app.settings.McpCustomServer
import dev.xiaoman.app.settings.McpServiceConfig
import dev.xiaoman.app.settings.McpSettings
import kotlinx.coroutines.launch

/** Settings → 外部服务：built-in MCP switches, user servers, confirmation policy, test. */
@Composable
fun McpScreen(graph: AppGraph, onBack: () -> Unit) {
    val store = graph.settings
    val settings by store.settings.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(store.current.mcp.normalized()) }
    var saved by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val scope = rememberCoroutineScope()

    fun update(next: McpSettings) {
        draft = next
        saved = false
        hint = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("外部服务", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已启用 ${draft.enabledCount} 个",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                val recommended = McpBuiltins.recommendedIds.toSet()
                                update(
                                    draft.copy(
                                        services = McpBuiltins.all.map { builtin ->
                                            draft.config(builtin.id).let { c ->
                                                if (builtin.id in recommended) c.copy(enabled = true) else c
                                            }
                                        },
                                    ),
                                )
                                hint = "已打开推荐组合，填写 Key 后点「测试连接」验证"
                            }) { Text("推荐组合") }
                        }
                        Text(
                            "打开后，小满Extra就能在聊天里调用这些服务；密钥只保存在本机。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        hint?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            McpBuiltins.groups.forEach { group ->
                item { Text(group, style = MaterialTheme.typography.titleMedium) }
                item {
                    Card {
                        Column {
                            McpBuiltins.all.filter { it.group == group }.forEachIndexed { index, builtin ->
                                if (index > 0) HorizontalDivider()
                                BuiltinRow(
                                    builtin = builtin,
                                    config = draft.config(builtin.id),
                                    onChange = { config ->
                                        update(
                                            draft.copy(
                                                services = McpBuiltins.all.map {
                                                    if (it.id == builtin.id) config else draft.config(it.id)
                                                },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item { Text("其他 MCP 服务", style = MaterialTheme.typography.titleMedium) }
            if (draft.custom.isEmpty()) {
                item {
                    Text(
                        "可以添加任意支持 Streamable HTTP 的 MCP 服务地址。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(draft.custom, key = { it.id }) { server ->
                CustomServerCard(
                    server = server,
                    onChange = { changed ->
                        update(draft.copy(custom = draft.custom.map { if (it.id == changed.id) changed else it }))
                    },
                    onDelete = { update(draft.copy(custom = draft.custom.filter { it.id != server.id })) },
                )
            }
            item {
                TextButton(onClick = { update(draft.copy(custom = draft.custom + McpCustomServer())) }) {
                    Text("+ 添加服务")
                }
            }

            item { Text("调用前确认", style = MaterialTheme.typography.titleMedium) }
            item {
                Card {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        McpConfirmPolicy.entries.forEach { policy ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = draft.confirmPolicy == policy,
                                    onClick = { update(draft.copy(confirmPolicy = policy)) },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(policyLabel(policy))
                                    Text(
                                        policyHint(policy),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = !testing,
                        onClick = {
                            val normalized = draft.normalized()
                            store.save(store.current.copy(mcp = normalized))
                            draft = normalized
                            saved = true
                        },
                    ) { Text("保存") }
                    OutlinedButton(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            results = null
                            scope.launch {
                                results = testConnections(draft.normalized())
                                testing = false
                            }
                        },
                    ) { Text(if (testing) "测试中…" else "测试连接") }
                    if (saved) {
                        Text("已保存", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    results?.let { list ->
        AlertDialog(
            onDismissRequest = { results = null },
            title = { Text("连接测试") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { (name, message) ->
                        Column {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (message.startsWith("连接成功")) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { results = null }) { Text("关闭") } },
        )
    }
}

private suspend fun testConnections(mcp: McpSettings): List<Pair<String, String>> {
    val targets = buildList {
        mcp.enabledBuiltins.forEach { (builtin, config) -> add(builtin.title to McpRegistry.connection(builtin, config)) }
        mcp.custom.filter { it.usable }.forEach { add(it.name.trim().ifEmpty { "自定义服务" } to McpRegistry.connection(it)) }
    }
    if (targets.isEmpty()) return listOf("未启用服务" to "先打开至少一个开关，或添加一个自定义服务")
    return targets.map { (name, connection) ->
        name to try {
            val tools = connection.listTools()
            "连接成功，发现 ${tools.size} 个工具"
        } catch (e: Exception) {
            "失败：${e.message ?: e.javaClass.simpleName}"
        }
    }
}

@Composable
private fun BuiltinRow(builtin: McpBuiltin, config: McpServiceConfig, onChange: (McpServiceConfig) -> Unit) {
    var showKey by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(builtin.title, style = MaterialTheme.typography.bodyLarge)
                    if (!builtin.needsKey) {
                        Text(
                            "免 Key",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    builtin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = config.enabled, onCheckedChange = { onChange(config.copy(enabled = it)) })
        }
        if (config.enabled) {
            if (builtin.needsKey) {
                OutlinedTextField(
                    value = config.apiKey,
                    onValueChange = { onChange(config.copy(apiKey = it)) },
                    label = { Text("API Key") },
                    placeholder = { Text("必填，只保存在本机") },
                    supportingText = { Text(builtin.keyHint.ifEmpty { "到服务方开放平台申请 Key" }) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "隐藏 Key" else "显示 Key",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            OutlinedTextField(
                value = config.url,
                onValueChange = { onChange(config.copy(url = it)) },
                label = { Text("服务地址（可选）") },
                placeholder = { Text(builtin.defaultUrl) },
                supportingText = { Text("留空使用默认地址；服务方换了地址时可在这里覆盖") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CustomServerCard(server: McpCustomServer, onChange: (McpCustomServer) -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = server.name,
                    onValueChange = { onChange(server.copy(name = it)) },
                    label = { Text("名称") },
                    placeholder = { Text("例如 公司知识库") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            OutlinedTextField(
                value = server.url,
                onValueChange = { onChange(server.copy(url = it)) },
                label = { Text("地址") },
                placeholder = { Text("https://…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = server.authorization,
                onValueChange = { onChange(server.copy(authorization = it)) },
                label = { Text("Authorization 请求头（可选）") },
                placeholder = { Text("Bearer …") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun policyLabel(policy: McpConfirmPolicy): String = when (policy) {
    McpConfirmPolicy.MUTATING -> "会修改数据时确认"
    McpConfirmPolicy.ALWAYS -> "每次确认"
    McpConfirmPolicy.NEVER -> "从不确认"
}

private fun policyHint(policy: McpConfirmPolicy): String = when (policy) {
    McpConfirmPolicy.MUTATING -> "查询类工具直接执行；下单、发送、删除等改数据的操作先问你"
    McpConfirmPolicy.ALWAYS -> "任何外部服务调用前都在聊天里等你确认"
    McpConfirmPolicy.NEVER -> "全部直接执行，不再询问（请只对信任的服务使用）"
}
