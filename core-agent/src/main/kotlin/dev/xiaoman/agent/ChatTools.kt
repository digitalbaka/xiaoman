package dev.xiaoman.agent

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.McpConfirmPolicy
import dev.xiaoman.api.McpException
import dev.xiaoman.api.McpHub
import dev.xiaoman.api.McpServerConnection
import dev.xiaoman.api.McpTool
import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.Relation
import dev.xiaoman.api.ReminderScheduler
import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillAction
import dev.xiaoman.api.SkillInstaller
import dev.xiaoman.api.SkillRepository
import dev.xiaoman.api.SkillSafety
import dev.xiaoman.api.ToolCall
import dev.xiaoman.api.ToolSpec
import dev.xiaoman.api.UserChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal class ToolArgumentException(message: String) : Exception(message)

/** Runs the chat model's tool calls. Argument and runtime errors become `{"error": ...}` tool results. */
internal class ChatTools(
    private val memory: MemoryStore,
    private val skills: SkillRepository,
    private val clock: () -> Long,
    private val reminders: ReminderScheduler,
    private val phoneTask: suspend (goal: String, skill: String?) -> AgentEvent.PhoneTaskFinished,
    private val installer: SkillInstaller? = null,
    private val mcp: McpHub? = null,
    private val users: UserChannel? = null,
    private val confirmPolicy: McpConfirmPolicy = McpConfirmPolicy.MUTATING,
) {
    private val mcpTools = LinkedHashMap<String, McpBinding>()
    private val emptySchema = buildJsonObject { put("type", "object") }

    private data class McpBinding(val server: McpServerConnection, val tool: McpTool)

    /**
     * Discovers the tools of the user's enabled MCP servers. Call once per chat turn, before
     * [execute]; the discovered names are remembered so their calls can be routed back.
     */
    suspend fun mcpSpecs(): List<ToolSpec> {
        val hub = mcp ?: return emptyList()
        val servers = try {
            hub.servers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }
        if (servers.isEmpty()) return emptyList()
        val discovered = coroutineScope {
            servers.map { server ->
                async {
                    try {
                        server.listTools().map { server to it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.map { it.await() }.flatten()
        }
        val specs = mutableListOf<ToolSpec>()
        for ((server, tool) in discovered) {
            val name = mcpToolName(server, tool)
            if (mcpTools.containsKey(name)) continue
            mcpTools[name] = McpBinding(server, tool)
            specs += ToolSpec(name, mcpDescription(server, tool), tool.inputSchema ?: emptySchema)
        }
        return specs
    }

    private fun mcpToolName(server: McpServerConnection, tool: McpTool): String {
        val base = MCP_PREFIX + sanitize(server.id) + "_" + sanitize(tool.name)
        var name = base.take(60)
        var i = 2
        while (mcpTools.containsKey(name)) {
            name = base.take(56) + "_" + i
            i++
        }
        return name
    }

    private fun sanitize(raw: String): String = raw.trim()
        .map { c -> if (c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '-') c else '_' }
        .joinToString("")
        .trim('_')
        .ifEmpty { "server" }

    private fun mcpDescription(server: McpServerConnection, tool: McpTool): String {
        val prefix = "【${server.title}】"
        val rest = tool.description.trim().ifEmpty { tool.name }
        return (prefix + rest).take(600)
    }

    suspend fun execute(call: ToolCall, taskId: String, emit: (AgentEvent) -> Unit): String = try {
        val args = parseArgs(call.argumentsJson)
        when {
            call.name.startsWith(MCP_PREFIX) -> mcpCall(call.name, args, taskId)
            call.name == AgentTools.MEMORY_SEARCH -> search(args)
            call.name == AgentTools.MEMORY_READ -> read(args)
            call.name == AgentTools.MEMORY_WRITE -> write(args, taskId, emit)
            call.name == AgentTools.MEMORY_FORGET -> forget(args, taskId, emit)
            call.name == AgentTools.MEMORY_FOLDER -> folder(args)
            call.name == AgentTools.LIST_SKILLS -> listSkills()
            call.name == AgentTools.SKILL_INSTALL -> installSkill(args)
            call.name == AgentTools.SKILL_DRAFT -> draftSkill(args, taskId, emit)
            call.name == AgentTools.SKILL_REMOVE -> removeSkill(args, taskId, emit)
            call.name == AgentTools.REMINDER_SET -> reminder(args)
            call.name == AgentTools.PHONE_TASK -> phone(args)
            else -> error("未知工具 ${call.name}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ToolArgumentException) {
        error("参数错误：${e.message}")
    } catch (e: Exception) {
        error("工具执行失败：${e.message ?: e::class.simpleName}")
    }

    private suspend fun mcpCall(name: String, args: JsonObject, taskId: String): String {
        val binding = mcpTools[name] ?: return error("MCP 服务已不可用，请重新发起请求")
        if (needsConfirm(binding.tool)) {
            val channel = users ?: return error("调用 ${binding.server.title} 需要用户确认，但当前没有可用的确认通道")
            val prompt = buildString {
                append("是否允许调用「").append(binding.server.title).append("」的 ")
                append(binding.tool.name).append(" 工具？")
                val preview = args.toString()
                if (preview != "{}") append("\n参数：").append(preview.take(300))
                append("\n回复「确认」执行，其他回复视为取消。")
            }
            val answer = channel.ask(taskId, prompt, takeover = false)
            if (!confirmed(answer)) {
                return buildJsonObject {
                    put("ok", false)
                    put("cancelled", true)
                    put("reason", "用户没有确认，工具没有执行")
                }.toString()
            }
        }
        return try {
            binding.server.callTool(binding.tool.name, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            error("MCP 调用失败：${e.message}")
        } catch (e: Exception) {
            error("MCP 调用失败：${e.message ?: e::class.simpleName}")
        }
    }

    private fun needsConfirm(tool: McpTool): Boolean = when (confirmPolicy) {
        McpConfirmPolicy.NEVER -> false
        McpConfirmPolicy.ALWAYS -> true
        McpConfirmPolicy.MUTATING -> tool.readOnly != true && looksMutating(tool)
    }

    /** Uses the MCP `readOnlyHint` when present, otherwise guesses from the tool name and description. */
    private fun looksMutating(tool: McpTool): Boolean {
        val text = (tool.name + " " + tool.description).lowercase()
        return MUTATING_WORDS.any { text.contains(it) }
    }

    private fun confirmed(answer: String): Boolean {
        val text = answer.trim().lowercase().replace(Regex("[\\s,，。.!！、~～]"), "")
        if (text.isEmpty()) return false
        if (NEGATIVE_WORDS.any { text.contains(it) }) return false
        return AFFIRMATIVE_WORDS.any { text.contains(it) }
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }.toString()

    private fun parseArgs(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return try {
            JsonText.json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw ToolArgumentException("arguments 不是合法的 JSON 对象")
    }

    private fun required(o: JsonObject, key: String): String =
        JsonText.string(o, key) ?: throw ToolArgumentException("缺少 $key")

    private suspend fun search(args: JsonObject): String {
        val query = required(args, "query")
        val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: 5).coerceIn(1, 20)
        val folder = JsonText.string(args, "folder")?.trim()?.replace('\\', '/')?.trim('/')?.takeIf { it.isNotEmpty() }
        val hits = if (folder == null) {
            memory.search(query, limit)
        } else {
            memory.search(query, (limit * 5).coerceAtMost(100))
                .filter { it.permalink.startsWith("$folder/") }
                .take(limit)
        }
        return buildJsonObject {
            folder?.let { put("folder", it) }
            putJsonArray("hits") {
                hits.forEach { h -> addJsonObject { put("permalink", h.permalink); put("title", h.title); put("snippet", h.snippet) } }
            }
        }.toString()
    }

    private suspend fun folder(args: JsonObject): String {
        val folder = JsonText.string(args, "folder")?.trim()?.replace('\\', '/')?.trim('/')?.takeIf { it.isNotEmpty() }
        val includeNotes = (args["include_notes"] as? JsonPrimitive)?.booleanOrNull ?: true
        val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: 20).coerceIn(1, 100)
        val folders = memory.listFolders(folder)
        val notes = if (includeNotes) memory.list(folder).take(limit) else emptyList()
        return buildJsonObject {
            put("folder", folder ?: "")
            putJsonArray("folders") {
                folders.forEach { f ->
                    addJsonObject {
                        put("path", f.path)
                        put("name", f.name)
                        put("noteCount", f.noteCount)
                        put("childCount", f.childCount)
                    }
                }
            }
            putJsonArray("notes") {
                notes.forEach { n ->
                    addJsonObject {
                        put("permalink", n.permalink)
                        put("title", n.title)
                        put("type", n.type)
                        put(
                            "summary",
                            n.observations.joinToString("；") { NoteText.observationLine(it) }
                                .ifBlank { NoteText.preamble(n.body) }
                                .take(500),
                        )
                    }
                }
            }
        }.toString()
    }

    private suspend fun read(args: JsonObject): String {
        val note = memory.read(required(args, "identifier")) ?: return buildJsonObject { put("found", false) }.toString()
        return buildJsonObject {
            put("found", true)
            put("title", note.title)
            put("permalink", note.permalink)
            put("type", note.type)
            putJsonArray("tags") { note.tags.forEach { add(it) } }
            putJsonArray("observations") { note.observations.forEach { add(NoteText.observationLine(it)) } }
            putJsonArray("relations") { note.relations.forEach { add(NoteText.relationLine(it)) } }
            put("body", note.body)
        }.toString()
    }

    private suspend fun write(args: JsonObject, taskId: String, emit: (AgentEvent) -> Unit): String {
        val title = required(args, "title")
        val observations = objects(args, "observations").map {
            Observation(required(it, "category"), required(it, "content"), JsonText.stringList(it, "tags"), JsonText.string(it, "context"))
        }
        val relations = objects(args, "relations").map {
            Relation(required(it, "type"), required(it, "target"), JsonText.string(it, "context"))
        }
        if (observations.isEmpty() && relations.isEmpty()) throw ToolArgumentException("observations 不能为空")

        val existing = memory.read(JsonText.string(args, "permalink") ?: title)
        val folder = JsonText.string(args, "folder")?.trim()?.replace('\\', '/')?.trim('/')?.takeIf { it.isNotEmpty() }
        val permalink = JsonText.string(args, "permalink") ?: existing?.permalink
            ?: (if (folder == null) title else "$folder/$title")
        val mergedObs = ((existing?.observations ?: emptyList()) + observations).distinctBy { it.category to it.content }
        val mergedRels = ((existing?.relations ?: emptyList()) + relations).distinctBy { it.type to it.target }
        val note = Note(
            title = title,
            permalink = permalink,
            type = JsonText.string(args, "type") ?: existing?.type ?: "note",
            tags = existing?.tags ?: emptyList(),
            extraFrontmatter = existing?.extraFrontmatter ?: emptyMap(),
            body = NoteText.body(title, mergedObs, mergedRels, existing?.body?.let { NoteText.preamble(it) } ?: ""),
            observations = mergedObs,
            relations = mergedRels,
            updatedAtMillis = clock(),
        )
        val stored = memory.write(note)
        emit(AgentEvent.MemoryUpdated(taskId, stored.permalink))
        return buildJsonObject {
            put("ok", true)
            put("permalink", stored.permalink)
            put("created", existing == null)
            put("observations", mergedObs.size)
        }.toString()
    }

    private fun objects(args: JsonObject, key: String): List<JsonObject> = when (val e = args[key]) {
        null -> emptyList()
        is JsonArray -> e.map { it as? JsonObject ?: throw ToolArgumentException("$key 的每一项必须是对象") }
        is JsonObject -> listOf(e)
        else -> if (e is JsonPrimitive && e.content == "null") emptyList() else throw ToolArgumentException("$key 必须是数组")
    }

    private suspend fun forget(args: JsonObject, taskId: String, emit: (AgentEvent) -> Unit): String {
        val identifier = required(args, "identifier")
        val permalink = memory.read(identifier)?.permalink ?: identifier
        val deleted = memory.delete(identifier)
        if (deleted) emit(AgentEvent.MemoryUpdated(taskId, permalink))
        return buildJsonObject { put("deleted", deleted) }.toString()
    }

    private suspend fun listSkills(): String {
        val list = skills.list()
        return listSkillsJson(list)
    }

    private suspend fun installSkill(args: JsonObject): String {
        val installer = installer ?: return error("当前版本不支持安装技能")
        val result = installer.installFromUrl(required(args, "url"))
        if (result.ok) runCatching { skills.reload() }
        return buildJsonObject {
            put("ok", result.ok)
            put("message", result.message)
            putJsonArray("installed") { result.installed.forEach { add(it) } }
            putJsonArray("replaced") { result.replaced.forEach { add(it) } }
        }.toString()
    }

    /** Shows a skill draft; nothing is written until the user confirms it in the chat. */
    private fun draftSkill(args: JsonObject, taskId: String, emit: (AgentEvent) -> Unit): String {
        val name = required(args, "name").trim()
        val markdown = required(args, "skill_md")
        val replace = (args["replace"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (!SKILL_NAME.matches(name)) throw ToolArgumentException("技能名只能用小写字母、数字和连字符，如 daily-review")
        val secrets = SkillSafety.problems(markdown)
        if (secrets.isNotEmpty()) {
            throw ToolArgumentException("技能内容不能包含${secrets.joinToString("、")}，请删掉后重新提交")
        }
        if (!markdown.trimStart().startsWith("---")) throw ToolArgumentException("SKILL.md 必须以 --- 包裹的 frontmatter 开头")
        if (!markdown.contains("name:") || !markdown.contains("description:")) {
            throw ToolArgumentException("SKILL.md 的 frontmatter 需要 name 和 description")
        }
        emit(AgentEvent.SkillProposed(taskId, SkillAction.Save(name, markdown.trim(), replace)))
        return buildJsonObject {
            put("ok", true)
            put("status", "草稿已展示，等待用户在界面上确认")
            put("note", "用户确认前没有写入；现在不要声称已经保存成功")
        }.toString()
    }

    /** Deletes only user-installed skills; bundled ones never reach this path. */
    private suspend fun removeSkill(args: JsonObject, taskId: String, emit: (AgentEvent) -> Unit): String {
        val name = required(args, "name").trim()
        val installer = installer ?: return error("当前版本不支持删除技能")
        val installed = installer.listInstalled().map { it.name }
        if (name !in installed) {
            val bundled = quietly { skills.get(name) != null } == true
            return error(if (bundled) "「$name」是内置技能，不能删除" else "没有找到用户安装的技能「$name」")
        }
        emit(AgentEvent.SkillProposed(taskId, SkillAction.Delete(name)))
        return buildJsonObject {
            put("ok", true)
            put("status", "等待用户在界面上确认删除")
            put("note", "用户确认前没有删除")
        }.toString()
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun listSkillsJson(list: List<Skill>): String = buildJsonObject {        putJsonArray("skills") {
            list.forEach { s ->
                addJsonObject {
                    put("name", s.name)
                    put("description", s.description)
                    put("apps", buildJsonArray { s.apps.forEach { add(it) } })
                    put("hasFlow", s.flow != null)
                }
            }
        }
    }.toString()

    private fun reminder(args: JsonObject): String {
        val text = required(args, "text")
        val raw = required(args, "at")
        val atMillis = ReminderTimes.parseAtMillis(raw)
            ?: throw ToolArgumentException("at 不是合法的 ISO-8601 本地时间，应为 2026-11-03T08:00 这样的形式")
        if (atMillis <= clock()) throw ToolArgumentException("提醒时间 $raw 已经过去，请向用户确认一个将来的时间")
        reminders.schedule(text, atMillis)
        return buildJsonObject {
            put("ok", true)
            put("text", text)
            put("at", raw)
        }.toString()
    }

    private suspend fun phone(args: JsonObject): String {
        val finished = phoneTask(required(args, "goal"), JsonText.string(args, "skill"))
        return buildJsonObject {
            put("success", finished.success)
            put("summary", finished.summary)
            put("steps", finished.steps)
        }.toString()
    }

    companion object {
        const val MCP_PREFIX = "mcp_"

        /** Skill folder names, shared with the parser. */
        private val SKILL_NAME = Regex("^[a-z0-9-]+$")

        /** Tool-name fragments that usually mean the tool changes something on the server. */
        private val MUTATING_WORDS = listOf(
            "create", "add", "insert", "update", "set", "edit", "modify", "delete", "remove",
            "clear", "send", "post", "put", "patch", "write", "upload", "book", "order", "buy",
            "pay", "purchase", "cancel", "submit", "revoke", "invite", "move", "rename",
            "schedule", "reserve", "transfer", "publish", "save", "comment",
            "创建", "新增", "添加", "修改", "更新", "删除", "移除", "发送", "提交", "下单",
            "购买", "支付", "预订", "预约", "取消", "清空", "写入", "上传", "编辑", "保存",
            "移动", "重命名", "发布", "评论",
        )
        private val AFFIRMATIVE_WORDS = listOf(
            "确认", "确定", "可以", "允许", "同意", "继续", "执行", "好", "是", "行", "嗯",
            "yes", "ok", "sure", "go",
        )
        private val NEGATIVE_WORDS = listOf("不", "取消", "别", "算了", "停止", "拒绝", "no")
    }
}
