package dev.xiaoman.app.session

import dev.xiaoman.api.Agent
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AgentListener
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalGateway
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.ChatAttachment
import dev.xiaoman.api.SkillAction
import dev.xiaoman.api.UserChannel
import dev.xiaoman.app.RunIds
import dev.xiaoman.hands.MutableStopSignal
import dev.xiaoman.hands.TakeoverControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Texts the session adds to the chat itself; resolved from resources by the app. */
data class SessionTexts(
    val notConfigured: String = "还没有配置模型。",
    val busy: String = "已有任务在运行。",
    val stopped: String = "已停止",
    val approvedOnce: (String) -> String = { "已允许：$it" },
    val approvedTask: (String) -> String = { "本次任务都允许：$it" },
    val denied: (String) -> String = { "已拒绝：$it" },
    /** Sent to a pending question when the user only attached files. */
    val sentAttachments: String = "请看这些附件。",
    val attachmentImage: String = "图片",
    val attachmentFile: String = "文件",
    val skillCancelled: String = "已取消技能修改",
)

/** One chat-search match; [conversationId] + [itemId] locate the row to jump to. */
data class ChatSearchHit(
    val conversationId: String,
    val title: String,
    val itemId: Long,
    val text: String,
    val updatedAt: Long,
)

class PendingQuestion(val id: Long, val taskId: String, val question: String, val takeover: Boolean) {
    internal val answer = CompletableDeferred<String>()
}

class PendingApproval(val id: Long, val request: ApprovalRequest) {
    internal val answer = CompletableDeferred<ApprovalAnswer>()
}

/**
 * The one conversation with the agent: runs chat turns and phone tasks one at a time in [scope],
 * keeps the chat list, and is the agent's [UserChannel] (the next chat input answers a question)
 * and [ApprovalGateway] (the approval card / notification answers it).
 *
 * [AgentService][dev.xiaoman.app.AgentService] keeps the process in the foreground while [busy].
 *
 * Every phone task ends with a terminal event in the trace: when the agent cannot run (not
 * configured, busy, crashed, cancelled) the session emits `Failed` + `PhoneTaskFinished` itself.
 */
