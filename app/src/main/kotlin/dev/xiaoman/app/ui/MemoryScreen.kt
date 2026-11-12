package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.xiaoman.api.MemoryFolder
import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.memory.BasicMemoryFormat
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A row of the memory list: every note when the query is blank, search hits otherwise. */
data class MemoryRow(val permalink: String, val title: String, val detail: String)

data class MemoryState(
    val query: String = "",
    val rows: List<MemoryRow> = emptyList(),
    val folders: List<MemoryFolder> = emptyList(),
    val notes: List<MemoryRow> = emptyList(),
    val currentFolder: String? = null,
    val allFolders: List<String> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** The opened note, rendered as Markdown text. */
    val open: Note? = null,
    val openMarkdown: String = "",
    val openMissing: Boolean = false,
)

@OptIn(FlowPreview::class)
class MemoryViewModel(private val memory: MemoryStore) : ViewModel() {
    private val _state = MutableStateFlow(MemoryState())
    val state: StateFlow<MemoryState> = _state.asStateFlow()
    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch { query.debounce(250).collect { refresh() } }
    }

    fun setQuery(text: String) {
        _state.update { it.copy(query = text) }
        query.value = text
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val q = _state.value.query.trim()
            val folder = _state.value.currentFolder
            runCatching {
                val all = collectFolders(null)
                if (q.isEmpty()) {
                    val folders = memory.listFolders(folder)
                    val notes = memory.list(folder)
                        .filter { note -> note.permalink.substringBeforeLast('/', "") == (folder ?: "") }
                        .sortedByDescending { it.updatedAtMillis }
                        .map { MemoryRow(it.permalink, it.title, it.permalink) }
                    _state.update { it.copy(rows = emptyList(), folders = folders, notes = notes, allFolders = all, loading = false) }
                } else {
                    val rows = memory.search(q, limit = 50).map { MemoryRow(it.permalink, it.title, it.snippet.replace('\n', ' ')) }
                    _state.update { it.copy(rows = rows, folders = emptyList(), notes = emptyList(), allFolders = all, loading = false) }
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "读取失败") }
            }
        }
    }

    private suspend fun collectFolders(parent: String?): List<String> {
        val out = mutableListOf<String>()
        for (folder in memory.listFolders(parent)) {
            out += folder.path
            out += collectFolders(folder.path)
        }
        return out
    }

    fun openFolder(path: String) {
        _state.update { it.copy(currentFolder = path, query = "") }
        query.value = ""
        refresh()
    }

    fun openRoot() {
        _state.update { it.copy(currentFolder = null, query = "") }
        query.value = ""
        refresh()
    }

    fun goUp() {
        val folder = _state.value.currentFolder ?: return
        _state.update { it.copy(currentFolder = folder.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }) }
        refresh()
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val parent = _state.value.currentFolder
            val path = if (parent.isNullOrEmpty()) name.trim() else "$parent/${name.trim()}"
            runCatching { memory.createFolder(path) }
                .onSuccess { created ->
                    if (!created) _state.update { it.copy(error = "同名文件夹已存在") }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "新建失败") } }
        }
    }

    fun renameFolder(path: String, name: String) {
        viewModelScope.launch {
            runCatching { memory.renameFolder(path, name) }
                .onSuccess { renamed ->
                    if (!renamed) {
                        _state.update { it.copy(error = "重命名失败，名称可能已存在") }
                    } else {
                        val newPath = listOfNotNull(path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }, name.trim())
                            .joinToString("/")
                        updateCurrentAfterMove(path, newPath)
                    }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "重命名失败") } }
        }
    }

    fun moveFolder(path: String, parent: String?) {
        viewModelScope.launch {
            runCatching { memory.moveFolder(path, parent) }
                .onSuccess { moved ->
                    if (!moved) {
                        _state.update { it.copy(error = "移动失败，目标位置已有同名文件夹") }
                    } else {
                        val newPath = listOfNotNull(parent?.takeIf { it.isNotBlank() }, path.substringAfterLast('/')).joinToString("/")
                        updateCurrentAfterMove(path, newPath)
                    }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "移动失败") } }
        }
    }

    private fun updateCurrentAfterMove(old: String, new: String) {
        val current = _state.value.currentFolder ?: return
        if (current == old) {
            _state.update { it.copy(currentFolder = new) }
        } else if (current.startsWith("$old/")) {
            _state.update { it.copy(currentFolder = new + current.removePrefix(old)) }
        }
    }

    fun deleteFolder(path: String) {
        viewModelScope.launch {
            runCatching { memory.deleteFolder(path) }
                .onSuccess { deleted ->
                    if (!deleted) _state.update { it.copy(error = "删除失败") }
                    val current = _state.value.currentFolder
                    if (current != null && (current == path || current.startsWith("$path/"))) {
                        _state.update { it.copy(currentFolder = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }) }
                    }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "删除失败") } }
        }
    }

    fun moveNote(permalink: String, target: String?) {
        viewModelScope.launch {
            runCatching { memory.moveNote(permalink, target) }
                .onSuccess { moved ->
                    if (moved == null) _state.update { it.copy(error = "笔记不存在") }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "移动失败") } }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun open(permalink: String) {
        viewModelScope.launch {
            val note = runCatching { memory.read(permalink) }.getOrNull()
            _state.update { it.copy(open = note, openMarkdown = note?.let(BasicMemoryFormat::render).orEmpty(), openMissing = note == null) }
        }
    }

    fun close() = _state.update { it.copy(open = null, openMarkdown = "", openMissing = false) }

    fun delete(permalink: String) {
        viewModelScope.launch {
            runCatching { memory.delete(permalink) }
            close()
            refresh()
        }
    }
}

