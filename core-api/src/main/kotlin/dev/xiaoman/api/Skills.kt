package dev.xiaoman.api

/**
 * A scenario skill written by users: a folder with `SKILL.md` and an optional `flow.yaml`.
 *
 * `SKILL.md` frontmatter:
 * ```
 * ---
 * name: taxi-home                 # required, [a-z0-9-], unique
 * description: 打车回家            # required, one line; shown to the chat model to pick skills
 * apps: [com.sdu.didi.psnger]     # package names the skill may operate
 * channel: deeplink-then-gui      # api | cli | deeplink | gui | deeplink-then-gui | mixed
 * confirm_before: [确认呼叫, 支付]  # words that force a user confirmation before tapping/typing on them
 * memory: [地址/家, 偏好/车型]       # notes to load into the operator prompt
 * deeplinks:                      # optional named deep links; {placeholders} filled by the operator
 *   search: "amapuri://route/plan/?dname={destination}"
 * ---
 * Markdown instructions for the operator model.
 * ```
 */
data class Skill(
    val name: String,
    val description: String,
    val apps: List<String> = emptyList(),
    val channel: SkillChannel = SkillChannel.GUI,
    val confirmBefore: List<String> = emptyList(),
    val memoryRefs: List<String> = emptyList(),
    val deeplinks: Map<String, String> = emptyMap(),
    /** Markdown body of SKILL.md after the frontmatter. */
    val instructions: String,
    val flow: Flow? = null,
    /** Where the skill was loaded from (folder path or URI), for display and reload. */
    val source: String,
) {
    /**
     * True when running the skill needs the screen (accessibility service): it has a fixed flow,
     * names the apps it operates, or opens a deep link. Knowledge skills such as a search skill
     * only need the chat model's tools, so they run without accessibility.
     */
    val needsDevice: Boolean
        get() = flow != null || apps.isNotEmpty() ||
            channel == SkillChannel.DEEPLINK || channel == SkillChannel.DEEPLINK_THEN_GUI || channel == SkillChannel.MIXED
}

enum class SkillChannel(val wire: String) {
    API("api"), CLI("cli"), DEEPLINK("deeplink"), GUI("gui"), DEEPLINK_THEN_GUI("deeplink-then-gui"), MIXED("mixed");

    companion object {
        fun fromWire(value: String): SkillChannel? = entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

/**
 * Optional `flow.yaml`: fixed steps run before or between free operator steps.
 * ```
 * steps:
 *   - launch: com.autonavi.minimap
 *   - deeplink: search            # a key of SKILL.md deeplinks, or a literal URI
 *   - ai: 选择快车并读出预估价       # a sub-goal handed to the operator loop
 *   - confirm: 确认呼叫快车？        # explicit user confirmation
 *   - tap: 确认呼叫                 # tap the element best matching this description
 *   - type: { target: 目的地, text: "{destination}" }
 *   - wait: 1500
 *   - assert: 行程已开始             # operator checks the screen shows this, else the flow fails
 * ```
 */
data class Flow(val steps: List<FlowStep>)

sealed class FlowStep {
    data class Launch(val packageName: String) : FlowStep()
    data class Deeplink(val keyOrUri: String) : FlowStep()
    data class Ai(val goal: String) : FlowStep()
    data class Confirm(val question: String) : FlowStep()
    data class Tap(val target: String) : FlowStep()
    data class Type(val target: String, val text: String) : FlowStep()
    data class Wait(val millis: Long) : FlowStep()
    data class Assert(val expectation: String) : FlowStep()
}

data class SkillProblem(val source: String, val message: String, val line: Int? = null)

/** Result of loading skills: problems never abort loading of the other skills. */
data class SkillLoadResult(val skills: List<Skill>, val problems: List<SkillProblem>)

interface SkillRepository {
    suspend fun reload(): SkillLoadResult

    suspend fun list(): List<Skill>

    suspend fun get(name: String): Skill?
}

/** A user-installed skill folder under the app's writable skills directory. */
data class InstalledSkillInfo(
    val name: String,
    val description: String?,
    val enabled: Boolean,
    val source: String,
)

/** Outcome of installing one zip; [installed] lists skill names, [replaced] ones that already existed. */
data class SkillInstallResult(
    val ok: Boolean,
    val message: String,
    val installed: List<String> = emptyList(),
    val replaced: List<String> = emptyList(),
)

/** A skill change proposed by the chat model; it only happens after the user confirms. */
sealed class SkillAction {
    abstract val name: String

    /** Create or overwrite a user skill with [markdown] (a complete `SKILL.md`). */
    data class Save(override val name: String, val markdown: String, val replace: Boolean) : SkillAction()

    /** Delete a user-installed skill; bundled skills are never touched. */
    data class Delete(override val name: String) : SkillAction()
}

/**
 * Rejects skill texts that carry secrets. The model must not copy passwords, API keys or phone
 * numbers into a `SKILL.md`, so such drafts are refused instead of saved.
 */
object SkillSafety {
    private val rules: List<Pair<String, Regex>> = listOf(
        "疑似密码或口令" to Regex("(?i)(password|passwd|pwd|密码|口令)\\s*[:：=]\\s*\\S+"),
        "疑似 API Key" to Regex("(?i)(api[_-]?key|secret[_-]?key|access[_-]?token|authorization)\\s*[:：=]\\s*\\S+"),
        "疑似以 sk- 开头的密钥" to Regex("\\bsk-[A-Za-z0-9_-]{16,}"),
        "疑似 Bearer 令牌" to Regex("(?i)bearer\\s+[A-Za-z0-9._-]{16,}"),
        "疑似手机号" to Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
    )

    /** Human-readable problems, empty when the text is clean. */
    fun problems(text: String): List<String> = rules.filter { (_, regex) -> regex.containsMatchIn(text) }.map { it.first }

    fun isSafe(text: String): Boolean = problems(text).isEmpty()
}

/**
 * Installs user skills from a zip (downloaded or picked locally) into the app's skills folder.
 * Implementations must reject zip-slip paths and folders without a valid `SKILL.md`.
 */
interface SkillInstaller {
    suspend fun installFromZip(bytes: ByteArray, sourceName: String = "zip"): SkillInstallResult

    suspend fun installFromUrl(url: String): SkillInstallResult

    /** Writes one `SKILL.md` into the user's skills folder; refuses secrets and invalid skills. */
    suspend fun installFromMarkdown(name: String, markdown: String, replace: Boolean = false): SkillInstallResult =
        SkillInstallResult(false, "当前版本不支持写入技能")

    /** User-installed skills only; bundled skills are not listed here. */
    suspend fun listInstalled(): List<InstalledSkillInfo>

    suspend fun setEnabled(name: String, enabled: Boolean): Boolean

    /** Deletes a user-installed skill; bundled skills are not affected. */
    suspend fun delete(name: String): Boolean
}
