package dev.xiaoman.app.settings

import dev.xiaoman.api.AgentConfig
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.ModelRole
import java.util.UUID

/** One OpenAI-compatible endpoint as typed into Settings. [toString] never shows [apiKey]. */
data class ModelSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val temperature: Double? = null,
    val maxTokens: Int? = null,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    fun toModelConfig(role: ModelRole) = ModelConfig(
        role = role,
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        apiKey = apiKey.trim(),
        temperature = temperature,
        maxTokens = maxTokens,
    )

    override fun toString() =
        "ModelSettings(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, temperature=$temperature, maxTokens=$maxTokens)"

    companion object {
        /** Volcano Ark. */
        const val DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
    }
}

/** A chat model the user added on the Models screen. */
data class SavedModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String = ModelSettings.DEFAULT_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val temperature: Double? = null,
    val maxTokens: Int? = null,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    fun toModelSettings() = ModelSettings(baseUrl.trim(), model.trim(), apiKey.trim(), temperature, maxTokens)

    /** Title shown in the list: the user's name, else the model id. */
    val displayName: String get() = name.trim().ifEmpty { model.ifBlank { "未命名模型" } }

    override fun toString() =
        "SavedModel(id=$id, name=$name, baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"})"
}

data class AppSettings(
    val chat: ModelSettings = ModelSettings(),
    val operator: ModelSettings = ModelSettings(),
    /** When true the operator reuses every chat model parameter and [operator] is kept untouched. */
    val operatorSameAsChat: Boolean = false,
    val maxSteps: Int = AgentConfig().maxSteps,
    val learnFromSuccess: Boolean = AgentConfig().learnFromSuccess,
    val assistantName: String = DEFAULT_ASSISTANT_NAME,
    /** Absolute path of a custom assistant avatar, or null for the default icon. */
    val avatarPath: String? = null,
    /** Absolute path of a custom chat background, or null for the theme background. */
    val chatBackgroundPath: String? = null,
    /** ARGB hex of the app accent colour, e.g. `#4E7CF6`. */
    val themeColor: String = DEFAULT_THEME_COLOR,
    val chatModels: List<SavedModel> = emptyList(),
    val selectedChatModelId: String? = null,
    val mcp: McpSettings = McpSettings(),
) {
    /** The model the operator really uses: the chat model when "与聊天模型相同" is checked. */
    val effectiveOperator: ModelSettings get() = if (operatorSameAsChat) chat else operator

    val configured: Boolean get() = chat.configured && effectiveOperator.configured

    /** The model the chat uses; falls back to the legacy single chat settings. */
    val selectedChatModel: SavedModel?
        get() = chatModels.firstOrNull { it.id == selectedChatModelId }

    /** Values to mask in traces and logs. */
    val secrets: List<String>
        get() = (listOf(chat.apiKey, operator.apiKey) + chatModels.map { it.apiKey } + mcp.secrets())
            .filter { it.isNotBlank() }

    fun agentConfig() = AgentConfig(maxSteps = maxSteps, learnFromSuccess = learnFromSuccess)

    companion object {
        val MAX_STEPS_RANGE = 1..200
        const val DEFAULT_ASSISTANT_NAME = "小满Extra"
        const val DEFAULT_THEME_COLOR = "#3D7BF0"
    }
}

/**
 * The editable text of the Settings screen. Parsing lives here, not in the Composable.
 */
data class SettingsForm(
    val chat: ModelForm = ModelForm(),
    val operator: ModelForm = ModelForm(),
    val operatorSameAsChat: Boolean = false,
    val maxSteps: String = AgentConfig().maxSteps.toString(),
    val learnFromSuccess: Boolean = true,
) {
    data class ModelForm(
        val baseUrl: String = ModelSettings.DEFAULT_BASE_URL,
        val model: String = "",
        val apiKey: String = "",
        val temperature: String = "",
        val maxTokens: String = "",
    ) {
        val temperatureValid: Boolean get() = parseTemperature(temperature) != null || temperature.isBlank()

        val maxTokensValid: Boolean get() = maxTokens.isBlank() || maxTokens.trim().toIntOrNull()?.let { it > 0 } == true

        fun toSettings(): ModelSettings? {
            if (!temperatureValid || !maxTokensValid) return null
            val t = if (temperature.isBlank()) null else parseTemperature(temperature) ?: return null
            val m = if (maxTokens.isBlank()) null else maxTokens.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
            return ModelSettings(baseUrl.trim(), model.trim(), apiKey.trim(), t, m)
        }

        override fun toString() =
            "ModelForm(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, temperature=$temperature, maxTokens=$maxTokens)"

        companion object {
            fun of(s: ModelSettings) =
                ModelForm(s.baseUrl, s.model, s.apiKey, s.temperature?.toString().orEmpty(), s.maxTokens?.toString().orEmpty())
        }
    }

    val maxStepsValid: Boolean get() = maxSteps.trim().toIntOrNull()?.let { it in AppSettings.MAX_STEPS_RANGE } == true

    /** Null while a field is invalid. [base] keeps personalisation fields the form does not edit. */
    fun toSettings(base: AppSettings = AppSettings()): AppSettings? {
        val steps = maxSteps.trim().toIntOrNull()?.takeIf { it in AppSettings.MAX_STEPS_RANGE } ?: return null
        return base.copy(
            chat = chat.toSettings() ?: return null,
            // While inheriting, the saved standalone operator config must not be overwritten.
            operator = if (operatorSameAsChat) base.operator else operator.toSettings() ?: return null,
            operatorSameAsChat = operatorSameAsChat,
            maxSteps = steps,
            learnFromSuccess = learnFromSuccess,
        )
    }

    companion object {
        fun of(s: AppSettings) = SettingsForm(
            chat = ModelForm.of(s.chat),
            operator = ModelForm.of(s.operator),
            operatorSameAsChat = s.operatorSameAsChat,
            maxSteps = s.maxSteps.toString(),
            learnFromSuccess = s.learnFromSuccess,
        )

        fun parseTemperature(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it in 0.0..2.0 }
    }
}
