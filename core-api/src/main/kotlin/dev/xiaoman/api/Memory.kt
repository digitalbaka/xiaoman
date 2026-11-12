package dev.xiaoman.api

/**
 * Memory is a folder of Markdown notes in the Basic Memory format, so it can be opened in Obsidian:
 *
 * ```
 * ---
 * title: 家
 * type: place
 * permalink: places/家
 * tags: [地址]
 * ---
 * # 家
 *
 * ## Observations
 * - [address] 北京市海淀区某某路 1 号 #常用 (用户 2026-10-30 告知)
 *
 * ## Relations
 * - lives_in [[北京]]
 * ```
 *
 * Observation lines are `- [category] content #tag ... (optional context)`.
 * Relation lines are `- relation_type [[Target Title]] (optional context)`.
 * Wiki links `[[...]]` anywhere in the body also count as `links_to` relations.
 */
data class Note(
    val title: String,
    /** Folder-relative path without `.md`, e.g. `places/家`. Unique key of the note. */
    val permalink: String,
    val type: String = "note",
    val tags: List<String> = emptyList(),
    /** Frontmatter keys other than title/type/permalink/tags, kept on rewrite. */
    val extraFrontmatter: Map<String, Any?> = emptyMap(),
    /** Markdown body after the frontmatter, including the Observations/Relations sections. */
    val body: String = "",
    val observations: List<Observation> = emptyList(),
    val relations: List<Relation> = emptyList(),
    val updatedAtMillis: Long = 0,
)

data class Observation(val category: String, val content: String, val tags: List<String> = emptyList(), val context: String? = null)

data class Relation(val type: String, val target: String, val context: String? = null)

data class SearchHit(val permalink: String, val title: String, val snippet: String, val score: Double)

/** An immediate child folder of the memory tree, with counts including nested notes. */
data class MemoryFolder(val path: String, val name: String, val noteCount: Int, val childCount: Int)

interface MemoryStore {
    /** Creates or replaces the note at [Note.permalink]; returns the stored note. */
    suspend fun write(note: Note): Note

    /** Looks a note up by permalink, then by exact title. */
    suspend fun read(identifier: String): Note?

    /** Full-text search over titles, observations and bodies; must handle Chinese text. */
    suspend fun search(query: String, limit: Int = 10): List<SearchHit>

    /** Adds one observation, creating the note (type `note`) when it does not exist. */
    suspend fun appendObservation(identifier: String, observation: Observation): Note

    /** Real deletion of the file and its index entries. Returns false when nothing was there. */
    suspend fun delete(identifier: String): Boolean

    suspend fun list(folder: String? = null): List<Note>

    /** Immediate child folders of [parent] (null or blank for the root), sorted by name. */
    suspend fun listFolders(parent: String? = null): List<MemoryFolder> = emptyList()

    /** Creates a folder path such as `生活/健康`; false when it already exists. */
    suspend fun createFolder(path: String): Boolean = false

    /** Renames the last segment of [path]; false when the name is taken or the folder is missing. */
    suspend fun renameFolder(path: String, newName: String): Boolean = false

    /** Moves [path] under [newParent] (null for root); false on collision. */
    suspend fun moveFolder(path: String, newParent: String?): Boolean = false

    /** Deletes a folder and every note inside it. */
    suspend fun deleteFolder(path: String): Boolean = false

    /** Moves one note into [targetFolder] (null for root), keeping its title; null when not found. */
    suspend fun moveNote(identifier: String, targetFolder: String?): Note? = null
}
