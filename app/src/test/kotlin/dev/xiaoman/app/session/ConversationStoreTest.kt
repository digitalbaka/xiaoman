package dev.xiaoman.app.session

import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
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
    fun ignoresBrokenFile() {
        val dir = Files.createTempDirectory("chats").toFile()
        dir.resolve("chat-bad.json").writeText("{ not json")
        val store = ConversationStore(dir)
        assertTrue(store.summaries().isEmpty())
    }
}
