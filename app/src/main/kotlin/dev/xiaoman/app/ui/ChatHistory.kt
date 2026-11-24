package dev.xiaoman.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.R
import dev.xiaoman.app.session.ConversationSummary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** All / archived / recycle bin tabs over the saved conversations. */
private val HISTORY_TABS = listOf(
    R.string.chat_history_tab_all,
    R.string.chat_history_tab_archived,
    R.string.chat_history_tab_trash,
)

/**
 * Saved conversations: switch, start a new one, archive, move to the recycle bin, restore or
 * delete for good from there.
 */
@Composable
internal fun ChatHistory(
    conversations: List<ConversationSummary>,
    currentId: String,
    busy: Boolean,
    incognito: Boolean,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onArchive: (String, Boolean) -> Unit,
    onTrash: (String) -> Unit,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
    onEmptyTrash: () -> Unit,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingTrash by remember { mutableStateOf<ConversationSummary?>(null) }
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    var pendingEmptyTrash by remember { mutableStateOf(false) }
    val active = conversations.filter { it.deletedAt == 0L && !it.archived }
    val archived = conversations.filter { it.deletedAt == 0L && it.archived }
    val trash = conversations.filter { it.deletedAt > 0L }.sortedByDescending { it.deletedAt }
    val shown = when (tabIndex) {
        1 -> archived
        2 -> trash
        else -> active
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) }
            Text(
                stringResource(R.string.chat_history),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (tabIndex == 2 && trash.isNotEmpty()) {
                TextButton(onClick = { pendingEmptyTrash = true }, enabled = !busy) {
                    Text(stringResource(R.string.chat_history_clear_trash))
                }
            } else if (tabIndex != 2) {
                TextButton(onClick = onNew, enabled = !busy) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_history_new))
                }
            }
        }
        TabRow(selectedTabIndex = tabIndex) {
            HISTORY_TABS.forEachIndexed { index, label ->
                Tab(
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    text = { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        if (incognito) {
            Text(
                stringResource(R.string.chat_history_incognito),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        if (busy) {
            Text(
                stringResource(R.string.chat_history_busy),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(
                            when (tabIndex) {
                                1 -> R.string.chat_history_archived_empty
                                2 -> R.string.chat_history_trash_empty
                                else -> R.string.chat_history_empty
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.id }) { conversation ->
                val current = conversation.id == currentId
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !busy && tabIndex != 2) { onOpen(conversation.id) },
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    conversation.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (current) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary) {
                                        Text(
                                            stringResource(R.string.chat_history_current),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            if (conversation.preview.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    conversation.preview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                formatTime(conversation.updatedAt) + " · " + stringResource(R.string.chat_history_count, conversation.count),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            IconButton(onClick = { menuFor = conversation.id }, enabled = !busy) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.chat_history_more),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(expanded = menuFor == conversation.id, onDismissRequest = { menuFor = null }) {
                                when (tabIndex) {
                                    2 -> {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_history_restore)) },
                                            onClick = { menuFor = null; onRestore(conversation.id) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_history_delete_forever)) },
                                            onClick = { menuFor = null; pendingDelete = conversation },
                                        )
                                    }
                                    else -> {
                                        DropdownMenuItem(
                                            text = {
                                                Text(stringResource(if (conversation.archived) R.string.chat_history_unarchive else R.string.chat_history_archive))
                                            },
                                            onClick = { menuFor = null; onArchive(conversation.id, !conversation.archived) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_history_delete)) },
                                            onClick = { menuFor = null; pendingTrash = conversation },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingTrash?.let { conversation ->
        AppAlertDialog(
            onDismissRequest = { pendingTrash = null },
            title = { Text(stringResource(R.string.chat_history_delete_title)) },
            text = { Text(stringResource(R.string.chat_history_delete_message, conversation.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onTrash(conversation.id)
                    pendingTrash = null
                }) { Text(stringResource(R.string.memory_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTrash = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    pendingDelete?.let { conversation ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chat_history_delete_forever_title)) },
            text = { Text(stringResource(R.string.chat_history_delete_forever_message, conversation.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteForever(conversation.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.chat_history_delete_forever)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (pendingEmptyTrash) {
        AppAlertDialog(
            onDismissRequest = { pendingEmptyTrash = false },
            title = { Text(stringResource(R.string.chat_history_clear_trash_title)) },
            text = { Text(stringResource(R.string.chat_history_clear_trash_message, trash.size)) },
            confirmButton = {
                TextButton(onClick = {
                    onEmptyTrash()
                    pendingEmptyTrash = false
                }) { Text(stringResource(R.string.chat_history_clear_trash)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingEmptyTrash = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

internal fun formatTime(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) {
        "HH:mm"
    } else {
        android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd HH:mm")
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}
