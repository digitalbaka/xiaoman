package dev.xiaoman.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.R
import dev.xiaoman.app.session.ChatItem
import dev.xiaoman.api.SkillAction

@Composable
internal fun ChatHeader(
    modelsReady: Boolean,
    deviceReady: Boolean,
    busy: Boolean,
    attention: Boolean,
    assistantName: String,
    avatarPath: String?,
    onHistory: () -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val avatar = rememberFileImage(avatarPath)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(38.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (avatar != null) {
                    androidx.compose.foundation.Image(
                        avatar,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                } else {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(assistantName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.chat_header_subtitle), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(when {
                attention -> stringResource(R.string.chat_status_waiting)
                busy -> stringResource(R.string.chat_status_busy)
                !modelsReady -> stringResource(R.string.chat_status_setup)
                !deviceReady -> stringResource(R.string.chat_status_no_accessibility)
                else -> stringResource(R.string.chat_status_ready)
            },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.chat_search), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onHistory, modifier = Modifier.padding(start = 2.dp)) {
            Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.chat_history), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ChatWelcome(onExample: (String) -> Unit, showSuggestions: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.chat_welcome_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.chat_welcome_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!showSuggestions) return@Column
        Spacer(Modifier.height(16.dp))
        Example(
            Icons.Outlined.ShoppingBag,
            stringResource(R.string.example_notebook_title),
            stringResource(R.string.example_notebook_detail),
            stringResource(R.string.example_notebook_prompt),
            onExample,
        )
        Example(
            Icons.Outlined.Schedule,
            stringResource(R.string.example_water_title),
            stringResource(R.string.example_water_detail),
            stringResource(R.string.example_water_prompt),
            onExample,
        )
        Example(
            Icons.Outlined.Lightbulb,
            stringResource(R.string.example_memory_title),
            stringResource(R.string.example_memory_detail),
            stringResource(R.string.example_memory_prompt),
            onExample,
        )
        Text(stringResource(R.string.chat_welcome_more), modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Example(icon: ImageVector, title: String, detail: String, prompt: String, onExample: (String) -> Unit) {
    Surface(onClick = { onExample(prompt) }, shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun TaskStatus(status: String, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(status, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onStop) { Text(stringResource(R.string.chat_stop), style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
internal fun ProcessCard(entries: List<ChatItem.Progress>) {
    var expanded by rememberSaveable(entries.first().id) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ListAlt, null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.chat_process_title, entries.size), Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    stringResource(if (expanded) R.string.chat_process_collapse else R.string.chat_process_expand), modifier = Modifier.size(20.dp))
            }
            if (expanded) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { Text(it.text, style = MaterialTheme.typography.bodySmall,
                    color = if (it.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatMessage(
    item: ChatItem,
    assistantName: String = "小满Extra",
    avatar: androidx.compose.ui.graphics.ImageBitmap? = null,
    onLongPress: (ChatItem) -> Unit = {},
) {
    val hold = Modifier.combinedClickable(onClick = {}, onLongClick = { onLongPress(item) }, onLongClickLabel = stringResource(R.string.chat_actions_title))
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp),
                    modifier = Modifier.fillMaxWidth(0.90f)) {
                    Text(item.text, Modifier.then(hold).padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
                BubbleTime(item.at)
            }
        }
        is ChatItem.Assistant -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatar != null) {
                        androidx.compose.foundation.Image(
                            avatar,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                        )
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                    }
                }
                Text(assistantName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Column {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(5.dp, 20.dp, 20.dp, 20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Text(chatRichText(item.text), Modifier.then(hold).padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                BubbleTime(item.at)
            }
        }
        is ChatItem.Question -> NoticeCard(
            stringResource(if (item.takeover) R.string.chat_notice_takeover else R.string.chat_notice_followup),
            item.text,
            false,
        )
        is ChatItem.Error -> NoticeCard(stringResource(R.string.chat_notice_error), item.text, true)
        is ChatItem.Progress -> Unit // handled by ProcessCard
    }
}

/** Small timestamp under a bubble; hidden for rows saved before timestamps existed. */
@Composable
private fun BubbleTime(at: Long) {
    if (at <= 0) return
    Text(
        formatTime(at),
        Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Copy / share / quote actions shown after long-pressing a chat bubble. */
@Composable
internal fun MessageActionsDialog(text: String, onDismiss: () -> Unit, onQuote: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_actions_title)) },
        text = {
            Text(
                text.replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text(stringResource(R.string.chat_copy)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onQuote(); onDismiss() }) { Text(stringResource(R.string.chat_quote)) }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.chat_share)))
                    onDismiss()
                }) { Text(stringResource(R.string.chat_share)) }
            }
        },
    )
}

@Composable
private fun NoticeCard(title: String, body: String, error: Boolean) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(chatRichText(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Skill draft / deletion waiting for the user's confirmation; nothing is written before it. */
@Composable
internal fun SkillDraftCard(action: SkillAction, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val deleting = action is SkillAction.Delete
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (deleting) R.string.skill_delete_title else R.string.skill_draft_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(action.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (action is SkillAction.Save) {
                Text(
                    stringResource(R.string.skill_draft_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        action.markdown,
                        Modifier.fillMaxWidth().heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.skill_delete_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.skill_cancel)) }
                Button(onClick = onConfirm) {
                    Text(stringResource(if (deleting) R.string.skill_confirm_delete else R.string.skill_confirm_add))
                }
            }
        }
    }
}

/** Basic emphasis and lists from model replies, rendered as text rather than raw Markdown markers. */
internal fun chatRichText(raw: String) = buildAnnotatedString {    val text = raw.replace(Regex("(?m)^#{1,6}\\s+"), "").replace(Regex("(?m)^[-*]\\s+"), "• ")
    var position = 0
    Regex("\\*\\*(.+?)\\*\\*").findAll(text).forEach { match ->
        append(text.substring(position, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.groupValues[1]) }
        position = match.range.last + 1
    }
    append(text.substring(position))
}
