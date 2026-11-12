package dev.xiaoman.memory

import dev.xiaoman.api.MemoryFolder
import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * [MemoryStore] backed by a folder of Basic Memory Markdown files: note `a/b` lives in `<root>/a/b.md`.
 *
 * Files may be added or edited by other tools (adb push, Obsidian); every lookup rescans the folder
 * by mtime/size so the search index follows them. Writes are atomic (temp file + rename), UTF-8,
 * and stamp the file's mtime with [clock]. Hidden files and folders (`.obsidian`) are ignored.
 */
class FileMemoryStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : MemoryStore {
    private val mutex = Mutex()
    private val index = MemoryIndex(root)
    private val canonicalRoot by lazy { root.canonicalFile }

    override suspend fun write(note: Note): Note = locked { writeLocked(note) }

    override suspend fun read(identifier: String): Note? = locked { readLocked(identifier) }

    override suspend fun search(query: String, limit: Int): List<SearchHit> = locked {
        index.sync()
        index.search(query, limit)
    }

    override suspend fun appendObservation(identifier: String, observation: Observation): Note = locked {
        val existing = readLocked(identifier)
        if (existing != null) {
            writeLocked(existing.copy(observations = existing.observations + observation))
        } else {
            val permalink = Permalinks.normalize(identifier)
            writeLocked(Note(title = permalink.substringAfterLast('/'), permalink = permalink, observations = listOf(observation)))
        }
    }

    override suspend fun delete(identifier: String): Boolean = locked {
        val note = readLocked(identifier) ?: return@locked false
        val file = fileFor(note.permalink)
        val deleted = file.delete()
        index.remove(note.permalink)
        pruneEmptyFolders(file.parentFile)
        deleted
    }