@Composable
fun MemoryScreen(graph: AppGraph) {
    val vm = viewModel { MemoryViewModel(graph.memory) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.error) {
        state.error?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            vm.clearError()
        }
    }
    val note = state.open
    if (note != null) {
        NoteView(
            note,
            state.openMarkdown,
            onBack = vm::close,
            onDelete = { vm.delete(note.permalink) },
            onMove = { target ->
                vm.moveNote(note.permalink, target)
                vm.close()
            },
            folders = state.allFolders,
        )
    } else {
        MemoryList(state, vm)
    }
}

@Composable
private fun MemoryList(state: MemoryState, vm: MemoryViewModel) {
    LaunchedEffect(Unit) { vm.refresh() }
    var searchOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<MemoryFolder?>(null) }
    var moving by remember { mutableStateOf<MemoryFolder?>(null) }
    var deleting by remember { mutableStateOf<MemoryFolder?>(null) }
    var movingNote by remember { mutableStateOf<MemoryRow?>(null) }
    val dark = isSystemInDarkTheme()
    val gradient = if (dark) {
        listOf(Color(0xFF15243D), Color(0xFF111A27), Color(0xFF0E141C))
    } else {
        listOf(Color(0xFFBFD6FF), Color(0xFFE8F0FF), Color(0xFFF7FAFF))
    }
    val cardColor = if (dark) MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.88f)

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(gradient))) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 20.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.nav_memory),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.memory_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) vm.setQuery("") }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.memory_search_hint))
                }
                IconButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.memory_new_folder))
                }
            }

            if (searchOpen || state.query.isNotBlank()) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.memory_search_hint)) },
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.memory_all),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (state.currentFolder == null) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { vm.openRoot() },
                )
                val segments = state.currentFolder?.split('/').orEmpty()
                segments.forEachIndexed { index, segment ->
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val path = segments.take(index + 1).joinToString("/")
                    Text(
                        segment,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (index == segments.lastIndex) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { vm.openFolder(path) },
                    )
                }
            }

            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp))

            if (!state.loading && state.query.isBlank() && state.folders.isEmpty() && state.notes.isEmpty()) {
                Text(
                    stringResource(if (state.currentFolder == null) R.string.memory_empty else R.string.memory_empty_folder),
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.loading && state.query.isNotBlank() && state.rows.isEmpty()) {
                Text(
                    stringResource(R.string.memory_no_hits),
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.query.isBlank()) {
                    items(state.folders, key = { "f-${it.path}" }, span = { GridItemSpan(1) }) { folder ->
                        FolderCard(
                            folder,
                            cardColor,
                            onOpen = { vm.openFolder(folder.path) },
                            onRename = { renaming = folder },
                            onMove = { moving = folder },
                            onDelete = { deleting = folder },
                        )
                    }
                    if (state.notes.isNotEmpty()) {
                        item(span = { GridItemSpan(2) }) {
                            Text(
                                stringResource(R.string.memory_notes),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        items(state.notes, key = { "n-${it.permalink}" }, span = { GridItemSpan(2) }) { note ->
                            NoteRow(note, cardColor, onOpen = { vm.open(note.permalink) }, onMove = { movingNote = note })
                        }
                    }
                } else {
                    items(state.rows, key = { "s-${it.permalink}" }, span = { GridItemSpan(2) }) { row ->
                        NoteRow(row, cardColor, onOpen = { vm.open(row.permalink) }, onMove = { movingNote = row })
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.memory_new_folder),
            initial = "",
            confirm = stringResource(R.string.memory_create),
            onDismiss = { creating = false },
            onConfirm = { name -> creating = false; vm.createFolder(name) },
        )
    }
    renaming?.let { folder ->
        NameDialog(
            title = stringResource(R.string.memory_rename_folder),
            initial = folder.name,
            confirm = stringResource(R.string.settings_save),
            onDismiss = { renaming = null },
            onConfirm = { name -> renaming = null; vm.renameFolder(folder.path, name) },
        )
    }
    moving?.let { folder ->
        FolderPickerDialog(
            title = stringResource(R.string.memory_move_to),
            options = state.allFolders.filter { it != folder.path && !it.startsWith("${folder.path}/") },
            allowRoot = true,
            onDismiss = { moving = null },
            onPick = { target -> moving = null; vm.moveFolder(folder.path, target) },
        )
    }
    movingNote?.let { row ->
        val parent = row.permalink.substringBeforeLast('/', "")
        FolderPickerDialog(
            title = stringResource(R.string.memory_move_note),
            options = state.allFolders.filter { it != parent },
            allowRoot = parent.isNotEmpty(),
            onDismiss = { movingNote = null },
            onPick = { target -> movingNote = null; vm.moveNote(row.permalink, target) },
        )
    }
    deleting?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.memory_delete_folder_title)) },
            text = { Text(stringResource(R.string.memory_delete_folder_message, folder.name)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; vm.deleteFolder(folder.path) }) {
                    Text(stringResource(R.string.memory_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun FolderCard(
    folder: MemoryFolder,
    cardColor: Color,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, top = 14.dp, end = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.memory_rename)) }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.memory_move)) }, leadingIcon = { Icon(Icons.Filled.Folder, null) }, onClick = { menu = false; onMove() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.memory_delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Text(
                folder.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                stringResource(R.string.memory_items, folder.noteCount + folder.childCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoteRow(row: MemoryRow, cardColor: Color, onOpen: () -> Unit, onMove: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onMove, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.memory_move_note), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.memory_folder_name)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.trim().isNotEmpty() && value.trim() != initial,
                onClick = { onConfirm(value.trim()) },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FolderPickerDialog(
    title: String,
    options: List<String>,
    allowRoot: Boolean,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (allowRoot) {
                    Text(
                        stringResource(R.string.memory_root),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                }
                options.forEach { path ->
                    Text(
                        path,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(path) }.padding(vertical = 12.dp),
                    )
                }
                if (options.isEmpty() && !allowRoot) {
                    Text(stringResource(R.string.memory_empty_folder), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NoteView(
    note: Note,
    markdown: String,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onMove: (String?) -> Unit,
    folders: List<String>,
) {
    var confirm by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(note.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = { moving = true }) { Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.memory_move_note)) }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.memory_delete)) }
        }
        SelectionContainer {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(markdown, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.memory_delete_title)) },
            text = { Text(stringResource(R.string.memory_delete_message, note.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDelete()
                }) { Text(stringResource(R.string.memory_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (moving) {
        FolderPickerDialog(
            title = stringResource(R.string.memory_move_note),
            options = folders.filter { it != note.permalink.substringBeforeLast('/', "") },
            allowRoot = note.permalink.contains('/'),
            onDismiss = { moving = false },
            onPick = { target -> moving = false; onMove(target) },
        )
    }
}