class AgentSession(
    private val scope: CoroutineScope,
    private val agent: () -> Agent?,
    private val runIds: RunIds,
    val stopSignal: MutableStopSignal,
    /** Shared with the accessibility device: a user takeover pauses every queued action. */
    val takeover: TakeoverControl = TakeoverControl(),
    private val trace: AgentListener,
    private val onTaskEnd: (String) -> Unit = {},
    private val texts: SessionTexts = SessionTexts(),
    private val ready: suspend () -> Unit = {},
    private val store: ConversationStore? = null,
    /** Writes or deletes one skill after the user confirms; returns ok + message for the chat. */
    private val applySkill: suspend (SkillAction) -> Pair<Boolean, String> = { false to "当前版本不支持修改技能" },
) : UserChannel, ApprovalGateway {

    private val ids = AtomicLong()
    private val running = AtomicBoolean(false)

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())

    /** Saved conversations, newest first, for the history list. */
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    private val _currentConversationId = MutableStateFlow(newConversationId())

    /** Id of the conversation shown now; changes on new / switch / delete. */
    val currentConversationId: StateFlow<String> = _currentConversationId.asStateFlow()

    private val _incognito = MutableStateFlow(false)

    /** True while chats are private: nothing is written to disk and leaving the mode drops the rows. */
    val incognito: StateFlow<Boolean> = _incognito.asStateFlow()

    /** Ids created while incognito, newest last; leaving the mode deletes every one of them. */
    private val incognitoChats = LinkedHashSet<String>()

    private val _incognitoChatCount = MutableStateFlow(0)

    /** How many private conversations leaving incognito will delete; shown in the confirm dialog. */
    val incognitoChatCount: StateFlow<Int> = _incognitoChatCount.asStateFlow()

    /** Chat to come back to when incognito ends; null when it started from an unsaved, empty chat. */
    private var chatBeforeIncognito: String? = null

    /** External `yunhu://chat-add` conversations opened in this process, keyed by `type:id`. */
    private val externalChats = mutableMapOf<String, String>()

    private val saveMutex = Mutex()

    init {
        // Trash older than 30 days goes away at startup; no periodic job is needed for that.
        runCatching { store?.purgeTrash() }
        restoreConversation()
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)

    /** Short text of the current step for the overlay and the notification. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _question = MutableStateFlow<PendingQuestion?>(null)
    val question: StateFlow<PendingQuestion?> = _question.asStateFlow()

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval.asStateFlow()

    private val _pendingSkill = MutableStateFlow<SkillAction?>(null)

    /** A skill draft / deletion the model proposed and the user has to confirm in the chat. */
    val pendingSkill: StateFlow<SkillAction?> = _pendingSkill.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /** Phone tasks started but without PhoneTaskFinished yet. */
    private val openRuns = LinkedHashSet<String>()

    /** Trace first, then the UI. */
    val listener = AgentListener { event ->
        try {
            trace.onEvent(event)
        } catch (e: Exception) {
            // A full disk must not stop the task.
        }
        synchronized(openRuns) {
            when (event) {
                is AgentEvent.PhoneTaskStarted -> openRuns += event.taskId
                is AgentEvent.PhoneTaskFinished -> openRuns -= event.taskId
                else -> Unit
            }
        }
        if (event is AgentEvent.PhoneTaskFinished) onTaskEnd(event.taskId)
        if (event is AgentEvent.SkillProposed) _pendingSkill.value = event.action
        StepText.status(event)?.let { _status.value = it }
        StepText.chatItem(event, ids.incrementAndGet())?.let(::add)
        _events.tryEmit(event)
    }

    sealed class Submit {
        data object Answered : Submit()
        data object Started : Submit()
        data object Busy : Submit()
        data object NotConfigured : Submit()
        data object Empty : Submit()
    }

    /** Chat input: answers a pending question, else starts a chat turn. */
    fun submit(text: String): Submit = submit(text, emptyList())

    /** Same as [submit] with attached images / files; empty text is allowed when something is attached. */
    fun submit(text: String, attachments: List<ChatAttachment>): Submit {
        val message = text.trim()
        if (message.isEmpty() && attachments.isEmpty()) return Submit.Empty
        // A skill draft is pending: "确认" / "取消" answer it here instead of starting a chat turn.
        if (_pendingSkill.value != null && attachments.isEmpty()) {
            val answer = skillAnswer(message)
            if (answer != null) {
                add(ChatItem.User(ids.incrementAndGet(), message))
                answerSkill(answer)
                return Submit.Started
            }
        }
        val pending = _question.value
        if (pending != null && pending.answer.complete(message.ifEmpty { texts.sentAttachments })) {
            add(ChatItem.User(ids.incrementAndGet(), displayMessage(message, attachments)))
            return Submit.Answered
        }
        val agent = agent()
        if (agent == null) {
            add(ChatItem.User(ids.incrementAndGet(), displayMessage(message, attachments)))
            add(ChatItem.Error(ids.incrementAndGet(), texts.notConfigured))
            return Submit.NotConfigured
        }
        val started = launch(null) { agent.handle(message, attachments, listener, stopSignal) }
        if (!started) return Submit.Busy
        add(ChatItem.User(ids.incrementAndGet(), displayMessage(message, attachments)))
        return Submit.Started
    }

    /** Confirms or cancels the pending skill action; only a confirmation writes or deletes files. */
    fun answerSkill(confirm: Boolean) {
        val action = _pendingSkill.value ?: return
        _pendingSkill.value = null
        if (!confirm) {
            add(ChatItem.Progress(ids.incrementAndGet(), texts.skillCancelled))
            return
        }
        scope.launch {
            val (ok, message) = try {
                applySkill(action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false to (e.message?.takeIf { it.isNotBlank() } ?: "操作失败")
            }
            add(ChatItem.Progress(ids.incrementAndGet(), message, ok))
        }
    }

    /** True / false when [text] is clearly yes / no, null when it is an ordinary message. */
    private fun skillAnswer(text: String): Boolean? {
        val plain = text.lowercase().replace(Regex("[\\s,，。.!！、~～]"), "")
        if (plain.isEmpty()) return null
        if (skillNo.any { plain.contains(it) }) return false
        return if (skillYes.any { plain.contains(it) }) true else null
    }

    /** Bubble text for a turn: what was typed plus one compact line per attachment. */    private fun displayMessage(text: String, attachments: List<ChatAttachment>): String = buildString {
        if (text.isNotEmpty()) append(text)
        for (a in attachments) {
            if (isNotEmpty()) append('\n')
            val kind = if (a is ChatAttachment.Image) texts.attachmentImage else texts.attachmentFile
            append("【").append(kind).append("】").append(a.name)
        }
    }

    /**
     * Runs one phone task directly (debug bridge). [runId] becomes the task id and trace folder;
     * an invalid or missing one is replaced. Returns false when it could not start; the trace then
     * already holds Failed + PhoneTaskFinished for that run.
     */
    fun runPhoneTask(goal: String, skill: String?, runId: String?): Boolean {
        val id = runId?.takeIf(RunIds::isValid) ?: runIds.generate()
        val agent = agent()
        if (agent == null) {
            fail(id, texts.notConfigured)
            return false
        }
        val started = launch(id) {
            runIds.prime(id)
            agent.runPhoneTask(goal, skill, listener, stopSignal)
        }
        if (!started) fail(id, texts.busy)
        return started
    }

    /** Emits Failed + PhoneTaskFinished for a run that cannot (or could not) complete. */
    fun fail(runId: String, message: String) {
        listener.onEvent(AgentEvent.Failed(runId, message))
        listener.onEvent(AgentEvent.PhoneTaskFinished(runId, false, message, 0))
    }

    /** Overlay Stop / notification Stop / debug STOP: cooperative stop, pending prompts released. */
    fun stop() {
        if (!_busy.value) return
        stopSignal.stop()
        // Wake an action waiting for the user to resume: it must not run after a stop.
        takeover.interrupt()
        _approval.value?.answer?.complete(ApprovalAnswer(approved = false))
        _question.value?.answer?.complete(texts.stopped)
        _status.value = texts.stopped
    }

    fun answerApproval(answer: ApprovalAnswer, approvalId: Long? = null) {
        val pending = _approval.value ?: return
        if (approvalId != null && pending.id != approvalId) return
        pending.answer.complete(answer)
    }

    override suspend fun ask(taskId: String, question: String, takeover: Boolean): String {
        val pending = PendingQuestion(ids.incrementAndGet(), taskId, question, takeover)
        if (stopSignal.stopped) return texts.stopped
        _question.value = pending
        try {
            return pending.answer.await()
        } finally {
            _question.compareAndSet(pending, null)
        }
    }

    override suspend fun request(request: ApprovalRequest): ApprovalAnswer {
        if (stopSignal.stopped) return ApprovalAnswer(approved = false)
        val pending = PendingApproval(ids.incrementAndGet(), request)
        val before = _status.value
        _approval.value = pending
        _status.value = "等待确认：${request.prompt}"
        try {
            val answer = pending.answer.await()
            val line = when {
                !answer.approved -> texts.denied(request.prompt)
                answer.scope == dev.xiaoman.api.ApprovalScope.TASK -> texts.approvedTask(request.prompt)
                else -> texts.approvedOnce(request.prompt)
            }
            add(ChatItem.Progress(ids.incrementAndGet(), line, answer.approved))
            return answer
        } finally {
            _approval.compareAndSet(pending, null)
            if (!stopSignal.stopped) _status.value = before
        }
    }

    /** Starts [block] unless something is running. [runId] is pre-registered as open (debug runs). */
    private fun launch(runId: String?, block: suspend () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        stopSignal.reset()
        takeover.reset()
        if (runId != null) synchronized(openRuns) { openRuns += runId }
        _status.value = null
        _busy.value = true
        scope.launch {
            var failure: String? = null
            try {
                ready()
                block()
            } catch (e: CancellationException) {
                failure = texts.stopped
                throw e
            } catch (e: Exception) {
                failure = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            } finally {
                withContext(NonCancellable) {
                    val open = synchronized(openRuns) { openRuns.toList().also { openRuns.clear() } }
                    open.forEach { fail(it, failure ?: texts.stopped) }
                    if (failure != null && open.isEmpty()) add(ChatItem.Error(ids.incrementAndGet(), failure))
                    _question.value?.answer?.cancel()
                    _approval.value?.answer?.cancel()
                    _question.value = null
                    _approval.value = null
                    _status.value = null
                    running.set(false)
                    _busy.value = false
                }
            }
        }
        return true
    }

    private fun add(item: ChatItem) {
        _items.update { (it + item).takeLast(MAX_ITEMS) }
        persist()
    }

    // ------------------------------------------------------------ conversations

    /** Starts an empty conversation. Refused while a task is running. */
    fun newConversation(): Boolean {
        if (_busy.value) return false
        startEmptyConversation()
        return true
    }

    /** Shows a saved conversation. Refused while a task is running. */
    fun switchConversation(id: String): Boolean {
        if (_busy.value) return false
        // Opening a saved chat ends incognito; every private row is dropped without a trace.
        if (_incognito.value) leaveIncognito()
        if (id == _currentConversationId.value) return false
        val conversation = store?.load(id) ?: return false
        val restored = conversation.items.mapNotNull(::toChatItem).takeLast(MAX_ITEMS)
        _currentConversationId.value = id
        _items.value = restored
        _pendingSkill.value = null
        ids.set(maxOf(ids.get(), restored.maxOfOrNull { it.id } ?: 0L))
        agent()?.forgetHistory()
        return true
    }

    /**
     * Enters or leaves incognito mode. Entering keeps the open chat on disk and continues in a fresh
     * private conversation; leaving deletes every conversation created during the mode (see
     * [incognitoChatCount]) and reopens the previous chat. Refused while a task is running.
     */
    fun setIncognito(enabled: Boolean): Boolean {
        if (_busy.value || enabled == _incognito.value) return false
        if (enabled) {
            chatBeforeIncognito = _currentConversationId.value.takeIf { store?.load(it) != null }
            incognitoChats.clear()
            _incognitoChatCount.value = 0
            _incognito.value = true
            startEmptyConversation()
            return true
        }
        val back = leaveIncognito()
        startEmptyConversation()
        if (back != null) switchConversation(back)
        return true
    }

    /**
     * Deletes every private conversation (files included, in case a save raced) and returns the chat
     * to reopen afterwards. Chats from before incognito are never touched.
     */
    private fun leaveIncognito(): String? {
        incognitoChats.forEach { id ->
            store?.delete(id)
            externalChats.entries.removeAll { it.value == id }
        }
        incognitoChats.clear()
        _incognitoChatCount.value = 0
        _incognito.value = false
        val back = chatBeforeIncognito
        chatBeforeIncognito = null
        return back
    }

    /** Hides a conversation from the main history list (or puts it back). */
    fun archiveConversation(id: String, archived: Boolean) {
        if (_busy.value) return
        store?.let { if (!it.setArchived(id, archived)) return }
        _conversations.update { list -> list.map { if (it.id == id) it.copy(archived = archived) else it } }
    }

    /** Moves a saved conversation to the recycle bin; trashing the open one starts a fresh chat. */
    fun trashConversation(id: String) {
        if (_busy.value) return
        val at = System.currentTimeMillis()
        store?.let { if (!it.trash(id, at)) return }
        _conversations.update { list -> list.map { if (it.id == id) it.copy(deletedAt = at) else it } }
        if (id == _currentConversationId.value) startEmptyConversation()
    }

    /** Takes a conversation out of the recycle bin. */
    fun restoreConversation(id: String) {
        if (_busy.value) return
        store?.let { if (!it.restore(id)) return }
        _conversations.update { list -> list.map { if (it.id == id) it.copy(deletedAt = 0) else it } }
    }

    /** Permanently deletes one conversation (only reachable from the recycle bin). */
    fun deleteConversation(id: String) {
        if (_busy.value) return
        store?.delete(id)
        _conversations.update { list -> list.filterNot { it.id == id } }
        if (id == _currentConversationId.value) startEmptyConversation()
    }

    /** Permanently deletes everything in the recycle bin. */
    fun emptyTrash() {
        if (_busy.value) return
        val trashed = _conversations.value.filter { it.deletedAt > 0 }
        trashed.forEach { store?.delete(it.id) }
        _conversations.update { list -> list.filterNot { it.deletedAt > 0 } }
    }

    /**
     * Opens the local chat a `yunhu://chat-add` link points at: the one already opened for this
     * `type:id` in this process, else a fresh conversation tagged with a visible row.
     */
    fun openExternalChat(typeKey: String, typeLabel: String, id: String): Boolean {
        if (_busy.value) return false
        val key = "$typeKey:$id"
        val existing = externalChats[key]
        if (existing != null && existing == _currentConversationId.value) return true
        if (existing != null && store?.load(existing) != null) return switchConversation(existing)
        if (!newConversation()) return false
        externalChats[key] = _currentConversationId.value
        // Not persisted on its own: it only becomes part of the chat once the user writes a message.
        _items.update { it + ChatItem.Progress(ids.incrementAndGet(), "已打开${typeLabel}会话 $id") }
        return true
    }

    private fun startEmptyConversation() {
        _currentConversationId.value = newConversationId()
        if (_incognito.value) {
            // Every chat opened during incognito is remembered so leaving the mode clears all of them.
            incognitoChats += _currentConversationId.value
            _incognitoChatCount.value = incognitoChats.size
        }
        _items.value = emptyList()
        _pendingSkill.value = null
        agent()?.forgetHistory()
    }

    private fun restoreConversation() {
        val store = store ?: return
        val all = runCatching { store.summaries() }.getOrDefault(emptyList())
        _conversations.value = all
        val latest = all.firstOrNull { it.deletedAt == 0L && !it.archived }?.let { store.load(it.id) } ?: return
        _items.value = latest.items.mapNotNull(::toChatItem).takeLast(MAX_ITEMS)
        _currentConversationId.value = latest.id
        ids.set(maxOf(ids.get(), latest.items.maxOfOrNull { it.id } ?: 0L))
    }

    private fun persist() {
        val store = store ?: return
        // Incognito chats exist in memory only; nothing may reach the disk.
        if (_incognito.value) return
        val items = _items.value
        if (items.isEmpty()) return
        val conversation = StoredConversation(
            id = _currentConversationId.value,
            title = titleOf(items),
            updatedAt = System.currentTimeMillis(),
            items = items.map(::toStored),
        )
        // Keep archive / trash flags: a new message in an archived chat must not bring it back.
        val known = _conversations.value.firstOrNull { it.id == conversation.id }
        _conversations.update { list ->
            (list.filterNot { it.id == conversation.id } +
                ConversationSummary(
                    conversation.id,
                    conversation.title,
                    conversation.updatedAt,
                    previewOf(items),
                    items.size,
                    archived = known?.archived ?: false,
                    deletedAt = known?.deletedAt ?: 0,
                ))
                .sortedByDescending { it.updatedAt }
        }
        scope.launch(Dispatchers.IO) {
            saveMutex.withLock { store.save(conversation) }
        }
    }

    private fun titleOf(items: List<ChatItem>): String =
        items.filterIsInstance<ChatItem.User>().firstOrNull()?.text?.trim()?.take(20)?.ifBlank { null } ?: "新对话"

    /**
     * Case-insensitive scan of the open conversation, plus every saved one when [all] is set.
     * The current chat is read from memory so unsaved-in-flight rows are searchable too.
     */
    suspend fun searchConversations(query: String, all: Boolean): List<ChatSearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val currentId = _currentConversationId.value
        val current = _items.value
        val hits = mutableListOf<ChatSearchHit>()
        current.forEach { item ->
            val text = item.contentText()
            if (text.contains(q, ignoreCase = true)) {
                hits += ChatSearchHit(currentId, titleOf(current), item.id, text, System.currentTimeMillis())
            }
        }
        val saved = store
        if (all && saved != null) {
            val others = withContext(Dispatchers.IO) {
                saved.summaries().filter { it.deletedAt == 0L }.mapNotNull { summary ->
                    if (summary.id == currentId) null else saved.load(summary.id)
                }
            }
            others.forEach { conversation ->
                conversation.items.forEach { item ->
                    if (item.text.contains(q, ignoreCase = true)) {
                        hits += ChatSearchHit(conversation.id, conversation.title, item.id, item.text, conversation.updatedAt)
                    }
                }
            }
        }
        return hits.sortedByDescending { it.updatedAt }
    }

    private fun previewOf(items: List<ChatItem>): String =
        items.lastOrNull { it is ChatItem.User || it is ChatItem.Assistant }
            ?.let { if (it is ChatItem.User) it.text else (it as ChatItem.Assistant).text }
            ?.replace('\n', ' ')?.take(60).orEmpty()

    private fun toStored(item: ChatItem): StoredItem = when (item) {
        is ChatItem.User -> StoredItem(item.id, "user", item.text, at = item.at)
        is ChatItem.Assistant -> StoredItem(item.id, "assistant", item.text, at = item.at)
        is ChatItem.Progress -> StoredItem(item.id, "progress", item.text, item.ok, at = item.at)
        is ChatItem.Question -> StoredItem(item.id, "question", item.text, takeover = item.takeover, at = item.at)
        is ChatItem.Error -> StoredItem(item.id, "error", item.text, at = item.at)
    }

    private fun toChatItem(item: StoredItem): ChatItem? = when (item.kind) {
        "user" -> ChatItem.User(item.id, item.text, item.at)
        "assistant" -> ChatItem.Assistant(item.id, item.text, item.at)
        "progress" -> ChatItem.Progress(item.id, item.text, item.ok, item.at)
        "question" -> ChatItem.Question(item.id, item.text, item.takeover, item.at)
        "error" -> ChatItem.Error(item.id, item.text, item.at)
        else -> null
    }

    private fun newConversationId() = "chat-${System.currentTimeMillis()}"

    companion object {
        const val MAX_ITEMS = 500

        private val skillYes = listOf("确认", "确定", "可以", "同意", "添加", "保存", "删除", "好的", "好", "是的", "嗯", "ok", "yes", "sure")
        private val skillNo = listOf("取消", "算了", "不要", "不用", "别", "不", "no")
    }
}
