package dev.xiaoman.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.ChatItem
import dev.xiaoman.app.session.contentText
import dev.xiaoman.app.speech.Speaker
import dev.xiaoman.hands.XiaomanAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(graph: AppGraph, speaker: Speaker, onOpenSettings: () -> Unit) {
    val session = graph.session
    val items by session.items.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val question by session.question.collectAsStateWithLifecycle()
    val approval by session.approval.collectAsStateWithLifecycle()
    val pendingSkill by session.pendingSkill.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val speaking by speaker.speaking.collectAsStateWithLifecycle()
    // Only replies that arrive while watching are spoken, so switching chats stays quiet.
    var seenCount by remember { mutableIntStateOf(items.size) }
    LaunchedEffect(items, settings.speakReplies) {
        val fresh = items.drop(seenCount)
        seenCount = items.size
        if (settings.speakReplies && fresh.size == 1) {
            (fresh.first() as? ChatItem.Assistant)?.let { speaker.speak(it.text) }
        }
    }
    val conversations by session.conversations.collectAsStateWithLifecycle()
    val currentConversationId by session.currentConversationId.collectAsStateWithLifecycle()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    // A search hit scrolls to this row once its conversation is on screen, then flashes it.
    var jumpTo by remember { mutableStateOf<Long?>(null) }
    var highlightId by remember { mutableStateOf<Long?>(null) }
    var actionItem by remember { mutableStateOf<ChatItem?>(null) }
    val service by XiaomanAccessibilityService.instance.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val notificationsGranted = rememberNotificationPermission()
    var draft by rememberSaveable { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<PickedAttachment>>(emptyList()) }
    val scope = rememberCoroutineScope()
    // A cold start restores focus to the input, which pops the IME; drop it once on startup.
    // The guard is disarmed the moment the user presses the input, so their own first tap opens
    // the keyboard normally instead of being mistaken for the startup focus.
    val inputInteractions = remember { MutableInteractionSource() }
    var startupImeGuard by remember { mutableStateOf(true) }
    LaunchedEffect(inputInteractions) {
        inputInteractions.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) startupImeGuard = false
        }
    }
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeOpen) {
        if (imeOpen && startupImeGuard) {
            startupImeGuard = false
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readAttachment(context, uri) }
            if (attachments.size < MAX_ATTACHMENTS) attachments = attachments + picked
        }
    }
    val rows = remember(items) { chatTimeline(items) }
    val avatar = rememberFileImage(settings.avatarPath)
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty() && jumpTo == null) listState.animateScrollToItem(rows.lastIndex)
    }
    LaunchedEffect(rows, jumpTo) {
        val target = jumpTo ?: return@LaunchedEffect
        val index = rows.indexOfFirst { row ->
            row.id == target || (row is TimelineRow.Process && row.entries.any { it.id == target })
        }
        if (index < 0) {
            jumpTo = null
            return@LaunchedEffect
        }
        listState.scrollToItem(index)
        jumpTo = null
        highlightId = target
        delay(1600)
        highlightId = null
    }
    LaunchedEffect(approval?.id) {
        if (approval != null) { focus.clearFocus(); keyboard?.hide() }
    }
    if (showSearch) {
        ChatSearchScreen(
            session = session,
            onBack = { showSearch = false },
            onOpen = { hit ->
                if (hit.conversationId != currentConversationId) session.switchConversation(hit.conversationId)
                jumpTo = hit.itemId
                showSearch = false
            },
        )
        return
    }
    if (showHistory) {
        ChatHistory(
            conversations = conversations,
            currentId = currentConversationId,
            busy = busy,
            onBack = { showHistory = false },
            onNew = { if (session.newConversation()) showHistory = false },
            onOpen = { if (session.switchConversation(it)) showHistory = false },
            onDelete = { session.deleteConversation(it) },
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        ChatHeader(modelsReady = settings.configured, deviceReady = service != null, busy = busy,
            attention = approval != null || question != null,
            assistantName = settings.assistantName, avatarPath = settings.avatarPath,
            onHistory = { showHistory = true },
            onSearch = { showSearch = true })
        if (service == null) {
            Banner(stringResource(R.string.banner_accessibility, settings.assistantName), stringResource(R.string.banner_accessibility_action)) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (!settings.configured) Banner(stringResource(R.string.banner_models), stringResource(R.string.banner_models_action), onOpenSettings)
        if (!notificationsGranted.granted) Banner(stringResource(R.string.banner_notifications), stringResource(R.string.banner_notifications_action), notificationsGranted.request)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val background = rememberFileImage(settings.chatBackgroundPath)
            if (background != null) {
                Image(
                    background,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f)))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (items.isEmpty()) item { ChatWelcome(onExample = { draft = it }, showSuggestions = settings.showTaskSuggestions) }
                items(rows, key = { it.id }) { row ->
                    when (row) {
                        is TimelineRow.Message -> {
                            val highlighted = row.item.id == highlightId
                            Box(
                                Modifier.fillMaxWidth()
                                    .then(
                                        if (highlighted) {
                                            Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp))
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                ChatMessage(row.item, settings.assistantName, avatar, onLongPress = { actionItem = it })
                            }
                        }
                        is TimelineRow.Process -> ProcessCard(row.entries)
                    }
                }
            }
        }
        if (speaking) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.chat_speaking),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { speaker.stop() }) {
                    Text(stringResource(R.string.chat_stop_speaking), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        pendingSkill?.let { action ->
            SkillDraftCard(action, onConfirm = { session.answerSkill(true) }, onCancel = { session.answerSkill(false) })
        }
        if (approval != null) {
            ApprovalCard(approval!!, session)
        } else {
            if (busy) TaskStatus(status ?: stringResource(R.string.chat_thinking), session::stop)
            ChatInput(
                text = draft,
                onChange = { draft = it },
                session = session,
                busy = busy,
                answering = question != null,
                attachments = attachments,
                supportsImages = settings.chatSupportsImages,
                interactionSource = inputInteractions,
                onAttach = { images -> picker.launch(if (images) "image/*" else "*/*") },
                onRemove = { index -> attachments = attachments.filterIndexed { i, _ -> i != index } },
                onSent = {
                    attachments = emptyList()
                    speaker.stop()
                },
            )
        }
    }
    actionItem?.let { item ->
        val text = item.contentText()
        MessageActionsDialog(
            text = text,
            onDismiss = { actionItem = null },
            onQuote = { draft = "> " + text.replace('\n', ' ').take(60) + "\n" },
        )
    }
}

