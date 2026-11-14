package dev.xiaoman.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Read-only page fetcher for knowledge skills (search, research). Such skills must work without the
 * accessibility service, so instead of driving a browser they read the page text directly.
 * No cookies, no scripts, no logged-in state; HTML is reduced to plain text.
 */
internal class WebFetch(
    private val maxBytes: Int = 800 * 1024,
    private val maxChars: Int = 6000,
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 15000,
) {
    suspend fun fetch(rawUrl: String): String = withContext(Dispatchers.IO) {
        val url = runCatching { URL(rawUrl.trim()) }.getOrNull()
            ?: return@withContext "抓取失败：地址不合法（$rawUrl）"
        if (url.protocol != "http" && url.protocol != "https") return@withContext "抓取失败：只支持 http/https 地址"
        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) XiaomanExtra/0.1")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            }
            val code = connection.responseCode
            if (code !in 200..299) return@withContext "抓取失败：HTTP $code（$rawUrl）"
            val type = connection.contentType.orEmpty().lowercase()
            if (!type.contains("html") && !type.contains("text/") && !type.contains("xml")) {
                return@withContext "抓取失败：不是网页文本（${type.ifBlank { "未知类型" }}）"
            }
            val bytes = readAtMost(connection) ?: return@withContext "抓取失败：读取内容出错（$rawUrl）"
            val plain = toText(String(bytes, charsetOf(type, bytes)))
            when {
                plain.isBlank() -> "抓取成功，但页面没有可读文字（$rawUrl）"
                plain.length > maxChars -> plain.take(maxChars) + "\n……（已截断，原文共 ${plain.length} 字）"
                else -> plain
            }
        } catch (e: Exception) {
            "抓取失败：${e.message ?: e::class.simpleName}"
        } finally {
            connection?.disconnect()
        }
    }

    private fun readAtMost(connection: HttpURLConnection): ByteArray? = runCatching {
        connection.inputStream.use { stream ->
            val out = ByteArrayOutputStream(32 * 1024)
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                val room = maxBytes - total
                if (room <= 0) break
                out.write(buffer, 0, minOf(n, room))
                total += minOf(n, room)
                if (n > room) break
            }
            out.toByteArray()
        }
    }.getOrNull()

    /** Header charset first, then `<meta charset>` in the first 2 KB, otherwise UTF-8. */
    private fun charsetOf(contentType: String, bytes: ByteArray): Charset {
        val header = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val meta = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        return runCatching { Charset.forName(header ?: meta ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
    }

    private fun toText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|noscript|svg|template)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|li|tr|h\\d|section|article)>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        s = s.replace(Regex("[\\t\\u000B\\f\\r ]+"), " ")
        s = s.replace(Regex("\\n\\s*\\n+"), "\n")
        return s.trim()
    }
}
