package dev.xiaoman.app.session

import dev.xiaoman.api.Action
import dev.xiaoman.api.Agent
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AgentListener
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.ApprovalScope
import dev.xiaoman.api.StopSignal
import dev.xiaoman.app.RunIds
import dev.xiaoman.hands.MutableStopSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionTest {
    private val traced = mutableListOf<AgentEvent>()
    private val trace = AgentListener { traced += it }

    private fun TestScope.session(agent: Agent?) = AgentSession(
        scope = backgroundScope,
        agent = { agent },
        runIds = RunIds(),
        stopSignal = MutableStopSignal(),
        trace = trace,
    )

    /** Answers like XiaomanAgent: asks once through the session, then finishes. */
    private class AskingAgent(private val users: () -> AgentSession) : Agent {
        val gate = CompletableDeferred<Unit>()

        override suspend fun handle(userMessage: String, listener: AgentListener, stop: StopSignal) {
            val answer = users().ask("t1", "去哪里？", false)
            listener.onEvent(AgentEvent.Reply("t1", "好的，去$answer"))
        }

        override suspend fun runPhoneTask(goal: String, skillName: String?, listener: AgentListener, stop: StopSignal): AgentEvent.PhoneTaskFinished {
            listener.onEvent(AgentEvent.PhoneTaskStarted("ignored", goal, skillName))
            gate.await()
            throw IllegalStateException("boom")
        }
    }

    @Test fun `debug run without models still ends with terminal events`() = runTest(UnconfinedTestDispatcher()) {
        val s = session(null)
        assertFalse(s.runPhoneTask("打开设置", null, "r1"))
        assertEquals(listOf("Failed", "PhoneTaskFinished"), traced.map { it::class.simpleName })
        assertTrue(traced.all { it.taskId == "r1" })
    }

    @Test fun `next chat input answers the agent's question`() = runTest(UnconfinedTestDispatcher()) {
        lateinit var s: AgentSession
        s = session(AskingAgent { s })
        assertIs<AgentSession.Submit.Started>(s.submit("打车"))
        assertNotNull(s.question.value)
        assertIs<AgentSession.Submit.Answered>(s.submit("家"))
        advanceUntilIdle()
        assertEquals("好的，去家", (traced.last() as AgentEvent.Reply).text)
        assertFalse(s.busy.value)
    }

    @Test fun `a crashed task is closed with Failed and PhoneTaskFinished`() = runTest(UnconfinedTestDispatcher()) {
        lateinit var s: AgentSession
        val agent = AskingAgent { s }
        s = session(agent)
        assertTrue(s.runPhoneTask("goal", null, "r2"))
        assertFalse(s.runPhoneTask("second", null, "r3")) // busy: r3 is closed right away
        assertEquals(listOf("Failed", "PhoneTaskFinished"), traced.filter { it.taskId == "r3" }.map { it::class.simpleName })
        agent.gate.complete(Unit)
        advanceUntilIdle()
        val r2 = traced.filter { it.taskId == "r2" }.map { it::class.simpleName }
        assertEquals(listOf("Failed", "PhoneTaskFinished"), r2)
        assertFalse(s.busy.value)
    }

    @Test fun `incognito chats are never written and leave no trace`() = runTest(UnconfinedTestDispatcher()) {
        val store = ConversationStore(Files.createTempDirectory("chats").toFile())
        val s = AgentSession(
            scope = backgroundScope,
            agent = { null },
            runIds = RunIds(),
            stopSignal = MutableStopSignal(),
            trace = trace,
            store = store,
        )
        assertEquals(AgentSession.Submit.NotConfigured, s.submit("保存的对话"))
        val savedId = s.currentConversationId.value
        awaitCondition("first chat saved") { store.summaries().size == 1 }

        assertTrue(s.setIncognito(true))
        assertTrue(s.incognito.value)
        val privateId = s.currentConversationId.value
        assertTrue(privateId != savedId)

        assertEquals(AgentSession.Submit.NotConfigured, s.submit("私密问题"))
        // Saving runs on Dispatchers.IO: give it time to prove it does not happen.
        Thread.sleep(300)
        assertEquals(1, store.summaries().size) // nothing new reached the disk
        assertNull(store.load(privateId))

        assertTrue(s.setIncognito(false))
        assertFalse(s.incognito.value)
        assertEquals(savedId, s.currentConversationId.value)
        assertTrue(s.items.value.none { it is ChatItem.User && it.text == "私密问题" })
        assertEquals(1, store.summaries().size)
    }

    @Test fun `leaving incognito deletes every chat it created`() = runTest(UnconfinedTestDispatcher()) {
        val store = ConversationStore(Files.createTempDirectory("chats").toFile())
        val s = AgentSession(
            scope = backgroundScope,
            agent = { null },
            runIds = RunIds(),
            stopSignal = MutableStopSignal(),
            trace = trace,
            store = store,
        )
        assertEquals(AgentSession.Submit.NotConfigured, s.submit("进入无痕前"))
        val savedId = s.currentConversationId.value
        awaitCondition("pre-incognito chat saved") { store.summaries().size == 1 }

        assertTrue(s.setIncognito(true))
        assertEquals(1, s.incognitoChatCount.value)
        val firstPrivate = s.currentConversationId.value
        assertEquals(AgentSession.Submit.NotConfigured, s.submit("私密一"))
        assertTrue(s.newConversation())
        assertEquals(2, s.incognitoChatCount.value)
        val secondPrivate = s.currentConversationId.value
        assertEquals(AgentSession.Submit.NotConfigured, s.submit("私密二"))
        Thread.sleep(300)
        assertEquals(1, store.summaries().size)

        assertTrue(s.setIncognito(false))
        assertFalse(s.incognito.value)
        assertEquals(0, s.incognitoChatCount.value)
        // Both private chats are gone, the chat from before incognito is untouched.
        assertNull(store.load(firstPrivate))
        assertNull(store.load(secondPrivate))
        assertEquals(listOf(savedId), store.summaries().map { it.id })
        assertEquals(savedId, s.currentConversationId.value)
    }

    /** Persistence runs on Dispatchers.IO, outside the test dispatcher. */
    private fun awaitCondition(what: String, timeoutMs: Long = 3_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun `approval card answers and stop denies`() = runTest(UnconfinedTestDispatcher()) {
        val s = session(null)
        val request = ApprovalRequest("t", Action.Tap(nodeId = 1, target = "支付"), "确认支付？", "confirmation word '支付'")
        val first = backgroundScope.launch { assertEquals(ApprovalAnswer(true, ApprovalScope.TASK), s.request(request)) }
        val pending = assertNotNull(s.approval.value)
        s.answerApproval(ApprovalAnswer(false), approvalId = pending.id + 100) // stale id: ignored
        s.answerApproval(ApprovalAnswer(true, ApprovalScope.TASK), approvalId = pending.id)
        first.join()
        assertEquals(null, s.approval.value)
        assertTrue(s.items.value.last() is ChatItem.Progress)
    }
}
