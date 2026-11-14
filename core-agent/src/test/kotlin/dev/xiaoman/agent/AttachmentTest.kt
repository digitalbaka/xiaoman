package dev.xiaoman.agent

import dev.xiaoman.api.ChatAttachment
import dev.xiaoman.api.ContentPart
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentTest {
    @Test
    fun textAndBinaryFilesBecomeOneUserMessage() = runTest {
        val h = Harness(chat = ScriptedLlm().reply("收到"))
        h.agent.handle(
            "帮我看看",
            listOf(
                ChatAttachment.Text("笔记.md", "# 标题\n正文内容"),
                ChatAttachment.File("photo.bin", 2048),
            ),
            h.events,
            h.stop,
        )

        val user = h.chat.requests.first().messages.last()
        assertTrue(user.text.contains("帮我看看"))
        assertTrue(user.text.contains("【文件：笔记.md】"))
        assertTrue(user.text.contains("# 标题"))
        assertTrue(user.text.contains("photo.bin"))
        assertTrue(user.text.contains("2 KB"))
        assertTrue(user.parts.none { it is ContentPart.ImagePng })
    }

    @Test
    fun imagesAreSentAsPngPartsWithANameInTheText() = runTest {
        val h = Harness(chat = ScriptedLlm().reply("这是一只猫"))
        h.agent.handle(
            "",
            listOf(ChatAttachment.Image("猫.png", byteArrayOf(9, 8, 7))),
            h.events,
            h.stop,
        )

        val user = h.chat.requests.first().messages.last()
        assertEquals(1, user.parts.filterIsInstance<ContentPart.ImagePng>().size)
        assertTrue(user.text.contains("附图片：猫.png"))
        assertEquals(true, user.parts.any { it is ContentPart.Text && it.text.contains("请看看我发的图片") })
    }
}
