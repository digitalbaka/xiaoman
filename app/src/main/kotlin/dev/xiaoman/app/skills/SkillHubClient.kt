package dev.xiaoman.app.skills

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One skill listed by the SkillHub store. [slug] is what the download endpoint expects. */
data class SkillHubSkill(
    val slug: String,
    val name: String,
    val summary: String,
    val version: String?,
    val namespace: String?,
    val source: String?,
    val downloads: Long?,
) {
    /** `@owner/slug` when the store attributed the skill to a namespace. */
    val coordinate: String
        get() {
            val ns = namespace?.trim()
            return when {
                ns.isNullOrEmpty() -> slug
                ns.startsWith("@") -> ns
                else -> "@$ns/$slug"
            }
        }

    /** `SkillHub · @owner/slug · v1.2.3`, stored next to the installed skill for display. */
    fun originLabel(): String = buildString {
        append("SkillHub · ").append(coordinate)
        if (!version.isNullOrBlank()) append(" · v").append(version)
    }
}

/** A store/network failure with a message that is safe to show to the user as-is. */
class SkillHubException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Read-only client for the SkillHub store (https://skillhub.cn).
 *
 * The published install guide only offers a bash/Python CLI, which cannot run on Android. These are
 * the same public HTTP endpoints that CLI calls (`skills_store_cli.py`), used anonymously:
 *  - search:   GET /api/v1/search?q=&limit=
 *  - showcase: GET /api/v1/showcase/hot
 *  - download: GET /api/v1/download?slug=   (falls back to the static COS zip the CLI uses)
 *
 * Only `HttpURLConnection`/`org.json` are used, so this also runs on API 25.
 */
class SkillHubClient(
    private val baseUrl: String = "https://api.skillhub.cn",
    private val fallbackZipBase: String = "https://skillhub-1388575217.cos.ap-guangzhou.myqcloud.com/skills",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun search(query: String, limit: Int = 30): List<SkillHubSkill> = withContext(io) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val url = "$baseUrl/api/v1/search?q=${encode(q)}&limit=${limit.coerceIn(1, 100)}"
        val body = getText(url)
        parseList(body, "results")
    }

    /** The store's download ranking, shown before the user searches. */
    suspend fun hot(limit: Int = 30): List<SkillHubSkill> = withContext(io) {
        parseList(getText("$baseUrl/api/v1/showcase/hot"), "skills").take(limit.coerceAtLeast(1))
    }

    /**
     * Downloads the zip for [slug]. Throws [SkillHubException] with both failure reasons instead of
     * returning a non-zip body, so a bad slug or a captive-portal page never reaches the installer.
     */
    suspend fun download(slug: String): ByteArray = withContext(io) {
        if (slug.isBlank()) throw SkillHubException("技能标识为空，无法下载")
        val primary = "$baseUrl/api/v1/download?slug=${encode(slug)}"
        val fallback = "$fallbackZipBase/${encodePath(slug)}.zip"
        val first = runCatching { getZip(primary) }
        first.getOrNull()?.let { return@withContext it }
        val firstError = first.exceptionOrNull()
        val second = runCatching { getZip(fallback) }
        second.getOrNull()?.let { return@withContext it }
        val secondError = second.exceptionOrNull()
        throw SkillHubException(
            "下载失败：${firstError?.message ?: "接口不可用"}；备用地址：${secondError?.message ?: "不可用"}",
            firstError,
        )
    }

    private fun getText(url: String): String {
        val response = request(url) { it.readTextUtf8() }
        return response.value ?: throw SkillHubException(httpMessage(response.code, response.error))
    }

    private fun getZip(url: String): ByteArray {
        val response = request(url) { it.inputStream.readBytesLimited(MAX_ZIP_BYTES) }
        val bytes = response.value ?: throw SkillHubException("HTTP ${response.code}")
        if (!isZip(bytes)) {
            val hint = runCatching { String(bytes.copyOfRange(0, minOf(bytes.size, 120)), Charsets.UTF_8) }
                .getOrDefault("")
                .trim()
            throw SkillHubException(if (hint.isNotEmpty()) "服务器返回的不是 zip：$hint" else "服务器返回的不是 zip")
        }
        return bytes
    }

    private class Read<T>(val code: Int, val value: T?, val error: String?)

    private fun <T> request(url: String, read: (HttpURLConnection) -> T): Read<T> {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = try {
                (URL(current).openConnection() as HttpURLConnection)
            } catch (e: Exception) {
                throw SkillHubException("地址无效：${e.message ?: current}", e)
            }
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json, application/zip, */*")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val code = try {
                    connection.responseCode
                } catch (e: IOException) {
                    throw SkillHubException(networkMessage(e), e)
                }
                if (code in 300..399) {
                    current = connection.getHeaderField("Location")
                        ?: throw SkillHubException("HTTP $code 缺少跳转地址")
                    return@repeat
                }
                if (code in 200..299) return Read(code, read(connection), null)
                val error = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.take(300)
                }.getOrNull()
                return Read(code, null, error)
            } finally {
                connection.disconnect()
            }
        }
        throw SkillHubException("跳转次数过多，已停止")
    }

    private fun parseList(body: String, arrayKey: String): List<SkillHubSkill> {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw SkillHubException("SkillHub 返回的不是有效 JSON：${body.take(80).ifBlank { "空响应" }}", e)
        }
        val array: JSONArray = json.optJSONArray(arrayKey)
            ?: throw SkillHubException("SkillHub 返回格式不符合预期（缺少 \"$arrayKey\"）")
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val slug = item.str("slug") ?: return@mapNotNull null
            val namespace = item.optJSONObject("namespace")?.str("canonicalName") ?: item.str("namespace")
            SkillHubSkill(
                slug = slug,
                name = firstNonBlank(item.str("displayName"), item.str("name"), slug),
                summary = firstNonBlank(
                    item.str("summary"),
                    item.str("description_zh"),
                    item.str("description"),
                ).let { it.replace(WHITESPACE, " ").trim() },
                version = item.str("version"),
                namespace = namespace,
                source = item.str("source"),
                downloads = item.optLong("downloads", -1).takeIf { it >= 0 },
            )
        }
    }

    private fun httpMessage(code: Int, body: String?): String {
        val hint = when (code) {
            401, 403 -> "（需要授权）"
            404 -> "（接口不存在或技能已下架）"
            in 500..599 -> "（SkillHub 服务异常）"
            else -> ""
        }
        val detail = body?.trim().orEmpty().take(120)
        return "SkillHub 返回 HTTP $code$hint" + if (detail.isNotEmpty()) "：$detail" else ""
    }

    private fun networkMessage(e: IOException): String = when {
        e is java.net.SocketTimeoutException -> "连接超时，请检查网络后重试"
        e is java.net.UnknownHostException -> "无法解析 api.skillhub.cn，请检查网络或 DNS"
        else -> "网络错误：${e.message ?: e.javaClass.simpleName}"
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encodePath(value: String): String =
        value.split('/').joinToString("/") { encode(it) }

    private fun HttpURLConnection.readTextUtf8(): String =
        inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val out = StringBuilder()
            val buffer = CharArray(4096)
            var total = 0
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_JSON_BYTES) throw SkillHubException("返回内容太大（上限 ${MAX_JSON_BYTES / 1024}KB）")
                out.append(buffer, 0, n)
            }
            out.toString()
        }

    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw SkillHubException("安装包太大（上限 ${max / 1024 / 1024}MB）")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
        const val MAX_ZIP_BYTES = 20 * 1024 * 1024
        const val USER_AGENT = "Xiaoman-SkillHub/1.0"
        val WHITESPACE = Regex("\\s+")
    }
}
