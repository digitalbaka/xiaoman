package dev.xiaoman.app.session

import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationStoreTest {
    private fun tempStore(): ConversationStore =
        ConversationStore(Files.createTempDirectory("chats").toFile())

    private fun conversation(id: String, title: String, text: String, at: Long) = StoredConversation(
        id = id,
        title = title,
        updatedAt = at,
        items = listOf(StoredItem(1, "user", text), StoredItem(2, "assistant", "$text 的回复")),
    )

    @Test
    fun savesAndListsNewestFirst() {
        val store = tempStore()
        store.save(conversation("chat-1", "旧会话", "你好", 1_000))
        store.save(conversation("chat-2", "新会话", "在吗", 2_000))

        val all = store.summaries()
        assertEquals(listOf("chat-2", "chat-1"), all.map { it.id })
        assertEquals("新会话", all.first().title)
        assertTrue(all.first().preview.contains("的回复"))
        assertEquals(2, all.first().count)
    }

    @Test
    fun loadsItemsForSwitch() {
        val store = tempStore()
        store.save(conversation("chat-1", "会话", "搜一下机型", 1_000))
        val loaded = assertNotNull(store.load("chat-1"))
        assertEquals(2, loaded.items.size)
        assertEquals("user", loaded.items.first().kind)
    }

    @Test
    fun deleteRemovesConversation() {
        val store = tempStore()
        store.save(conversation("chat-1", "会话", "你好", 1_000))
        store.delete("chat-1")
        assertNull(store.load("chat-1"))
        assertTrue(store.summaries().isEmpty())
    }

    @Test
    fun archiveKeepsRowsButMarksTheSummary() {
        val store = tempStore()
        store.save(conversation("chat-1", "会话", "你好", 1_000))

        assertTrue(store.setArchived("chat-1", true))

        val summary = store.summaries().single()
        assertTrue(summary.archived)
        assertEquals(0L, summary.deletedAt)
        assertEquals(2, assertNotNull(store.load("chat-1")).items.size)
    }

    @Test
    fun aNewTurnKeepsArchiveAndTrashFlags() {
        val store = tempStore()
        store.save(conversation("chat-1", "会话", "你好", 1_000))
        store.setArchived("chat-1", true)

        // A chat turn rewrites the whole file: the store must not drop the flags.
        store.save(conversation("chat-1", "会话", "又说了一句", 2_000))

        assertTrue(store.summaries().single().archived)
    }

    @Test
    fun trashRestoreAndDeleteForGood() {
        val store = tempStore()
        store.save(conversation("chat-1", "会话", "你好", 1_000))

        store.trash("chat-1", 5_000)
        assertEquals(5_000L, store.summaries().single().deletedAt)
        assertNotNull(store.load("chat-1"))

        store.restore("chat-1")
        assertEquals(0L, store.summaries().single().deletedAt)

        store.trash("chat-1", 6_000)
        store.delete("chat-1")
        assertNull(store.load("chat-1"))
        assertTrue(store.summaries().isEmpty())
    }

    @Test
    fun purgeTrashRemovesOnlyEntriesOlderThan30Days() {
        val store = tempStore()
        store.save(conversation("chat-new", "刚删", "你好", 1_000))
        store.save(conversation("chat-old", "放久了", "你好", 1_000))
        val now = 100L * 24 * 60 * 60 * 1000
        val month = ConversationStore.TRASH_MAX_AGE_MILLIS
        store.trash("chat-new", now - month + 60_000) // 差一分钟满 30 天
        store.trash("chat-old", now - month - 60_000) // 已超过 30 天

        assertEquals(listOf("chat-old"), store.purgeTrash(now = now))

        assertNotNull(store.load("chat-new"))
        assertNull(store.load("chat-old"))
        assertEquals(listOf("chat-new"), store.summaries().map { it.id })
    }

    @Test
    fun filesWithoutFlagsLoadAsActive() {        val dir = Files.createTempDirectory("chats").toFile()
        dir.resolve("chat-old.json").writeText(
            """{"id":"chat-old","title":"旧会话","updatedAt":1000,"items":[{"id":1,"kind":"user","text":"hi"}]}""",
        )
        val store = ConversationStore(dir)

        val summary = store.summaries().single()
        assertFalse(summary.archived)
        assertEquals(0L, summary.deletedAt)
    }

    @Test
    fun ignoresBrokenFile() {
        val dir = Files.createTempDirectory("chats").toFile()
        dir.resolve("chat-bad.json").writeText("{ not json")
        val store = ConversationStore(dir)
        assertTrue(store.summaries().isEmpty())
    }
}
