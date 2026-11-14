package dev.xiaoman.app.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One persisted chat row; [kind] is the [ChatItem] variant name in lower case. */
@Serializable
data class StoredItem(
    val id: Long,
    val kind: String,
    val text: String,
    val ok: Boolean? = null,
    val takeover: Boolean = false,
    /** Creation time; 0 means the file predates the bubble timestamp feature. */
    val at: Long = 0,
)

/** A whole conversation as written to `files/chats/<id>.json`. */
@Serializable
data class StoredConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val items: List<StoredItem>,
)

/** Light-weight row for the history list (no item bodies). */
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
    val count: Int,
)

/**
 * Keeps conversations on disk so they survive a restart. Each conversation is one JSON file;
 * writes happen from a background coroutine and are full rewrites, which is plenty for chat sizes.
 */
class ConversationStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun summaries(): List<ConversationSummary> =
        conversations().map { c ->
            ConversationSummary(
                id = c.id,
                title = c.title,
                updatedAt = c.updatedAt,
                preview = c.items.lastOrNull { it.kind == "assistant" || it.kind == "user" }?.text.orEmpty(),
                count = c.items.size,
            )
        }.sortedByDescending { it.updatedAt }

    fun load(id: String): StoredConversation? {
        val file = fileFor(id)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<StoredConversation>(file.readText()) }.getOrNull()
            ?.takeIf { it.items.isNotEmpty() }
    }

    fun save(conversation: StoredConversation) {
        if (conversation.items.isEmpty()) return
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "${conversation.id}.tmp")
            tmp.writeText(json.encodeToString(conversation))
            val target = fileFor(conversation.id)
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    fun delete(id: String) {
        runCatching { fileFor(id).delete() }
    }

    private fun conversations(): List<StoredConversation> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching { json.decodeFromString<StoredConversation>(f.readText()) }.getOrNull()
                ?.takeIf { it.items.isNotEmpty() }
        }
    }

    private fun fileFor(id: String) = File(dir, "${id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}.json")
}
