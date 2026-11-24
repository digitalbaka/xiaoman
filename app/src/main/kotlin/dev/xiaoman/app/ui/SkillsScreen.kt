package dev.xiaoman.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.xiaoman.api.InstalledSkillInfo
import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillInstaller
import dev.xiaoman.api.SkillProblem
import dev.xiaoman.api.SkillRepository
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.skills.SkillHubClient
import dev.xiaoman.app.skills.SkillHubSkill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SkillsState(
    val skills: List<Skill> = emptyList(),
    val problems: List<SkillProblem> = emptyList(),
    val installed: List<InstalledSkillInfo> = emptyList(),
    /** `.origin` marker written at install time, keyed by skill name (e.g. `SkillHub · @ns/slug`). */
    val origins: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val installing: Boolean = false,
    val installResult: String? = null,
    val installOk: Boolean = false,
)

data class ShopState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val query: String = "",
    val items: List<SkillHubSkill> = emptyList(),
    val error: String? = null,
    val installing: String? = null,
    val message: String? = null,
    val messageOk: Boolean = false,
)

class SkillsViewModel(
    private val repository: SkillRepository,
    private val installer: SkillInstaller,
    private val skillHub: () -> SkillHubClient,
    private val skillsDir: File,
    private val ready: suspend () -> Unit,
    private val onReloaded: (Int, List<SkillProblem>) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(SkillsState())
    val state: StateFlow<SkillsState> = _state.asStateFlow()

    private val _shop = MutableStateFlow(ShopState())
    val shop: StateFlow<ShopState> = _shop.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            ready()
            val result = repository.reload()
            val installed = runCatching { installer.listInstalled() }.getOrDefault(emptyList())
            val origins = withContext(Dispatchers.IO) {
                installed.mapNotNull { info -> readOrigin(info.name)?.let { info.name to it } }.toMap()
            }
            onReloaded(result.skills.size, result.problems)
            _state.update {
                it.copy(skills = result.skills, problems = result.problems, installed = installed, origins = origins, loading = false)
            }
        }
    }

    fun installFromUrl(url: String) {
        _state.update { it.copy(installing = true, installResult = null) }
        viewModelScope.launch {
            val result = runCatching { installer.installFromUrl(url) }.getOrNull()
            _state.update {
                it.copy(
                    installing = false,
                    installOk = result?.ok == true,
                    installResult = result?.message ?: "安装请求失败",
                )
            }
            if (result?.ok == true) reload()
        }
    }

    fun installZip(bytes: ByteArray, name: String) {
        _state.update { it.copy(installing = true, installResult = null) }
        viewModelScope.launch {
            val result = runCatching { installer.installFromZip(bytes, name) }.getOrNull()
            _state.update {
                it.copy(
                    installing = false,
                    installOk = result?.ok == true,
                    installResult = result?.message ?: "安装请求失败",
                )
            }
            if (result?.ok == true) reload()
        }
    }

    fun setEnabled(skill: InstalledSkillInfo, enabled: Boolean) {
        viewModelScope.launch {
            installer.setEnabled(skill.name, enabled)
            reload()
        }
    }

    fun delete(skill: InstalledSkillInfo) {
        viewModelScope.launch {
            installer.delete(skill.name)
            reload()
        }
    }

    // --- SkillHub store -------------------------------------------------------

    fun openShop() {
        _shop.update { it.copy(open = true, message = null) }
        if (_shop.value.items.isEmpty() && !_shop.value.loading) loadHot()
    }

    fun closeShop() {
        _shop.update { it.copy(open = false) }
    }

    fun searchShop(query: String) {
        _shop.update { it.copy(query = query) }
        if (query.isBlank()) {
            loadHot()
            return
        }
        _shop.update { it.copy(loading = true, error = null, message = null) }
        viewModelScope.launch {
            val loaded = runCatching { skillHub().search(query) }
            loaded.onSuccess { items ->
                _shop.update {
                    it.copy(
                        loading = false,
                        items = items,
                        error = if (items.isEmpty()) "没有找到相关技能，换个关键词试试" else null,
                    )
                }
            }.onFailure { e ->
                _shop.update { it.copy(loading = false, items = emptyList(), error = "搜索失败：${e.message ?: "网络错误"}") }
            }
        }
    }

    private fun loadHot() {
        _shop.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val loaded = runCatching { skillHub().hot() }
            loaded.onSuccess { items ->
                _shop.update {
                    it.copy(
                        loading = false,
                        items = items,
                        error = if (items.isEmpty()) "商店暂时没有返回技能" else null,
                    )
                }
            }.onFailure { e ->
                _shop.update { it.copy(loading = false, items = emptyList(), error = "加载失败：${e.message ?: "网络错误"}") }
            }
        }
    }

    fun installFromShop(item: SkillHubSkill) {
        if (_shop.value.installing != null) return
        _shop.update { it.copy(installing = item.slug, message = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                val bytes = skillHub().download(item.slug)
                installer.installFromZip(bytes, item.slug)
            }
            outcome.onSuccess { result ->
                if (result.ok) {
                    withContext(Dispatchers.IO) {
                        result.installed.forEach { name ->
                            runCatching { File(File(skillsDir, name), ".origin").writeText(item.originLabel()) }
                        }
                    }
                    _shop.update {
                        it.copy(
                            installing = null,
                            messageOk = true,
                            message = "已安装 ${result.installed.joinToString("、")}（${item.originLabel()}）",
                        )
                    }
                    reload()
                } else {
                    _shop.update { it.copy(installing = null, messageOk = false, message = "安装失败：${result.message}") }
                }
            }.onFailure { e ->
                _shop.update { it.copy(installing = null, messageOk = false, message = "安装失败：${e.message ?: "网络错误"}") }
            }
        }
    }

    private fun readOrigin(name: String): String? = runCatching {
        val marker = File(File(skillsDir, name), ".origin")
        if (marker.isFile) marker.readText().trim().takeIf { it.isNotEmpty() } else null
    }.getOrNull()
}

