package dev.xiaoman.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.xiaoman.api.McpConfirmPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists [AppSettings]: everything but the API keys in [plain], the keys only in [secret]
 * (EncryptedSharedPreferences in the app). Nothing here logs a key.
 */
class SettingsStore(private val plain: SharedPreferences, private val secret: SharedPreferences) {

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    @Synchronized
    fun save(settings: AppSettings) {
        val models = settings.chatModels
        val selected = models.firstOrNull { it.id == settings.selectedChatModelId }
        val chat = selected?.toModelSettings() ?: settings.chat
        val saved = settings.copy(chat = chat, assistantName = settings.assistantName.trim().ifEmpty { AppSettings.DEFAULT_ASSISTANT_NAME })
        plain.edit(commit = true) {
            putModel(CHAT, saved.chat)
            putModel(OPERATOR, saved.operator)
            putBoolean(OPERATOR_SAME, saved.operatorSameAsChat)
            putInt(MAX_STEPS, saved.maxSteps)
            putBoolean(LEARN, saved.learnFromSuccess)
            putString(ASSISTANT_NAME, saved.assistantName)
            putString(AVATAR_PATH, saved.avatarPath)
            putString(BACKGROUND_PATH, saved.chatBackgroundPath)
            putString(THEME_COLOR, saved.themeColor)
            putString(THEME_STYLE, saved.themeStyle)
            putString(MODELS, encodeModels(saved.chatModels))
            putString(SELECTED_MODEL, saved.selectedChatModelId)
            putString(MCP, encodeMcp(saved.mcp))
            putBoolean(SHOW_SUGGESTIONS, saved.showTaskSuggestions)
            putString(SKILL_SOURCES, encodeSources(saved.skillSources))
            putString(SELECTED_SOURCE, saved.selectedSkillSourceId)
            putFloat(FONT_SCALE, saved.fontScale)
            putString(DARK_MODE, saved.darkMode.name)
            putString(LANGUAGE, saved.language)
            putBoolean(SPEAK_REPLIES, saved.speakReplies)
            putBoolean(START_FRESH, saved.startFreshChat)
        }
        secret.edit(commit = true) {
            putString(CHAT + API_KEY, saved.chat.apiKey)
            putString(OPERATOR + API_KEY, saved.operator.apiKey)
            val kept = saved.chatModels.map { it.id }.toSet()
            current.chatModels.filter { it.id !in kept }.forEach { remove(modelKey(it.id)) }
            saved.chatModels.forEach { putString(modelKey(it.id), it.apiKey) }
            val mcpServiceIds = saved.mcp.services.map { it.id }.toSet()
            current.mcp.services.filter { it.id !in mcpServiceIds }.forEach { remove(mcpKey(it.id)) }
            saved.mcp.services.forEach { if (it.apiKey.isBlank()) remove(mcpKey(it.id)) else putString(mcpKey(it.id), it.apiKey) }
            val customIds = saved.mcp.custom.map { it.id }.toSet()
            current.mcp.custom.filter { it.id !in customIds }.forEach { remove(mcpAuthKey(it.id)) }
            saved.mcp.custom.forEach { if (it.authorization.isBlank()) remove(mcpAuthKey(it.id)) else putString(mcpAuthKey(it.id), it.authorization) }
        }
        _settings.value = saved
    }