    override suspend fun list(folder: String?): List<Note> = locked {
        index.sync()
        val prefix = folder?.trim()?.replace('\\', '/')?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "$it/" }
        index.notes.filter { prefix == null || it.permalink.startsWith(prefix) }.sortedBy { it.permalink }
    }

    override suspend fun listFolders(parent: String?): List<MemoryFolder> = locked {
        val base = folderKey(parent)
        val dir = folderDir(base)
        if (!dir.isDirectory) return@locked emptyList()
        index.sync()
        dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?.map { child ->
                val path = if (base.isEmpty()) child.name else "$base/${child.name}"
                MemoryFolder(
                    path = path,
                    name = child.name,
                    noteCount = index.notes.count { it.permalink.startsWith("$path/") },
                    childCount = child.listFiles()?.count { it.isDirectory && !it.name.startsWith(".") } ?: 0,
                )
            }
            ?: emptyList()
    }

    override suspend fun createFolder(path: String): Boolean = locked {
        val key = folderKey(path)
        require(key.isNotEmpty()) { "Folder name is empty" }
        val dir = folderDir(key)
        if (dir.exists()) return@locked false
        dir.mkdirs()
    }

    override suspend fun renameFolder(path: String, newName: String): Boolean = locked {
        val old = folderKey(path)
        require(old.isNotEmpty()) { "Folder name is empty" }
        val name = Permalinks.normalize(newName)
        require(!name.contains('/')) { "Folder name must be one segment: $newName" }
        val dir = folderDir(old)
        if (!dir.isDirectory) return@locked false
        val target = File(dir.parentFile, name)
        require(insideRoot(target)) { "Folder escapes the memory folder: $newName" }
        if (target.exists()) return@locked false
        val renamed = dir.renameTo(target)
        if (renamed) index.sync()
        renamed
    }

    override suspend fun moveFolder(path: String, newParent: String?): Boolean = locked {
        val old = folderKey(path)
        require(old.isNotEmpty()) { "Folder name is empty" }
        val parent = folderKey(newParent)
        require(parent != old && !parent.startsWith("$old/")) { "Cannot move a folder into itself" }
        val dir = folderDir(old)
        if (!dir.isDirectory) return@locked false
        val parentDir = folderDir(parent)
        if (!parentDir.isDirectory && !parentDir.mkdirs()) return@locked false
        val target = File(parentDir, dir.name)
        require(insideRoot(target)) { "Folder escapes the memory folder: $parent" }
        if (target.exists()) return@locked false
        val moved = dir.renameTo(target)
        if (moved) index.sync()
        moved
    }

    override suspend fun deleteFolder(path: String): Boolean = locked {
        val key = folderKey(path)
        if (key.isEmpty()) return@locked false
        val dir = folderDir(key)
        if (!dir.isDirectory) return@locked false
        val deleted = dir.deleteRecursively()
        index.sync()
        pruneEmptyFolders(dir.parentFile)
        deleted
    }

    override suspend fun moveNote(identifier: String, targetFolder: String?): Note? = locked {
        val note = readLocked(identifier) ?: return@locked null
        val folder = folderKey(targetFolder)
        if (folder == note.permalink.substringBeforeLast('/', "")) return@locked note
        val slug = note.permalink.substringAfterLast('/')
        val permalink = uniquePermalink(folder, slug, note.title)
        val moved = writeLocked(note.copy(permalink = permalink))
        if (permalink != note.permalink) fileFor(note.permalink).delete()
        index.sync()
        pruneEmptyFolders(fileFor(note.permalink).parentFile)
        moved
    }

    private fun folderKey(raw: String?): String {
        val path = raw?.trim()?.replace('\\', '/')?.trim('/').orEmpty()
        return if (path.isEmpty()) "" else Permalinks.normalize(path)
    }

    private fun folderDir(path: String): File {
        val dir = if (path.isEmpty()) root else File(root, path)
        require(insideRoot(dir)) { "Folder escapes the memory folder: $path" }
        return dir
    }

    private fun uniquePermalink(folder: String, slug: String, title: String): String {
        var n = 2
        while (true) {
            val candidate = if (folder.isEmpty()) {
                if (n == 2) slug else "$slug-${n - 1}"
            } else {
                if (n == 2) "$folder/$slug" else "$folder/$slug-${n - 1}"
            }
            val file = fileFor(candidate)
            if (!file.exists()) return candidate
            if (index.load(candidate, file)?.title == title) return candidate
            n++
        }
    }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun writeLocked(note: Note): Note {
        val title = note.title.trim().ifEmpty { note.permalink.trim().removeSuffix(".md").substringAfterLast('/').trim() }
        require(title.isNotEmpty()) { "Note needs a title or a permalink" }
        val permalink = if (note.permalink.isBlank()) generatePermalink(title) else Permalinks.normalize(note.permalink)
        val file = fileFor(permalink)
        val text = BasicMemoryFormat.render(note.copy(title = title, permalink = permalink))
        atomicWrite(file, text)
        file.setLastModified(clock())
        val stored = BasicMemoryFormat.parse(text, permalink, file.lastModified())
        index.put(stored, file.lastModified(), file.length())
        return stored
    }

    private fun readLocked(identifier: String): Note? {
        val key = identifier.trim()
        if (key.isEmpty()) return null
        val permalink = runCatching { Permalinks.normalize(key) }.getOrNull()
        if (permalink != null) {
            val file = fileFor(permalink)
            if (file.isFile) return index.load(permalink, file)
        }
        index.sync()
        val notes = index.notes.sortedBy { it.permalink }
        return notes.firstOrNull { it.title == key } ?: notes.firstOrNull { it.title.equals(key, ignoreCase = true) }
    }

    /** Slug of [title], suffixed `-2`, `-3`, … when another note already owns it. */
    private fun generatePermalink(title: String): String {
        val base = Permalinks.fromTitle(title).ifEmpty { "note-${clock()}" }
        var candidate = base
        var n = 2
        while (true) {
            val file = fileFor(candidate)
            if (!file.exists()) return candidate
            if (file.isFile && index.load(candidate, file)?.title == title) return candidate
            candidate = "$base-${n++}"
        }
    }

    private fun fileFor(permalink: String): File {
        val file = File(root, "$permalink.md")
        require(insideRoot(file)) { "Permalink escapes the memory folder: $permalink" }
        return file
    }

    /** java.nio.file.Path is API 26+, so containment is checked on canonical path strings. */
    private fun insideRoot(file: File): Boolean {
        val path = file.canonicalPath
        val base = canonicalRoot.path
        return path == base || path.startsWith(base + File.separator)
    }

    private fun atomicWrite(file: File, text: String) {
        val dir = file.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create folder $dir")
        val temp = File.createTempFile(".${file.name}.", ".tmp", dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            // java.nio.file is API 26+; plain rename keeps Android 7.1 working and is atomic on the same filesystem.
            if (file.exists() && !file.delete()) throw IOException("Cannot replace $file")
            if (!temp.renameTo(file)) {
                file.outputStream().use { out -> temp.inputStream().use { it.copyTo(out) } }
                if (!temp.delete()) temp.deleteOnExit()
            }
        } finally {
            temp.delete()
        }
    }

    private fun pruneEmptyFolders(start: File?) {
        var dir = start?.canonicalFile ?: return
        while (dir != canonicalRoot && insideRoot(dir)) {
            if (dir.list()?.isEmpty() != true || !dir.delete()) return
            dir = dir.parentFile ?: return
        }
    }
}
