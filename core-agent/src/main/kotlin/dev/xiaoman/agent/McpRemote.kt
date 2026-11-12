package dev.xiaoman.agent

import dev.xiaoman.api.McpException
import dev.xiaoman.api.McpServerConnection
import dev.xiaoman.api.McpTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * A remote MCP server over "Streamable HTTP" (JSON-RPC 2.0). Works on Android 7.1: plain
 * [HttpURLConnection] and kotlinx.serialization only, no OkHttp.
 *
 * The handshake (`initialize` → `notifications/initialized`) is done lazily before the first call
 * and repeated when the server reports an expired session.
 */
class HttpMcpConnection(
    override val id: String,
    override val title: String,
    private val endpoint: String,
    private val headers: Map<String, String> = emptyMap(),
) : McpServerConnection {
    private val mutex = Mutex()
    private val ids = AtomicLong(0)
    private var sessionId: String? = null
    private var initialized = false
    private var cachedTools: List<McpTool>? = null
    private var cachedToolsAt = 0L

    override suspend fun listTools(): List<McpTool> = mutex.withLock {
        val cached = cachedTools
        if (cached != null && System.currentTimeMillis() - cachedToolsAt < TOOLS_CACHE_MS) return@withLock cached
        withContext(Dispatchers.IO) {
            ensureInitialized()
            val result = rpc("tools/list", buildJsonObject { }, retryOnSessionLoss = true).objectAt("result")
            val tools = result["tools"] as? JsonArray ?: return@withContext emptyList()
            val parsed = tools.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val name = JsonText.string(o, "name") ?: return@mapNotNull null
                val annotations = o["annotations"] as? JsonObject
                McpTool(
                    name = name,
                    description = JsonText.string(o, "description").orEmpty(),
                    inputSchema = o["inputSchema"] as? JsonObject,
                    readOnly = annotations?.let { JsonText.bool(it, "readOnlyHint") },
                )
            }
            cachedTools = parsed
            cachedToolsAt = System.currentTimeMillis()
            parsed
        }
    }

    override suspend fun callTool(tool: String, arguments: JsonObject): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureInitialized()
            val params = buildJsonObject {
                put("name", tool)
                put("arguments", arguments)
            }
            val message = rpc("tools/call", params, retryOnSessionLoss = true).objectAt("result")
            val content = message["content"] as? JsonArray
            val texts = content.orEmpty().mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                if (JsonText.string(o, "type") == "text") JsonText.string(o, "text") else null
            }
            val text = texts.joinToString("\n").ifBlank {
                (message["structuredContent"] ?: JsonNull).toString().takeIf { it != "null" } ?: ""
            }
            val truncated = if (text.length > MAX_RESULT_CHARS) text.take(MAX_RESULT_CHARS) + "\n…（结果过长已截断）" else text
            if (JsonText.bool(message, "isError") == true) {
                throw McpException("MCP 工具返回错误：${truncated.ifBlank { "（无内容）" }}")
            }
            truncated.ifBlank { "（工具没有返回内容）" }
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        rpc(
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject {
                    put("name", "xiaoman")
                    put("version", "1.0")
                })
            },
            retryOnSessionLoss = false,
        ).objectAt("result")
        notify("notifications/initialized")
        initialized = true
    }

    private fun notify(method: String) {
        post(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
            },
            expectBody = false,
        )
    }

    private fun rpc(method: String, params: JsonObject, retryOnSessionLoss: Boolean): JsonObject {
        val id = ids.incrementAndGet()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val message = try {
            post(body, expectBody = true)
        } catch (e: SessionLostException) {
            if (!retryOnSessionLoss) throw e
            initialized = false
            sessionId = null
            ensureInitialized()
            return rpc(method, params, retryOnSessionLoss = false)
        }
        val error = message["error"] as? JsonObject
        if (error != null) {
            val text = JsonText.string(error, "message") ?: error.toString()
            val code = (error["code"] as? JsonPrimitive)?.content
            throw McpException("$method 失败${if (code != null) "（$code）" else ""}：$text")
        }
        if (message["result"] == null) throw McpException("$method 的响应里没有 result")
        return message
    }

    private fun post(body: JsonObject, expectBody: Boolean): JsonObject {
        val connection = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw McpException("地址无效：$endpoint")
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            headers.forEach { (k, v) -> if (k.isNotBlank()) connection.setRequestProperty(k, v) }
            sessionId?.let { connection.setRequestProperty("Mcp-Session-Id", it) }
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            connection.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            if (code == 404 && sessionId != null) throw SessionLostException()
            if (code == 401 || code == 403) {
                throw McpException("认证失败（HTTP $code），请检查 Key 或 Authorization 请求头")
            }
            if (code < 200 || code >= 300) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                }.getOrDefault("").trim()
                throw McpException("服务器返回 HTTP $code${if (detail.isNotEmpty()) "：${detail.take(300)}" else ""}")
            }
            if (!expectBody) return JsonObject(emptyMap())
            val type = connection.contentType.orEmpty()
            val stream = connection.inputStream
            return if (type.contains("text/event-stream", ignoreCase = true)) {
                readSse(stream, body["id"])
            } else {
                val raw = stream.bufferedReader(Charsets.UTF_8).readText()
                parseMessage(raw) ?: throw McpException("服务器返回了无法解析的内容：${raw.take(200)}")
            }
        } catch (e: McpException) {
            throw e
        } catch (e: SessionLostException) {
            throw e
        } catch (e: Exception) {
            throw McpException(describe(e), e)
        } finally {
            connection.disconnect()
        }
    }

    /** Reads `data:` lines until the JSON-RPC answer with [id] arrives (some servers keep the stream open). */
    private fun readSse(stream: InputStream, id: JsonElement?): JsonObject {
        val reader = BufferedReader(stream.reader(Charsets.UTF_8))
        val data = StringBuilder()
        var fallback: JsonObject? = null
        var message: JsonObject? = null
        try {
            while (message == null) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) {
                    val parsed = data.toString().trim().takeIf { it.isNotEmpty() }?.let { parseMessage(it) }
                    data.setLength(0)
                    if (parsed != null) {
                        if (parsed["id"] == id) message = parsed else fallback = parsed
                    }
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
            }
            if (message == null && data.isNotEmpty()) {
                val parsed = parseMessage(data.toString().trim())
                if (parsed != null) {
                    if (parsed["id"] == id) message = parsed else fallback = parsed
                }
            }
        } finally {
            runCatching { reader.close() }
        }
        return message ?: fallback ?: throw McpException("服务器没有返回 JSON-RPC 响应")
    }

    private fun parseMessage(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        return try {
            JsonText.json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "连接超时（${e.message ?: "服务器没有响应"}）"
        is java.net.UnknownHostException -> "无法解析域名：${e.message ?: endpoint}"
        is java.net.ConnectException -> "无法连接服务器：${e.message ?: endpoint}"
        else -> "网络错误：${e.message ?: e.javaClass.simpleName}"
    }

    private fun JsonObject.objectAt(key: String): JsonObject =
        this[key] as? JsonObject ?: throw McpException("响应里缺少 $key")

    private class SessionLostException : Exception()

    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_RESULT_CHARS = 8_000
        private const val TOOLS_CACHE_MS = 5 * 60_000L
    }
}