    @Synchronized
    fun saveModels(models: List<SavedModel>, selectedId: String?) {
        val selected = models.firstOrNull { it.id == selectedId } ?: models.firstOrNull()
        // Removing the last model must also clear the chat config, otherwise it would be
        // migrated back into a new model entry on the next launch.
        val chat = selected?.toModelSettings() ?: ModelSettings()
        save(current.copy(chatModels = models, selectedChatModelId = selected?.id, chat = chat))
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        val models = runCatching { decodeModels(plain.getString(MODELS, null)) }.getOrDefault(emptyList())
        val legacyChat = model(CHAT)
        val migrated = if (models.isEmpty() && legacyChat.model.isNotBlank()) {
            listOf(
                SavedModel(
                    id = LEGACY_ID,
                    name = legacyChat.model,
                    baseUrl = legacyChat.baseUrl,
                    model = legacyChat.model,
                    apiKey = legacyChat.apiKey,
                    temperature = legacyChat.temperature,
                    maxTokens = legacyChat.maxTokens,
                ),
            )
        } else {
            models
        }
        val selectedId = plain.getString(SELECTED_MODEL, null)
            ?.takeIf { id -> migrated.any { it.id == id } }
            ?: migrated.firstOrNull()?.id
        val selected = migrated.firstOrNull { it.id == selectedId }
        return AppSettings(
            chat = selected?.toModelSettings() ?: legacyChat,
            operator = model(OPERATOR),
            operatorSameAsChat = plain.getBoolean(OPERATOR_SAME, false),
            maxSteps = plain.getInt(MAX_STEPS, defaults.maxSteps),
            learnFromSuccess = plain.getBoolean(LEARN, defaults.learnFromSuccess),
            assistantName = plain.getString(ASSISTANT_NAME, null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: defaults.assistantName,
            avatarPath = plain.getString(AVATAR_PATH, null),
            chatBackgroundPath = plain.getString(BACKGROUND_PATH, null),
            themeColor = plain.getString(THEME_COLOR, null)?.takeIf { it.matches(HEX) } ?: defaults.themeColor,
            themeStyle = plain.getString(THEME_STYLE, null)
                ?.takeIf { it == AppSettings.THEME_STYLE_ACCENT || it == AppSettings.THEME_STYLE_WALLPAPER }
                ?: defaults.themeStyle,
            chatModels = migrated,
            selectedChatModelId = selectedId,
            mcp = decodeMcp(plain.getString(MCP, null)),
            showTaskSuggestions = plain.getBoolean(SHOW_SUGGESTIONS, defaults.showTaskSuggestions),
            skillSources = decodeSources(plain.getString(SKILL_SOURCES, null)),
            selectedSkillSourceId = plain.getString(SELECTED_SOURCE, null) ?: defaults.selectedSkillSourceId,
            fontScale = plain.getFloat(FONT_SCALE, defaults.fontScale).takeIf { it in FONT_SCALE_RANGE } ?: defaults.fontScale,
            darkMode = runCatching { DarkMode.valueOf(plain.getString(DARK_MODE, null).orEmpty()) }
                .getOrDefault(defaults.darkMode),
            language = plain.getString(LANGUAGE, null)?.takeIf { it in AppSettings.LANGUAGES } ?: defaults.language,
            speakReplies = plain.getBoolean(SPEAK_REPLIES, defaults.speakReplies),
            startFreshChat = plain.getBoolean(START_FRESH, defaults.startFreshChat),
        )
    }

    private fun model(prefix: String) = ModelSettings(
        baseUrl = plain.getString(prefix + BASE_URL, null) ?: ModelSettings.DEFAULT_BASE_URL,
        model = plain.getString(prefix + MODEL, null).orEmpty(),
        apiKey = secret.getString(prefix + API_KEY, null).orEmpty(),
        temperature = plain.getString(prefix + TEMPERATURE, null)?.toDoubleOrNull(),
        maxTokens = plain.getString(prefix + MAX_TOKENS, null)?.toIntOrNull(),
    )

    private fun SharedPreferences.Editor.putModel(prefix: String, m: ModelSettings) {
        putString(prefix + BASE_URL, m.baseUrl)
        putString(prefix + MODEL, m.model)
        putString(prefix + TEMPERATURE, m.temperature?.toString())
        putString(prefix + MAX_TOKENS, m.maxTokens?.toString())
    }

    /** Stored without API keys; [model] reads them back from [secret]. */
    private fun encodeModels(models: List<SavedModel>): String {
        val array = JSONArray()
        models.forEach { m ->
            array.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("name", m.name)
                    put("baseUrl", m.baseUrl)
                    put("model", m.model)
                    put("temperature", m.temperature ?: JSONObject.NULL)
                    put("maxTokens", m.maxTokens ?: JSONObject.NULL)
                },
            )
        }
        return array.toString()
    }

    private fun decodeModels(raw: String?): List<SavedModel> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    SavedModel(
                        id = id,
                        name = o.optString("name"),
                        baseUrl = o.optString("baseUrl", ModelSettings.DEFAULT_BASE_URL),
                        model = o.optString("model"),
                        apiKey = secret.getString(modelKey(id), null).orEmpty(),
                        temperature = o.takeUnless { it.isNull("temperature") }?.optDouble("temperature")?.takeIf { !it.isNaN() },
                        maxTokens = o.takeUnless { it.isNull("maxTokens") }?.optInt("maxTokens")?.takeIf { it > 0 },
                    ),
                )
            }
        }
    }

    /** Stored without keys or Authorization headers; those live in [secret]. */
    private fun encodeMcp(mcp: McpSettings): String = JSONObject().apply {
        put("confirm", mcp.confirmPolicy.name)
        put(
            "services",
            JSONArray().apply {
                mcp.services.forEach { s ->
                    put(
                        JSONObject().apply {
                            put("id", s.id)
                            put("enabled", s.enabled)
                            put("url", s.url)
                        },
                    )
                }
            },
        )
        put(
            "custom",
            JSONArray().apply {
                mcp.custom.forEach { c ->
                    put(
                        JSONObject().apply {
                            put("id", c.id)
                            put("name", c.name)
                            put("url", c.url)
                        },
                    )
                }
            },
        )
    }.toString()

    private fun decodeMcp(raw: String?): McpSettings {
        if (raw.isNullOrBlank()) return McpSettings().normalized()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return McpSettings().normalized()
        val services = buildList {
            val array = root.optJSONArray("services") ?: JSONArray()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                if (McpBuiltins.byId(id) == null) continue
                add(
                    McpServiceConfig(
                        id = id,
                        enabled = o.optBoolean("enabled", false),
                        apiKey = secret.getString(mcpKey(id), null).orEmpty(),
                        url = o.optString("url"),
                    ),
                )
            }
        }
        val custom = buildList {
            val array = root.optJSONArray("custom") ?: JSONArray()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    McpCustomServer(
                        id = id,
                        name = o.optString("name"),
                        url = o.optString("url"),
                        authorization = secret.getString(mcpAuthKey(id), null).orEmpty(),
                    ),
                )
            }
        }
        val policy = runCatching { McpConfirmPolicy.valueOf(root.optString("confirm", McpConfirmPolicy.MUTATING.name)) }
            .getOrDefault(McpConfirmPolicy.MUTATING)
        return McpSettings(services = services, custom = custom, confirmPolicy = policy).normalized()
    }

    /** Extra Skill stores; the built-in SkillHub is implicit and never written. */
    private fun encodeSources(sources: List<SkillSource>): String = JSONArray().apply {
        sources.filterNot { it.builtin }.forEach { s ->
            put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("baseUrl", s.baseUrl)
            })
        }
    }.toString()

    private fun decodeSources(raw: String?): List<SkillSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.isNotBlank() && it != SkillSource.BUILTIN_ID } ?: continue
                    val url = o.optString("baseUrl").trim()
                    if (url.isBlank()) continue
                    add(SkillSource(id = id, name = o.optString("name").ifBlank { id }, baseUrl = url))
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "Xiaoman"
        private const val PLAIN_FILE = "settings"
        private const val SECRET_FILE = "settings-secrets"
        private const val CHAT = "chat."
        private const val OPERATOR = "operator."
        private const val BASE_URL = "baseUrl"
        private const val MODEL = "model"
        private const val API_KEY = "apiKey"
        private const val TEMPERATURE = "temperature"
        private const val MAX_TOKENS = "maxTokens"
        private const val MAX_STEPS = "agent.maxSteps"
        private const val LEARN = "agent.learnFromSuccess"
        private const val ASSISTANT_NAME = "custom.assistantName"
        private const val AVATAR_PATH = "custom.avatarPath"
        private const val BACKGROUND_PATH = "custom.backgroundPath"
        private const val THEME_COLOR = "custom.themeColor"
        private const val THEME_STYLE = "custom.themeStyle"
        private const val MODELS = "models.chat"
        private const val SELECTED_MODEL = "models.selected"
        private const val OPERATOR_SAME = "operator.sameAsChat"
        private const val MCP = "mcp.config"
        private const val SHOW_SUGGESTIONS = "chat.showSuggestions"
        private const val SKILL_SOURCES = "skills.sources"
        private const val SELECTED_SOURCE = "skills.selectedSource"
        private const val FONT_SCALE = "ui.fontScale"
        private const val DARK_MODE = "ui.darkMode"
        private const val LANGUAGE = "ui.language"
        private const val SPEAK_REPLIES = "ui.speakReplies"
        private const val START_FRESH = "ui.startFreshChat"
        private val FONT_SCALE_RANGE = 0.8f..1.6f
        private const val LEGACY_ID = "legacy-chat"
        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

        private fun modelKey(id: String) = "model.$id.apiKey"
        private fun mcpKey(id: String) = "mcp.$id.apiKey"
        private fun mcpAuthKey(id: String) = "mcp.$id.authorization"

        fun create(context: Context): SettingsStore =
            SettingsStore(context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE), encrypted(context))

        private fun encrypted(context: Context): SharedPreferences = try {
            openEncrypted(context)
        } catch (e: Exception) {
            // A keystore entry lost after a restore or OS update makes the file undecryptable.
            // The keys cannot be recovered; start over so the user can type them again.
            Log.w(TAG, "settings: encrypted preferences unreadable (${e.javaClass.simpleName}); resetting API keys")
            context.deleteSharedPreferences(SECRET_FILE)
            openEncrypted(context)
        }

        private fun openEncrypted(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context,
                SECRET_FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
