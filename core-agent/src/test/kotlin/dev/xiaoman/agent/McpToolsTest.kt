package dev.xiaoman.agent

import dev.xiaoman.api.ChatRole
import dev.xiaoman.api.McpConfirmPolicy
import dev.xiaoman.api.McpHub
import dev.xiaoman.api.McpServerConnection
import dev.xiaoman.api.McpTool
import dev.xiaoman.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpToolsTest {

    private class FakeServer(private val tools: List<McpTool>) : McpServerConnection {
        override val id = "local"
        override val title = "本地测试"
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun listTools() = tools
        override suspend fun callTool(tool: String, arguments: JsonObject): String {
            calls += tool to arguments
            return "ok:$tool"
        }
    }

    private val searchTool = McpTool(
        name = "search_notes",
        description = "搜索测试笔记",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string") }) })
        },
        readOnly = true,
    )
    private val createTool = McpTool(
        name = "create_note",
        description = "创建测试笔记",
        readOnly = false,
    )

    private fun harness(server: FakeServer, policy: McpConfirmPolicy, answers: FakeUsers) = Harness(
        users = answers,
        mcp = object : McpHub {
            override suspend fun servers() = listOf(server)
        },
        mcpConfirm = policy,
    )

    @Test
    fun toolsAreOfferedUnderMcpPrefix() = runTest {
        val server = FakeServer(listOf(searchTool))
        val h = harness(server, McpConfirmPolicy.MUTATING, FakeUsers())
        h.chat.reply("好")

        h.agent.handle("你好", h.events, h.stop)

        val names = h.chat.requests[0].tools.map { it.name }
        assertTrue("mcp_local_search_notes" in names)
        assertEquals("【本地测试】搜索测试笔记", h.chat.requests[0].tools.first { it.name == "mcp_local_search_notes" }.description)
    }

    @Test
    fun readOnlyToolRunsWithoutAskingUnderMutatingPolicy() = runTest {
        val server = FakeServer(listOf(searchTool))
        val users = FakeUsers("好")
        val h = harness(server, McpConfirmPolicy.MUTATING, users)
        h.chat.tools(ToolCall("c1", "mcp_local_search_notes", "{}")).reply("结果如下")

        h.agent.handle("搜索 hello", h.events, h.stop)

        assertTrue(users.asked.isEmpty())
        assertEquals(listOf("search_notes"), server.calls.map { it.first })
        assertEquals("ok:search_notes", h.chat.requests[1].messages.last { it.role == ChatRole.TOOL }.text)
    }

    @Test
    fun mutatingToolAsksAndRunsAfterConfirm() = runTest {
        val server = FakeServer(listOf(createTool))
        val users = FakeUsers("确认")
        val h = harness(server, McpConfirmPolicy.MUTATING, users)
        h.chat.tools(ToolCall("c1", "mcp_local_create_note", """{"title":"demo"}""")).reply("已创建")

        h.agent.handle("创建笔记 demo", h.events, h.stop)

        assertEquals(1, users.asked.size)
        assertTrue("create_note" in users.asked.single().first)
        assertEquals(listOf("create_note"), server.calls.map { it.first })
    }

    @Test
    fun denialCancelsTheCall() = runTest {
        val server = FakeServer(listOf(createTool))
        val h = harness(server, McpConfirmPolicy.MUTATING, FakeUsers("不要"))
        h.chat.tools(ToolCall("c1", "mcp_local_create_note", """{"title":"demo"}""")).reply("好的，没执行")

        h.agent.handle("创建笔记 demo", h.events, h.stop)

        assertTrue(server.calls.isEmpty())
        val result = h.chat.requests[1].messages.last { it.role == ChatRole.TOOL }.text
        assertTrue("\"cancelled\":true" in result)
    }

    @Test
    fun alwaysPolicyAsksEvenForReadOnlyTools() = runTest {
        val server = FakeServer(listOf(searchTool))
        val users = FakeUsers("可以")
        val h = harness(server, McpConfirmPolicy.ALWAYS, users)
        h.chat.tools(ToolCall("c1", "mcp_local_search_notes", "{}")).reply("结果如下")

        h.agent.handle("搜索 hello", h.events, h.stop)

        assertEquals(1, users.asked.size)
        assertEquals(listOf("search_notes"), server.calls.map { it.first })
    }

    @Test
    fun neverPolicySkipsConfirmationForMutatingTools() = runTest {
        val server = FakeServer(listOf(createTool))
        val users = FakeUsers("确认")
        val h = harness(server, McpConfirmPolicy.NEVER, users)
        h.chat.tools(ToolCall("c1", "mcp_local_create_note", "{}")).reply("已创建")

        h.agent.handle("创建笔记", h.events, h.stop)

        assertTrue(users.asked.isEmpty())
        assertEquals(listOf("create_note"), server.calls.map { it.first })
    }

    @Test
    fun serverFailureBecomesToolError() = runTest {
        val failing = object : McpServerConnection {
            override val id = "local"
            override val title = "本地测试"
            override suspend fun listTools() = listOf(createTool)
            override suspend fun callTool(tool: String, arguments: JsonObject): String =
                throw dev.xiaoman.api.McpException("连不上")
        }
        val h = Harness(
            users = FakeUsers(),
            mcp = object : McpHub {
                override suspend fun servers() = listOf(failing)
            },
            mcpConfirm = McpConfirmPolicy.NEVER,
        )
        h.chat.tools(ToolCall("c1", "mcp_local_create_note", "{}")).reply("失败")

        h.agent.handle("创建", h.events, h.stop)

        val result = h.chat.requests[1].messages.last { it.role == ChatRole.TOOL }.text
        assertTrue("连不上" in result)
    }
}
