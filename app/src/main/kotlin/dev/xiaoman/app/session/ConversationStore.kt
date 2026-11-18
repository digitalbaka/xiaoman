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
    /** Archived chats stay on disk but leave the main history list. */
    val archived: Boolean = false,
    /** Epoch millis when moved to the trash; 0 means the conversation is not deleted. */
    val deletedAt: Long = 0,
)

/** Light-weight row for the history list (no item bodies). */
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
    val count: Int,
    val archived: Boolean = false,
    val deletedAt: Long = 0,
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
                archived = c.archived,
                deletedAt = c.deletedAt,
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
            // Archive / trash flags belong to the store; a chat turn must not reset them.
            val stored = load(conversation.id)
            write(
                conversation.copy(
                    archived = stored?.archived ?: conversation.archived,
                    deletedAt = stored?.deletedAt ?: conversation.deletedAt,
                ),
            )
        }
    }

    /** Hides or shows a conversation in the main list without touching its rows. */
    fun setArchived(id: String, archived: Boolean): Boolean = update(id) { it.copy(archived = archived) }

    /** Moves a conversation to the trash; [atMillis] keeps the trash ordered by deletion time. */
    fun trash(id: String, atMillis: Long = System.currentTimeMillis()): Boolean = update(id) { it.copy(deletedAt = atMillis) }

    /** Takes a conversation out of the trash. */
    fun restore(id: String): Boolean = update(id) { it.copy(deletedAt = 0) }

    /** Removes the file for good. */
    fun delete(id: String) {
        runCatching { fileFor(id).delete() }
    }

    /**
     * Drops trash entries that have been waiting longer than [maxAgeMillis], so the recycle bin does
     * not keep deleted chats forever. Runs at app start; returns the ids that were removed.
     */
    fun purgeTrash(
        maxAgeMillis: Long = TRASH_MAX_AGE_MILLIS,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val expired = conversations().filter { it.deletedAt > 0 && now - it.deletedAt >= maxAgeMillis }
        expired.forEach { runCatching { fileFor(it.id).delete() } }
        return expired.map { it.id }
    }

    private fun update(id: String, change: (StoredConversation) -> StoredConversation): Boolean {
        val conversation = load(id) ?: return false
        return runCatching { write(change(conversation)) }.isSuccess
    }

    private fun write(conversation: StoredConversation) {
        dir.mkdirs()
        val tmp = File(dir, "${conversation.id}.tmp")
        tmp.writeText(json.encodeToString(conversation))
        val target = fileFor(conversation.id)
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun conversations(): List<StoredConversation> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching { json.decodeFromString<StoredConversation>(f.readText()) }.getOrNull()
                ?.takeIf { it.items.isNotEmpty() }
        }
    }

    private fun fileFor(id: String) = File(dir, "${id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}.json")

    companion object {
        /** Trashed chats are removed automatically after this long (30 days). */
        const val TRASH_MAX_AGE_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
