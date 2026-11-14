package dev.xiaoman.agent

import dev.xiaoman.api.Agent
import dev.xiaoman.api.AgentConfig
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AgentListener
import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatRole
import dev.xiaoman.api.ChatAttachment
import dev.xiaoman.api.ContentPart
import dev.xiaoman.api.Device
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.McpConfirmPolicy
import dev.xiaoman.api.McpHub
import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.ReminderScheduler
import dev.xiaoman.api.Sentinel
import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillInstaller
import dev.xiaoman.api.SkillRepository
import dev.xiaoman.api.StopSignal
import dev.xiaoman.api.UserChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Message thrown by the hands layer when accessibility is off; mapped to a user-facing hint. */
private const val DEVICE_NOT_CONNECTED = "无障碍服务未开启"

/** Rounds a knowledge skill may spend fetching pages before it must answer. */
private const val TEXT_SKILL_ROUNDS = 4

/** Characters of attached file text the model sees in one turn (per file budgets share it). */
private const val MAX_ATTACHMENT_CHARS = 24_000

/**
 * The Xiaoman agent: chat turns with memory and phone tools ([handle]), and phone tasks run by
 * the operator loop or a skill flow ([runPhoneTask]). See docs/ARCHITECTURE.md.
 *
 * Conversation history is kept in memory, bounded to the last [HISTORY_LIMIT] user/assistant messages.
 */