@Composable
private fun ChatInput(
    text: String,
    onChange: (String) -> Unit,
    session: AgentSession,
    busy: Boolean,
    answering: Boolean,
    attachments: List<PickedAttachment>,
    supportsImages: Boolean,
    interactionSource: MutableInteractionSource,
    onAttach: (images: Boolean) -> Unit,
    onRemove: (Int) -> Unit,
    onSent: () -> Unit,
) {
    val view = LocalView.current
    // clearFocus() alone left the field attached to the IME, which re-shows the keyboard as soon
    // as the layout settles; blur it by handing focus to a zero-size sink instead.
    val blurTarget = remember { FocusRequester() }
    val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && (!busy || answering)
    var menu by remember { mutableStateOf(false) }
    fun send() {
        if (!canSend) return
        when (session.submit(text, attachments.map { it.attachment })) {
            AgentSession.Submit.Busy, AgentSession.Submit.Empty -> Unit
            else -> {
                onChange("")
                onSent()
                blurTarget.requestFocus()
                view.post {
                    view.context.getSystemService(InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(view.windowToken, 0)
                }
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (attachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEachIndexed { index, item ->
                        InputChip(
                            selected = false,
                            onClick = { onRemove(index) },
                            label = { Text(item.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            avatar = { Icon(if (item.image) Icons.Outlined.Image else Icons.Outlined.Description, null, Modifier.size(16.dp)) },
                            trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.chat_attachment_remove), Modifier.size(14.dp)) },
                        )
                    }
                }
                if (attachments.any { it.image } && !supportsImages) {
                    Text(
                        stringResource(R.string.chat_image_model_warning),
                        Modifier.padding(bottom = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.padding(bottom = 4.dp)) {
                        Icon(Icons.Outlined.AddCircleOutline, stringResource(R.string.chat_attach), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_attach_image)) },
                            leadingIcon = { Icon(Icons.Outlined.Image, null) },
                            onClick = { menu = false; onAttach(true) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_attach_file)) },
                            leadingIcon = { Icon(Icons.Outlined.Description, null) },
                            onClick = { menu = false; onAttach(false) },
                        )
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = onChange, modifier = Modifier.weight(1f),
                    interactionSource = interactionSource,
                    placeholder = { Text(stringResource(if (answering) R.string.chat_placeholder_answer else R.string.chat_placeholder), style = MaterialTheme.typography.bodyMedium) },
                    shape = MaterialTheme.shapes.large, textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 1, maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(1.dp).focusRequester(blurTarget).focusable())
                FilledIconButton(onClick = { if (busy && !answering) session.stop() else send() },
                    enabled = (busy && !answering) || canSend, modifier = Modifier.padding(bottom = 4.dp).size(48.dp)) {
                    Icon(if (busy && !answering) Icons.Default.Stop else Icons.Default.ArrowUpward,
                        contentDescription = stringResource(if (busy && !answering) R.string.chat_stop else R.string.chat_send))
                }
            }
        }
    }
}

/** How many files one turn may carry. */
private const val MAX_ATTACHMENTS = 5

@Composable
private fun Banner(text: String, action: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

private class NotificationPermission(val granted: Boolean, val request: () -> Unit)

/** POST_NOTIFICATIONS on Android 13+: asked once automatically, then via the banner. */
@Composable
private fun rememberNotificationPermission(): NotificationPermission {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NotificationPermission(true) {}
    val context = LocalContext.current
    fun check() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(check()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = check() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return NotificationPermission(granted) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