/** A skill shipped in the APK; its card is listed even when the extracted files are missing. */
private data class BuiltinSkill(val name: String, val description: String)

/** Descriptions mirror the frontmatter of `skills/examples/<name>/SKILL.md`. */
private val BUILTIN_SKILLS = listOf(
    BuiltinSkill("ai-news-brief", "每天整理一份 AI 新闻简报：筛选重要动态，按主题归纳，给出要点与来源"),
    BuiltinSkill("daily-quiz", "每天出一道小测验：按主题出题、给出选项与解析，适合碎片时间自测"),
    BuiltinSkill("health-alarms", "用系统时钟设置健康提醒：喝水、久坐起身、按时休息，只新增不改动已有闹钟"),
)

@Composable
fun SkillsScreen(graph: AppGraph, onOpenChat: () -> Unit) {
    val vm = viewModel {
        SkillsViewModel(
            graph.skills,
            graph.skillInstaller,
            graph::skillHubClient,
            graph.skillsDir,
            { graph.startup.await() },
            graph::logSkillProblems,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val shop by vm.shop.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var showInstall by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<InstalledSkillInfo?>(null) }
    var modifyTarget by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<File?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Skill creation / editing goes through the chat: the model answers or drafts in the existing
    // confirm-in-chat flow, so nothing is written here.
    fun sendToAgent(prompt: String) {
        if (graph.session.submit(prompt) == AgentSession.Submit.Busy) {
            Toast.makeText(context, context.getString(R.string.skills_busy), Toast.LENGTH_SHORT).show()
        } else {
            onOpenChat()
        }
    }

    if (shop.open) {
        SkillHubScreen(
            shop = shop,
            installed = state.installed,
            sourceName = settings.selectedSkillSource.name,
            onBack = vm::closeShop,
            onSearch = vm::searchShop,
            onRefresh = { vm.searchShop(shop.query) },
            onInstall = vm::installFromShop,
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.skills_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { sendToAgent(context.getString(R.string.skills_new_prompt)) }) {
                Text(stringResource(R.string.skills_new))
            }
            TextButton(onClick = vm::reload, enabled = !state.loading) {
                Text(stringResource(R.string.skills_reload))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = vm::openShop) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Text(stringResource(R.string.skills_shop_entry), modifier = Modifier.padding(start = 2.dp))
            }
            TextButton(onClick = { showInstall = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.skills_install), modifier = Modifier.padding(start = 2.dp))
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(BUILTIN_SKILLS) { builtin ->
                val file = File(File(graph.bundledSkillsDir, builtin.name), "SKILL.md")
                BuiltinSkillCard(
                    name = builtin.name,
                    description = builtin.description,
                    path = file.path,
                    available = file.isFile,
                    onView = { viewing = file },
                    onModify = { modifyTarget = builtin.name },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 2.dp)) }

            if (state.installed.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.skills_user_skills), style = MaterialTheme.typography.titleMedium)
                }
                items(state.installed.size) { index ->
                    val info = state.installed[index]
                    InstalledSkillCard(
                        info = info,
                        origin = state.origins[info.name],
                        onEnabled = { vm.setEnabled(info, it) },
                        onDelete = { deleting = info },
                        onModify = { modifyTarget = info.name },
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
            } else if (!state.loading) {
                item {
                    Text(
                        stringResource(R.string.skills_no_installed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val installedNames = state.installed.map { it.name }.toSet()
            val builtinNames = BUILTIN_SKILLS.map { it.name }.toSet()
            val bundled = state.skills.filterNot {
                it.source.startsWith(graph.skillsDir.path) || it.name in installedNames || it.name in builtinNames
            }
            if (!state.loading && bundled.isEmpty()) {
                item { Text(stringResource(R.string.skills_empty, graph.skillsDir.path), style = MaterialTheme.typography.bodyMedium) }
            }
            items(bundled, key = { it.name }) { skill ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(skill.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(
                                stringResource(R.string.skills_bundled),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                        if (skill.apps.isNotEmpty()) {
                            Text(stringResource(R.string.skills_apps, skill.apps.joinToString()), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { modifyTarget = skill.name }) {
                                Text(
                                    stringResource(R.string.skills_modify_one_sentence),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
            if (state.problems.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(stringResource(R.string.skills_problems), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                items(state.problems) { p ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.source + (p.line?.let { ":$it" } ?: ""), style = MaterialTheme.typography.labelMedium)
                            Text(p.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    if (showInstall) {
        InstallSkillDialog(
            state = state,
            onDismiss = { showInstall = false },
            onInstallUrl = vm::installFromUrl,
            onPickZip = { uri ->
                scope.launch { vm.installZip(readBytes(context, uri), uri.lastPathSegment ?: "zip") }
            },
        )
    }
    deleting?.let { skill ->
        AppAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.skills_delete_title)) },
            text = { Text(stringResource(R.string.skills_delete_message, skill.name)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; vm.delete(skill) }) {
                    Text(stringResource(R.string.skills_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    modifyTarget?.let { name ->
        ModifySkillDialog(
            name = name,
            onDismiss = { modifyTarget = null },
            onSubmit = { sentence ->
                modifyTarget = null
                sendToAgent(context.getString(R.string.skills_modify_prompt, name, sentence))
            },
        )
    }
    viewing?.let { file ->
        SkillContentDialog(
            name = file.parentFile?.name.orEmpty(),
            file = file,
            onDismiss = { viewing = null },
        )
    }
}

@Composable
private fun BuiltinSkillCard(
    name: String,
    description: String,
    path: String,
    available: Boolean,
    onView: () -> Unit,
    onModify: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.skills_bundled),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.skills_path, path),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!available) {
                Text(
                    stringResource(R.string.skills_builtin_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onView) {
                    Text(stringResource(R.string.skills_view_content), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onModify) {
                    Text(stringResource(R.string.skills_modify_one_sentence), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun InstalledSkillCard(
    info: InstalledSkillInfo,
    origin: String?,
    onEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onModify: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.name, style = MaterialTheme.typography.titleMedium)
                    if (!info.enabled) {
                        Text(
                            "  " + stringResource(R.string.skills_disabled),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                info.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                origin?.let {
                    Text(
                        stringResource(R.string.skills_origin, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = onModify, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(
                        stringResource(R.string.skills_modify_one_sentence),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Switch(checked = info.enabled, onCheckedChange = onEnabled)
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.skills_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SkillHubScreen(
    shop: ShopState,
    installed: List<InstalledSkillInfo>,
    sourceName: String,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onInstall: (SkillHubSkill) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val installedNames = installed.map { it.name }.toSet()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.skills_shop_back)) }
            Text(
                stringResource(R.string.skills_shop_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !shop.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.skills_shop_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onSearch(query) }, enabled = !shop.loading) {
                Text(stringResource(R.string.skills_shop_search))
            }
        }
        if (shop.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        shop.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        shop.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (shop.messageOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shop.items, key = { it.slug + "|" + it.namespace }) { item ->
                val isInstalled = item.slug in installedNames || item.name in installedNames
                val downloadsText = item.downloads?.let { stringResource(R.string.skills_shop_downloads, it) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            item.version?.takeIf { it.isNotBlank() }?.let {
                                Text("v$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(
                            listOfNotNull(
                                item.coordinate,
                                item.source?.takeIf { it.isNotBlank() },
                                downloadsText,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.summary.isNotBlank()) {
                            Text(item.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            if (shop.installing == item.slug) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.skills_shop_installing), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                            } else if (isInstalled) {
                                Text(stringResource(R.string.skills_installed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Button(onClick = { onInstall(item) }, enabled = shop.installing == null) {
                                    Text(stringResource(R.string.skills_shop_install))
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.skills_shop_footer, sourceName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InstallSkillDialog(
    state: SkillsState,
    onDismiss: () -> Unit,
    onInstallUrl: (String) -> Unit,
    onPickZip: (Uri) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPickZip(uri)
    }
    AppDialog(onDismissRequest = { if (!state.installing) onDismiss() }) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), tonalElevation = 4.dp) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.skills_install_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.skills_install_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.skills_install_url)) },
                    placeholder = { Text(stringResource(R.string.skills_install_url_hint)) },
                    singleLine = true,
                    enabled = !state.installing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = url.startsWith("http://") || url.startsWith("https://"),
                        onClick = { onInstallUrl(url.trim()) },
                    ) { Text(stringResource(R.string.skills_install_action)) }
                    OutlinedButton(enabled = !state.installing, onClick = { picker.launch("*/*") }) {
                        Text(stringResource(R.string.skills_pick_zip))
                    }
                }
                if (state.installing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.skills_installing), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.installResult?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.installOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !state.installing) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

private suspend fun readBytes(context: android.content.Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
}

/** Collects the one-sentence change; the sentence is handed to the chat draft flow, never written here. */
@Composable
private fun ModifySkillDialog(name: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var sentence by remember { mutableStateOf("") }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_modify_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.skills_modify_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = sentence,
                    onValueChange = { sentence = it },
                    placeholder = { Text(name) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = sentence.isNotBlank(), onClick = { onSubmit(sentence.trim()) }) {
                Text(stringResource(R.string.skills_modify_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Shows SKILL.md; a missing or unreadable file gets the same readable hint as its card. */
@Composable
private fun SkillContentDialog(name: String, file: File, onDismiss: () -> Unit) {
    var content by remember(file) { mutableStateOf<String?>(null) }
    var loaded by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        content = withContext(Dispatchers.IO) {
            runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        }
        loaded = true
    }
    AppDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 4.dp) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.skills_path, file.path),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    !loaded -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    content == null -> Text(
                        stringResource(R.string.skills_builtin_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(content.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}