class XiaomanAgent(
    private val chat: LlmClient,
    operator: LlmClient,
    device: Device,
    private val memory: MemoryStore,
    private val skills: SkillRepository,
    sentinel: Sentinel,
    private val users: UserChannel,
    private val reminders: ReminderScheduler,
    private val config: AgentConfig = AgentConfig(),
    private val persona: String = Prompts.DEFAULT_PERSONA,
    private val installer: SkillInstaller? = null,
    private val mcp: McpHub? = null,
    private val mcpConfirm: McpConfirmPolicy = McpConfirmPolicy.MUTATING,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : Agent {
    private val loop = OperatorLoop(operator, device, sentinel, users, config)
    private val flows = FlowRunner(loop, users)
    private val historyLock = Mutex()
    private val history = ArrayDeque<ChatMessage>()

    override suspend fun handle(userMessage: String, listener: AgentListener, stop: StopSignal) =
        handle(userMessage, emptyList(), listener, stop)

    override suspend fun handle(
        userMessage: String,
        attachments: List<ChatAttachment>,
        listener: AgentListener,
        stop: StopSignal,
    ) {
        val taskId = idFactory()
        val emit: (AgentEvent) -> Unit = listener::onEvent
        val tools = ChatTools(memory, skills, clock, reminders, { goal, skill ->
            runPhoneTask(goal, skill, listener, stop)
        }, installer, mcp, users, mcpConfirm)
        val mcpSpecs = quietly(emptyList()) { tools.mcpSpecs() }
        try {
            val system = ChatMessage.system(systemPrompt(userMessage))
            val past = historyLock.withLock { history.toList() }
            val userTurn = turnMessage(userMessage, attachments)
            val turn = mutableListOf(userTurn)
            var lastText: String? = null
            var finalText: String? = null
            for (round in 0..MAX_TOOL_ROUNDS) {
                val offerTools = round < MAX_TOOL_ROUNDS
                val response = chat.complete(
                    ChatRequest(listOf(system) + past + turn, tools = if (offerTools) AgentTools.specs + mcpSpecs else emptyList()),
                )
                val text = response.text.trim()
                if (response.toolCalls.isEmpty() || !offerTools) {
                    finalText = text.ifEmpty { if (response.toolCalls.isNotEmpty()) Prompts.TOOL_ROUNDS_EXCEEDED else "" }
                    break
                }
                if (text.isNotEmpty()) {
                    emit(AgentEvent.Reply(taskId, text))
                    lastText = text
                }
                turn += ChatMessage(ChatRole.ASSISTANT, listOf(ContentPart.Text(response.text)), toolCalls = response.toolCalls)
                for (call in response.toolCalls) {
                    turn += ChatMessage.tool(call.id, tools.execute(call, taskId, emit))
                }
                if (stop.stopped) break
            }
            if (!finalText.isNullOrEmpty()) emit(AgentEvent.Reply(taskId, finalText))
            remember(userTurn, finalText?.takeIf { it.isNotEmpty() } ?: lastText)
        } catch (e: LlmException) {
            emit(AgentEvent.Failed(taskId, Prompts.llmFailed(e)))
        }
    }

    /** One user message: typed text, readable file bodies, then the images as PNG parts. */
    private fun turnMessage(userMessage: String, attachments: List<ChatAttachment>): ChatMessage {
        val images = attachments.filterIsInstance<ChatAttachment.Image>()
        var budget = MAX_ATTACHMENT_CHARS
        val body = buildString {
            if (userMessage.isNotBlank()) append(userMessage.trim())
            for (a in attachments) {
                when (a) {
                    is ChatAttachment.Text -> {
                        append("\n\n【文件：").append(a.name).append("】\n")
                        val content = a.content.trim()
                        val room = budget.coerceAtLeast(0)
                        append(content.take(room))
                        if (content.length > room) append("\n…（内容过长已截断）")
                        budget -= content.length.coerceAtMost(room)
                    }
                    is ChatAttachment.File ->
                        append("\n\n【文件：").append(a.name).append("（").append(fileSize(a.size)).append("，无法读取内容）】")
                    is ChatAttachment.Image -> Unit
                }
            }
            if (images.isNotEmpty()) {
                if (isBlank()) append("请看看我发的图片。")
                append("\n\n附图片：").append(images.joinToString("、") { it.name })
            }
        }.trim()
        val parts = buildList {
            if (body.isNotEmpty()) add(ContentPart.Text(body))
            images.forEach { add(ContentPart.ImagePng(it.png)) }
        }
        return ChatMessage(ChatRole.USER, parts.ifEmpty { listOf(ContentPart.Text("（空消息）")) })
    }

    private fun fileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    override suspend fun runPhoneTask(goal: String, skillName: String?, listener: AgentListener, stop: StopSignal): AgentEvent.PhoneTaskFinished {
        val taskId = idFactory()
        fun finish(success: Boolean, summary: String, steps: Int) =
            AgentEvent.PhoneTaskFinished(taskId, success, summary, steps).also(listener::onEvent)

        val wanted = skillName?.trim()?.takeIf { it.isNotEmpty() }
        val skill = wanted?.let { quietly(null) { skills.get(it) } }
        if (skill != null) listener.onEvent(AgentEvent.SkillChosen(taskId, skill.name))
        listener.onEvent(AgentEvent.PhoneTaskStarted(taskId, goal, skill?.name))
        if (wanted != null && skill == null) return finish(false, "${Prompts.SKILL_NOT_FOUND}：$wanted", 0)
        // Knowledge skills (search, research) only need the chat model and web_fetch, so they must
        // not require the accessibility service. Only skills that really drive the UI do.
        if (skill != null && !skill.needsDevice) {
            val answer = runTextSkill(skill, goal, stop)
            return finish(answer.isNotBlank(), answer.ifBlank { Prompts.TEXT_SKILL_EMPTY }, 0)
        }

        val task = TaskRun(taskId, goal, skill, listener, stop)
        val outcome = try {
            task.references = references(skill)
            task.howto = howto(goal, skill)
            val flow = skill?.flow
            if (flow != null && flow.steps.isNotEmpty()) flows.run(task, flow) else loop.run(task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            val message = Prompts.operatorLlmFailed(e)
            listener.onEvent(AgentEvent.Failed(taskId, message))
            LoopOutcome(false, message)
        } catch (e: Exception) {
            // e.g. the accessibility service is not connected: device.observe() throws.
            val raw = e.message?.takeIf { it.isNotBlank() } ?: Prompts.UNEXPECTED_ERROR
            val message = if (raw == DEVICE_NOT_CONNECTED) Prompts.DEVICE_REQUIRED else raw
            listener.onEvent(AgentEvent.Failed(taskId, message))
            LoopOutcome(false, message)
        }
        if (outcome.success && config.learnFromSuccess) quietly(Unit) { learn(task) }
        return finish(outcome.success, outcome.summary, task.steps)
    }

    /**
     * Runs a knowledge skill (search / research) on the chat model with [AgentTools.WEB_FETCH].
     * No screen is observed, so this path works with the accessibility service turned off.
     */
    private suspend fun runTextSkill(skill: Skill, goal: String, stop: StopSignal): String {
        val fetch = WebFetch()
        val messages = mutableListOf(
            ChatMessage.system(Prompts.textSkillSystem(skill.name, skill.instructions)),
            ChatMessage.user(goal),
        )
        var answer = ""
        for (round in 0 until TEXT_SKILL_ROUNDS) {
            if (stop.stopped) return Prompts.STOPPED
            val response = try {
                chat.complete(ChatRequest(messages.toList(), tools = listOf(AgentTools.webFetchSpec)))
            } catch (e: LlmException) {
                return Prompts.llmFailed(e)
            }
            answer = response.text.trim()
            if (response.toolCalls.isEmpty()) return answer
            messages += ChatMessage(ChatRole.ASSISTANT, listOf(ContentPart.Text(response.text)), toolCalls = response.toolCalls)
            for (call in response.toolCalls) {
                val result = if (call.name == AgentTools.WEB_FETCH) {
                    val url = JsonText.firstObject(call.argumentsJson)?.let { JsonText.string(it, "url") }
                    if (url.isNullOrBlank()) "参数错误：缺少 url" else fetch.fetch(url)
                } else {
                    "未知工具：${call.name}"
                }
                messages += ChatMessage.tool(call.id, result)
            }
        }
        // Tool budget used up: ask once more without tools so the model has to answer.
        return quietly(answer) { chat.complete(ChatRequest(messages.toList())).text.trim() }
    }

    private suspend fun systemPrompt(userMessage: String): String = buildString {
        append(persona.trim())
        append("\n\n").append(Prompts.nowSection(ReminderTimes.promptNow(clock())))
        append("\n\n").append(Prompts.SKILLS_HEADER).append('\n')
        val list = quietly(emptyList()) { skills.list() }
        if (list.isEmpty()) append(Prompts.NO_SKILLS) else list.forEach { append("- ").append(it.name).append(": ").append(it.description).append('\n') }
        val hits = quietly(emptyList()) { memory.search(userMessage, MEMORY_HITS) }
        if (hits.isNotEmpty()) {
            append("\n\n").append(Prompts.MEMORY_HEADER).append('\n')
            hits.forEach { append("- ").append(it.title).append(" (").append(it.permalink).append("): ").append(it.snippet.replace('\n', ' ')).append('\n') }
        }
    }.trimEnd()

    override fun forgetHistory() {
        // Conversations are switched only while idle, so the lock is free; never block the UI.
        if (!historyLock.tryLock()) return
        try {
            history.clear()
        } finally {
            historyLock.unlock()
        }
    }

    private suspend fun remember(user: ChatMessage, reply: String?) = historyLock.withLock {
        // Follow-up turns only need the text; resending image bytes would grow every request.
        history.addLast(ChatMessage(ChatRole.USER, user.parts.filterIsInstance<ContentPart.Text>()))
        if (!reply.isNullOrBlank()) history.addLast(ChatMessage.assistant(reply))
        while (history.size > HISTORY_LIMIT) history.removeFirst()
        // Never start the window with an assistant message.
        while (history.firstOrNull()?.role == ChatRole.ASSISTANT) history.removeFirst()
    }

    private suspend fun references(skill: Skill?): String {
        if (skill == null) return ""
        return skill.memoryRefs.mapNotNull { ref -> quietly(null) { memory.read(ref) }?.let { NoteText.forPrompt(it) } }.joinToString("\n\n")
    }

    /** `howto` notes for the goal: the skill's own note first, then search hits of type howto. */
    private suspend fun howto(goal: String, skill: Skill?): String {
        val notes = LinkedHashMap<String, Note>()
        skill?.let { s -> quietly(null) { memory.read(howtoPermalink(s.name)) }?.let { notes[it.permalink] = it } }
        for (hit in quietly(emptyList()) { memory.search(goal, MEMORY_HITS) }) {
            if (notes.size >= MAX_HOWTO_NOTES) break
            if (hit.permalink in notes) continue
            quietly(null) { memory.read(hit.permalink) }?.takeIf { it.type == HOWTO_TYPE }?.let { notes[it.permalink] = it }
        }
        return notes.values.take(MAX_HOWTO_NOTES).joinToString("\n\n") { NoteText.forPrompt(it, maxChars = 800) }
    }

    /** Records the successful path as `- [path] a → b → c #skill` in `howto/<skill-or-app>`. */
    private suspend fun learn(task: TaskRun) {
        if (task.path.isEmpty()) return
        val key = task.skill?.name ?: task.launchedPackage ?: task.lastPackage ?: return
        val permalink = howtoPermalink(key)
        val observation = Observation(PATH_CATEGORY, task.path.joinToString(" → "), tags = listOf(key))
        val existing = memory.read(permalink)
        if (existing != null) {
            if (existing.observations.any { it.category == observation.category && it.content == observation.content }) return
            memory.appendObservation(permalink, observation)
        } else {
            val title = "$key 操作路径"
            memory.write(
                Note(
                    title = title,
                    permalink = permalink,
                    type = HOWTO_TYPE,
                    tags = listOf(HOWTO_TYPE),
                    body = NoteText.body(title, listOf(observation), emptyList()),
                    observations = listOf(observation),
                    updatedAtMillis = clock(),
                ),
            )
        }
        task.emit(AgentEvent.MemoryUpdated(task.taskId, permalink))
    }

    private suspend fun <T> quietly(default: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        default
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 8
        const val HISTORY_LIMIT = 20
        const val MEMORY_HITS = 5
        const val HOWTO_TYPE = "howto"
        const val PATH_CATEGORY = "path"
        private const val MAX_HOWTO_NOTES = 3

        fun howtoPermalink(key: String) = "howto/$key"
    }
}
