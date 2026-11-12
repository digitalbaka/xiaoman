package dev.xiaoman.app.mcp

import dev.xiaoman.agent.HttpMcpConnection
import dev.xiaoman.api.McpHub
import dev.xiaoman.api.McpServerConnection
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.McpBuiltin
import dev.xiaoman.app.settings.McpCustomServer
import dev.xiaoman.app.settings.McpServiceConfig
import dev.xiaoman.app.settings.McpSettings
import java.net.URLEncoder

/**
 * Builds MCP connections from the saved settings: enabled built-in services first, then the
 * user-added servers. Connections are cached so an MCP session survives across chat turns.
 */
class McpRegistry(private val settings: () -> AppSettings) : McpHub {

    private var cachedFor: McpSettings? = null
    private var cached: List<McpServerConnection> = emptyList()

    override suspend fun servers(): List<McpServerConnection> = connections(settings().mcp)

    @Synchronized
    fun connections(mcp: McpSettings): List<McpServerConnection> {
        if (cachedFor == mcp) return cached
        val list = buildList {
            // A keyed service stays off the tool list until the user fills the key in.
            mcp.enabledBuiltins.forEach { (builtin, config) ->
                if (!builtin.needsKey || config.apiKey.isNotBlank()) add(connection(builtin, config))
            }
            mcp.custom.filter { it.usable }.forEach { add(connection(it)) }
        }
        cachedFor = mcp
        cached = list
        return list
    }

    companion object {
        fun connection(builtin: McpBuiltin, config: McpServiceConfig): McpServerConnection {
            var url = config.resolvedUrl(builtin)
            val headers = mutableMapOf<String, String>()
            if (builtin.needsKey && config.apiKey.isNotBlank()) {
                val key = config.apiKey.trim()
                if (builtin.keyParam != null) {
                    val sep = if (url.contains('?')) "&" else "?"
                    url += sep + builtin.keyParam + "=" + URLEncoder.encode(key, Charsets.UTF_8)
                } else {
                    headers["Authorization"] = if (key.startsWith("Bearer ", ignoreCase = true)) key else "Bearer $key"
                }
            }
            return HttpMcpConnection(builtin.id, builtin.title, url.trim(), headers)
        }

        fun connection(server: McpCustomServer): McpServerConnection {
            val headers = if (server.authorization.isBlank()) {
                emptyMap()
            } else {
                mapOf("Authorization" to server.authorization.trim())
            }
            return HttpMcpConnection(
                id = server.id,
                title = server.name.trim().ifEmpty { "自定义服务" },
                endpoint = server.url.trim(),
                headers = headers,
            )
        }
    }
}
