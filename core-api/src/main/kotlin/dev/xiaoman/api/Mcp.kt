package dev.xiaoman.api

import kotlinx.serialization.json.JsonObject

/** When the agent asks the user before running an MCP tool. */
enum class McpConfirmPolicy {
    /** Ask only for tools that look like they change data (default). */
    MUTATING,

    /** Ask before every call. */
    ALWAYS,

    /** Never ask. */
    NEVER,
}

/**
 * One tool exposed by an MCP server. [inputSchema] is the tool's JSON Schema object; a null one is
 * replaced with an empty object schema by the agent.
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject? = null,
    /** From MCP tool annotations; null when the server does not say. */
    val readOnly: Boolean? = null,
)

/** A connected MCP server. [id] is unique in the app and becomes part of the tool name. */
interface McpServerConnection {
    val id: String
    val title: String

    /** @throws McpException when the server cannot be reached or answers with an error. */
    suspend fun listTools(): List<McpTool>

    /** Calls one tool with a JSON argument object and returns its text result. */
    suspend fun callTool(tool: String, arguments: JsonObject): String
}

/** The app supplies the currently enabled MCP servers; tools are discovered lazily per chat turn. */
fun interface McpHub {
    suspend fun servers(): List<McpServerConnection>
}

/** Transport or protocol failure of an MCP server; the message is shown to the user/model. */
class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)
