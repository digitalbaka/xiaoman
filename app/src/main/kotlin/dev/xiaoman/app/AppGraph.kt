package dev.xiaoman.app

import android.content.Context
import android.util.Log
import dev.xiaoman.agent.Prompts
import dev.xiaoman.agent.XiaomanAgent
import dev.xiaoman.api.Agent
import dev.xiaoman.api.ModelRole
import dev.xiaoman.api.SkillAction
import dev.xiaoman.api.SkillInstallResult
import dev.xiaoman.app.mcp.McpRegistry
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.ConversationStore
import dev.xiaoman.app.session.SessionTexts
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.SettingsStore
import dev.xiaoman.app.skills.SkillHubClient
import dev.xiaoman.app.trace.RecordingDevice
import dev.xiaoman.app.trace.TraceWriter
import dev.xiaoman.hands.AccessibilityDevice
import dev.xiaoman.hands.MutableStopSignal
import dev.xiaoman.hands.XiaomanAccessibilityService
import dev.xiaoman.llm.LlmClientFactory
import dev.xiaoman.llm.LlmClients
import dev.xiaoman.memory.FileMemoryStore
import dev.xiaoman.sentinel.PolicySentinel
import dev.xiaoman.skills.DirectorySkillInstaller
import dev.xiaoman.skills.DirectorySkillRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException

/**
 * Manual dependency container. Everything is built lazily; [start] runs once from
 * [XiaomanApp.onCreate].
 *
 * Folders (`<external files>` = `/sdcard/Android/data/dev.xiaoman.app/files`):
 * `memory/`, `skills/` (user skills, override bundled ones), `traces/`; bundled skills are
 * extracted to `<files>/bundled-skills/`.
 */
class AppGraph(context: Context, val llm: LlmClientFactory = LlmClients.default) {
    private val app: Context = context.applicationContext

    /** Process-wide scope; the session runs agent work here. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val externalRoot: File = app.getExternalFilesDir(null) ?: File(app.filesDir, "external")
    val memoryDir = File(externalRoot, "memory")
    val skillsDir = File(externalRoot, "skills")
    val tracesDir = File(externalRoot, "traces")
    val bundledSkillsDir = File(app.filesDir, "bundled-skills")

    /** Saved conversations; internal storage so they survive restarts and are not user-visible files. */
    val chatsDir = File(app.filesDir, "chats")

    val conversationStore by lazy { ConversationStore(chatsDir) }

    val settings: SettingsStore by lazy { SettingsStore.create(app) }

    val memory by lazy { FileMemoryStore(memoryDir) }

    val skills by lazy { DirectorySkillRepository(listOf(bundledSkillsDir, skillsDir)) }

    /** Installs user skills into [skillsDir]; the Skills tab and the chat `skill_install` tool share it. */
    val skillInstaller by lazy { DirectorySkillInstaller(skillsDir) }

    /** Client for the Skill store selected in Settings (built-in SkillHub by default). */
    fun skillHubClient(): SkillHubClient = SkillHubClient(baseUrl = settings.current.selectedSkillSource.baseUrl.trim())

    /** Enabled external MCP services; the agent discovers their tools on every chat turn. */
    val mcpRegistry by lazy { McpRegistry { settings.current } }

    val notifier by lazy { Notifier(app) }

    val reminderScheduler by lazy { AlarmReminderScheduler(app) }

    val runIds = RunIds()
    val uiVisible = MutableStateFlow(false)

    val traces by lazy {
        TraceWriter(
            root = tracesDir,
            secrets = { settings.current.secrets },
            log = { Log.i(TAG, it) },
            warn = { Log.w(TAG, it) },
        )
    }

    val accessibilityDevice by lazy { AccessibilityDevice({ XiaomanAccessibilityService.instance.value }, app) }

    private val device by lazy { RecordingDevice(accessibilityDevice, traces::saveScreenshot) }

    val sentinel by lazy { PolicySentinel(approvals = session, audit = traces, selfPackage = app.packageName) }

