package dev.xiaoman.agent

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.InstalledSkillInfo
import dev.xiaoman.api.SkillAction
import dev.xiaoman.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DRAFT = """---\nname: daily-review\ndescription: 每日复盘\n---\n每天晚上问用户今天做成了哪三件事。"""

class SkillDraftTest {
    @Test
    fun draftIsProposedButNotWritten() = runTest {
        val installer = FakeInstaller()
        val h = Harness(
            chat = ScriptedLlm()
                .tools(ToolCall("1", "skill_draft", """{"name":"daily-review","skill_md":"$DRAFT"}"""))
                .reply("草稿写好了，请确认。"),
            installer = installer,
        )
        h.agent.handle("帮我加一个技能，我说复盘就问三个问题", h.events, h.stop)

        val proposed = h.events.of<AgentEvent.SkillProposed>().single()
        assertTrue(proposed.action is SkillAction.Save)
        assertEquals("daily-review", proposed.action.name)
        assertTrue((proposed.action as SkillAction.Save).markdown.contains("每日复盘"))
        assertTrue(installer.written.isEmpty())
    }

    @Test
    fun draftWithSecretsIsRejected() = runTest {
        val h = Harness(
            chat = ScriptedLlm()
                .tools(ToolCall("1", "skill_draft", """{"name":"leaky","skill_md":"---\nname: leaky\ndescription: 登录\n---\n用 sk-abcdefghijklmnopqrstuvwx 登录"}"""))
                .reply("抱歉，草稿里有密钥，我改一下。"),
        )
        h.agent.handle("加一个登录技能", h.events, h.stop)

        assertTrue(h.events.of<AgentEvent.SkillProposed>().isEmpty())
        val toolResult = h.chat.requests.last().messages.last().text
        assertTrue(toolResult.contains("不能包含"))
    }

    @Test
    fun bundledSkillCannotBeRemovedFromChat() = runTest {
        val installer = FakeInstaller()
        val h = Harness(
            chat = ScriptedLlm()
                .tools(ToolCall("1", "skill_remove", """{"name":"multi-search-engine"}"""))
                .reply("这是内置技能，删不了。"),
            skills = FakeSkills(skill("multi-search-engine")),
            installer = installer,
        )
        h.agent.handle("删掉搜索技能", h.events, h.stop)

        assertTrue(h.events.of<AgentEvent.SkillProposed>().isEmpty())
        assertTrue(installer.deleted.isEmpty())
        assertTrue(h.chat.requests.last().messages.last().text.contains("内置技能"))
    }

    @Test
    fun userSkillRemovalIsProposed() = runTest {
        val installer = FakeInstaller(mutableListOf(InstalledSkillInfo("daily-review", "复盘", true, "chat")))
        val h = Harness(
            chat = ScriptedLlm()
                .tools(ToolCall("1", "skill_remove", """{"name":"daily-review"}"""))
                .reply("请确认删除。"),
            installer = installer,
        )
        h.agent.handle("删掉复盘技能", h.events, h.stop)

        val proposed = h.events.of<AgentEvent.SkillProposed>().single()
        assertEquals(SkillAction.Delete("daily-review"), proposed.action)
        assertTrue(installer.deleted.isEmpty())
    }
}
