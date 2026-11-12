package dev.xiaoman.app.settings

import dev.xiaoman.api.McpConfirmPolicy
import java.util.UUID

/** One built-in MCP service offered in Settings. Only configuration and connectivity live here. */
data class McpBuiltin(
    val id: String,
    val group: String,
    val title: String,
    val description: String,
    /** False for services that work without an API key. */
    val needsKey: Boolean,
    val defaultUrl: String,
    /** Where the user can apply for a key; shown under the key field. */
    val keyHint: String = "",
    /** Appended as `?param=key` when set (e.g. AMap), otherwise the key is sent as a Bearer token. */
    val keyParam: String? = null,
    val recommended: Boolean = false,
)

/** One saved built-in service. A blank [url] means the catalog default. */
data class McpServiceConfig(
    val id: String,
    val enabled: Boolean = false,
    val apiKey: String = "",
    val url: String = "",
) {
    fun resolvedUrl(builtin: McpBuiltin): String = url.trim().ifEmpty { builtin.defaultUrl }
}

/** A user-added remote MCP server. */
data class McpCustomServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val authorization: String = "",
) {
    val usable: Boolean get() = url.trim().startsWith("http", ignoreCase = true)
}

data class McpSettings(
    val services: List<McpServiceConfig> = emptyList(),
    val custom: List<McpCustomServer> = emptyList(),
    val confirmPolicy: McpConfirmPolicy = McpConfirmPolicy.MUTATING,
) {
    fun config(id: String): McpServiceConfig = services.firstOrNull { it.id == id } ?: McpServiceConfig(id)

    fun isEnabled(id: String): Boolean = config(id).enabled

    val enabledBuiltins: List<Pair<McpBuiltin, McpServiceConfig>>
        get() = McpBuiltins.all.mapNotNull { b -> config(b.id).takeIf { it.enabled }?.let { b to it } }

    val enabledCount: Int
        get() = enabledBuiltins.size + custom.count { it.usable }

    /** Values to mask in traces and logs. */
    fun secrets(): List<String> =
        (enabledBuiltins.map { it.second.apiKey } + custom.filter { it.usable }.map { it.authorization })
            .filter { it.isNotBlank() }

    /** Applies the saved values over the catalog, keeping the catalog order and adding new services. */
    fun normalized(): McpSettings {
        val byId = services.associateBy { it.id }
        val merged = McpBuiltins.all.map { b ->
            byId[b.id] ?: McpServiceConfig(b.id)
        }
        return copy(services = merged)
    }
}

/** The built-in catalog. Endpoints are editable in the UI, so a changed vendor URL can be fixed there. */
object McpBuiltins {
    const val GROUP_TRAVEL = "出行"
    const val GROUP_LIFE = "生活"
    const val GROUP_WORK = "效率"
    const val GROUP_NEWS = "资讯"

    val all: List<McpBuiltin> = listOf(
        McpBuiltin(
            id = "amap",
            group = GROUP_TRAVEL,
            title = "高德地图",
            description = "查地点、周边搜索和路线规划",
            needsKey = true,
            defaultUrl = "https://mcp.amap.com/mcp",
            keyHint = "在高德开放平台申请 Web 服务 Key，填在下方即可",
            keyParam = "key",
            recommended = true,
        ),
        McpBuiltin(
            id = "tencent-map",
            group = GROUP_TRAVEL,
            title = "腾讯地图",
            description = "地点检索、路线规划和坐标转换",
            needsKey = true,
            defaultUrl = "https://mcp.map.qq.com/mcp",
            keyHint = "在腾讯位置服务控制台申请 Key",
            keyParam = "key",
        ),
        McpBuiltin(
            id = "didi",
            group = GROUP_TRAVEL,
            title = "滴滴出行",
            description = "叫车、查订单和行程信息",
            needsKey = true,
            defaultUrl = "https://mcp.didichuxing.com/mcp",
            keyHint = "在滴滴开放平台申请应用凭证",
        ),
        McpBuiltin(
            id = "tuniu-hotel",
            group = GROUP_TRAVEL,
            title = "途牛酒店",
            description = "按城市和日期查酒店房型与价格",
            needsKey = true,
            defaultUrl = "https://mcp.tuniu.com/hotel/mcp",
            keyHint = "在途牛开放平台申请 Key",
        ),
        McpBuiltin(
            id = "tuniu-flight",
            group = GROUP_TRAVEL,
            title = "途牛机票",
            description = "查航班、余票和价格",
            needsKey = true,
            defaultUrl = "https://mcp.tuniu.com/flight/mcp",
            keyHint = "在途牛开放平台申请 Key",
        ),
        McpBuiltin(
            id = "mcdonalds",
            group = GROUP_LIFE,
            title = "麦当劳",
            description = "查菜单、优惠和附近门店",
            needsKey = false,
            defaultUrl = "https://mcp.mcd.cn/mcp",
            recommended = true,
        ),
        McpBuiltin(
            id = "dida365",
            group = GROUP_WORK,
            title = "滴答清单",
            description = "读取和创建任务、清单",
            needsKey = true,
            defaultUrl = "https://mcp.dida365.com/mcp",
            keyHint = "在滴答清单开放平台获取访问令牌",
        ),
        McpBuiltin(
            id = "metaso",
            group = GROUP_NEWS,
            title = "秘塔搜索",
            description = "联网搜索并返回带来源的结果",
            needsKey = true,
            defaultUrl = "https://metaso.cn/api/mcp",
            keyHint = "在秘塔开放平台申请 API Key",
            keyParam = "key",
            recommended = true,
        ),
        McpBuiltin(
            id = "ai-news",
            group = GROUP_NEWS,
            title = "AI 热点资讯",
            description = "聚合当天 AI 领域的新闻热点",
            needsKey = false,
            defaultUrl = "https://mcp.aibase.com/mcp",
        ),
    )

    val groups: List<String> = all.map { it.group }.distinct()

    /** "推荐组合" fills the switchboard with a starter set. */
    val recommendedIds: List<String> = all.filter { it.recommended }.map { it.id }

    fun byId(id: String): McpBuiltin? = all.firstOrNull { it.id == id }
}