    val session: AgentSession by lazy {
        AgentSession(
            scope = scope,
            agent = ::agent,
            runIds = runIds,
            stopSignal = MutableStopSignal(),
            trace = traces,
            onTaskEnd = { sentinel.endTask(it) },
            texts = SessionTexts(
                notConfigured = app.getString(R.string.session_not_configured),
                busy = app.getString(R.string.session_busy),
                stopped = app.getString(R.string.session_stopped),
                approvedOnce = { app.getString(R.string.approval_resolved_allowed, it) },
                approvedTask = { app.getString(R.string.approval_resolved_task, it) },
                denied = { app.getString(R.string.approval_resolved_denied, it) },
                sentAttachments = app.getString(R.string.session_sent_attachments),
                attachmentImage = app.getString(R.string.chat_attachment_image),
                attachmentFile = app.getString(R.string.chat_attachment_file),
                skillCancelled = app.getString(R.string.skill_change_cancelled),
            ),
            ready = { startup.await() },
            store = conversationStore,
            applySkill = { action ->
                val result = when (action) {
                    is SkillAction.Save -> skillInstaller.installFromMarkdown(action.name, action.markdown, replace = true)
                    is SkillAction.Delete -> {
                        val ok = skillInstaller.delete(action.name)
                        SkillInstallResult(ok, if (ok) "已删除技能 ${action.name}" else "删除失败：技能不存在或属于内置技能")
                    }
                }
                if (result.ok) runCatching { skills.reload() }
                result.ok to result.message
            },
        )
    }

    /** Bundled skills extracted and skills loaded once. */
    lateinit var startup: Deferred<Unit>
        private set

    private var cachedAgent: Pair<AppSettings, Agent>? = null

    /** The agent for the current settings, rebuilt when they change; null until both models are configured. */
    @Synchronized
    fun agent(): Agent? {
        val current = settings.current
        if (!current.configured) return null
        cachedAgent?.let { (s, agent) -> if (s == current) return agent }
        val agent = XiaomanAgent(
            chat = llm.create(current.chat.toModelConfig(ModelRole.CHAT)),
            operator = llm.create(current.effectiveOperator.toModelConfig(ModelRole.OPERATOR)),
            device = device,
            memory = memory,
            skills = skills,
            sentinel = sentinel,
            users = session,
            reminders = reminderScheduler,
            config = current.agentConfig(),
            persona = Prompts.persona(current.assistantName),
            installer = skillInstaller,
            mcp = mcpRegistry,
            mcpConfirm = current.mcp.confirmPolicy,
            idFactory = runIds::next,
        )
        cachedAgent = current to agent
        return agent
    }

    fun start() {
        startup = scope.async(Dispatchers.IO) { prepareSkills() }
        notifier.createChannels()
        OverlayController(session, accessibilityDevice, uiVisible = uiVisible).start(scope)
        scope.launch(Dispatchers.Main.immediate) {
            session.busy.collect { busy -> if (busy) AgentService.start(app) }
        }
        scope.launch(Dispatchers.Main.immediate) { session.approval.collect(notifier::showApproval) }
        scope.launch(Dispatchers.Main.immediate) { session.question.collect(notifier::showQuestion) }
    }

    private suspend fun prepareSkills() {
        try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val version = "${BuildConfig.VERSION_CODE}-${info.lastUpdateTime}"
            if (BundledSkills.extract(app.assets, bundledSkillsDir, version)) Log.i(TAG, "skills: bundled skills extracted ($version)")
            skillsDir.mkdirs()
            memoryDir.mkdirs()
        } catch (e: IOException) {
            Log.w(TAG, "skills: cannot extract bundled skills: ${e.message}")
        }
        val result = skills.reload()
        logSkillProblems(result.skills.size, result.problems)
    }

    fun logSkillProblems(count: Int, problems: List<dev.xiaoman.api.SkillProblem>) {
        Log.i(TAG, "reload-skills: $count skills, ${problems.size} problems")
        problems.forEach { p -> Log.w(TAG, "reload-skills: problem ${p.source}${p.line?.let { ":$it" }.orEmpty()} ${p.message}") }
    }

    companion object {
        const val TAG = "Xiaoman"
    }
}
