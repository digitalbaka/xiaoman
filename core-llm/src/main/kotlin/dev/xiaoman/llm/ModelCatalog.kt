package dev.xiaoman.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Fetches `GET {baseUrl}/models` from an OpenAI-compatible endpoint.
 *
 * Only model ids travel back; the API key is used for the request header and never logged.
 * [normalizeBaseUrl] is the single normalization rule shared with the on-device cache, so two
 * spellings of the same endpoint always map to the same cache entry.
 */
object ModelCatalog {

    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        timeoutSeconds: Long = 15,
    ): List<String> = listModels(baseUrl, apiKey, LlmClients.sharedHttp, timeoutSeconds)

    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        http: OkHttpClient,
        timeoutSeconds: Long = 15,
    ): List<String> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(baseUrl)
        if (base.isBlank()) throw ModelCatalogException(ModelCatalogException.Kind.NETWORK, null, "Empty base URL")
        val client = http.newBuilder()
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        var notFound: ModelCatalogException? = null
        for (url in candidateUrls(base)) {
            val response = try {
                fetch(client, url, apiKey)
            } catch (e: InterruptedIOException) {
                throw ModelCatalogException(ModelCatalogException.Kind.TIMEOUT, null, "Timed out fetching $url", e)
            } catch (e: IOException) {
                throw ModelCatalogException(
                    ModelCatalogException.Kind.NETWORK, null,
                    "Request to $url failed: ${e.message ?: e.javaClass.simpleName}", e,
                )
            } catch (e: IllegalArgumentException) {
                throw ModelCatalogException(
                    ModelCatalogException.Kind.NETWORK, null,
                    "Invalid URL $url: ${e.message}", e,
                )
            }
            when {
                response.code in 200..299 -> return@withContext parse(response.body, url)
                response.code == 404 -> notFound = ModelCatalogException(
                    ModelCatalogException.Kind.NOT_FOUND, 404, "HTTP 404 from $url",
                )
                response.code == 401 -> throw ModelCatalogException(
                    ModelCatalogException.Kind.UNAUTHORIZED, 401, "HTTP 401 from $url",
                )
                response.code == 403 -> throw ModelCatalogException(
                    ModelCatalogException.Kind.FORBIDDEN, 403, "HTTP 403 from $url",
                )
                response.code >= 500 -> throw ModelCatalogException(
                    ModelCatalogException.Kind.SERVER, response.code, "HTTP ${response.code} from $url",
                )
                else -> throw ModelCatalogException(
                    ModelCatalogException.Kind.HTTP, response.code, "HTTP ${response.code} from $url",
                )
            }
        }
        throw notFound ?: ModelCatalogException(ModelCatalogException.Kind.NETWORK, null, "No usable URL for $base")
    }

    /**
     * Trims, drops a trailing `/chat/completions` (users sometimes paste the whole endpoint),
     * strips query/fragment and lowercases scheme + host. The path keeps its case.
     */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().substringBefore('#').substringBefore('?').trimEnd('/')
        if (url.endsWith("/chat/completions", ignoreCase = true)) {
            url = url.dropLast("/chat/completions".length).trimEnd('/')
        }
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return url
        val scheme = url.substring(0, schemeEnd).lowercase()
        val rest = url.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        val host = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "" else rest.substring(slash).trimEnd('/')
        return "$scheme://${host.lowercase()}$path"
    }

    /**
     * The endpoint normally lives next to `/chat/completions`, so a path that already ends in a
     * version segment gets `/models` directly; otherwise `/v1/models` is tried before `/models`
     * (or the reverse, when the path already has a prefix like `/api`). Only 404 falls through.
     */
    private fun candidateUrls(base: String): List<String> {
        val path = base.substringAfter("://", "").substringAfter('/', "").trimEnd('/')
        val versioned = path.isNotEmpty() && path.substringAfterLast('/').matches(VERSION)
        return when {
            versioned -> listOf("$base/models")
            path.isEmpty() -> listOf("$base/v1/models", "$base/models")
            else -> listOf("$base/models", "$base/v1/models")
        }
    }

    private val VERSION = Regex("^v\\d+(?:(?:alpha|beta)\\d*)?$", RegexOption.IGNORE_CASE)

    private fun fetch(client: OkHttpClient, url: String, apiKey: String): HttpResult {
        val builder = Request.Builder().url(url).get().header("Accept", "application/json")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return client.newCall(builder.build()).execute().use { response ->
            HttpResult(response.code, response.body?.string().orEmpty())
        }
    }

    private fun parse(body: String, url: String): List<String> {
        val root = try {
            JSON.parseToJsonElement(body)
        } catch (e: Exception) {
            throw ModelCatalogException(ModelCatalogException.Kind.MALFORMED, null, "Not JSON from $url", e)
        }
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray ?: root["models"] as? JsonArray
            else -> null
        } ?: throw ModelCatalogException(ModelCatalogException.Kind.MALFORMED, null, "Unexpected JSON from $url")
        return array.mapNotNull { element ->
            when (element) {
                is JsonObject -> (element["id"] as? JsonPrimitive)?.contentOrNull
                    ?: (element["name"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> element.contentOrNull
                else -> null
            }
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private val JSON = Json { ignoreUnknownKeys = true }

    private data class HttpResult(val code: Int, val body: String)
}

/** Why a model list could not be fetched; the UI maps each kind to one short message. */
class ModelCatalogException(
    val kind: Kind,
    val statusCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { UNAUTHORIZED, FORBIDDEN, NOT_FOUND, TIMEOUT, NETWORK, MALFORMED, SERVER, HTTP }
}
