package com.controlxia.brain.llm

/**
 * Abstracción de proveedor de LLM. El resto de la app solo habla con esta
 * interfaz: cambiar de proveedor es cuestión de configuración, no de código.
 */
interface LlmProvider {

    /**
     * Envía la conversación (más las tools disponibles) y devuelve la decisión
     * del modelo.
     * @throws LlmException si la API falla (key inválida, red, modelo inexistente).
     */
    suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
    ): LlmResponse
}

/**
 * Proveedores soportados, con sus modelos sugeridos. El usuario también puede
 * escribir a mano cualquier ID de modelo nuevo sin actualizar la app.
 */
enum class LlmProviderType(
    val displayName: String,
    val suggestedModels: List<String>,
    val needsApiKey: Boolean = true,
    val needsBaseUrl: Boolean = false,
    val defaultBaseUrl: String? = null,
) {
    ANTHROPIC(
        displayName = "Anthropic (Claude)",
        suggestedModels = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"),
    ),
    OPENAI(
        displayName = "OpenAI (GPT)",
        suggestedModels = listOf("gpt-4o-mini", "gpt-4o"),
        defaultBaseUrl = "https://api.openai.com/v1",
    ),
    GEMINI(
        displayName = "Google (Gemini)",
        suggestedModels = listOf("gemini-2.5-flash", "gemini-2.5-pro"),
    ),
    OLLAMA(
        displayName = "Ollama (local, sin internet)",
        suggestedModels = listOf("llama3.2", "qwen2.5", "llava"),
        needsApiKey = false,
        needsBaseUrl = true,
        defaultBaseUrl = "http://localhost:11434/v1",
    ),
    OPENAI_COMPATIBLE(
        displayName = "Compatible OpenAI (Groq, OpenRouter…)",
        suggestedModels = emptyList(),
        needsApiKey = false,
        needsBaseUrl = true,
    );
}

/** Configuración resuelta de un proveedor, lista para crear el cliente. */
data class LlmConfig(
    val type: LlmProviderType,
    val apiKey: String,
    val model: String,
    /** Para OLLAMA / OPENAI_COMPATIBLE, ej: "https://api.groq.com/openai/v1". */
    val baseUrl: String? = null,
    /**
     * Esfuerzo de razonamiento para Anthropic ("low", "medium", "high"…).
     * Vacío = no se envía. "low" da respuestas más rápidas, ideal para voz.
     */
    val effort: String? = null,
)

object LlmProviderFactory {

    fun create(config: LlmConfig): LlmProvider = when (config.type) {
        LlmProviderType.ANTHROPIC -> AnthropicProvider(config.apiKey, config.model, config.effort)
        LlmProviderType.GEMINI -> GeminiProvider(config.apiKey, config.model)
        LlmProviderType.OPENAI,
        LlmProviderType.OLLAMA,
        LlmProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(
            apiKey = config.apiKey,
            model = config.model,
            baseUrl = config.baseUrl?.ifBlank { null }
                ?: config.type.defaultBaseUrl
                ?: throw LlmException("Falta la base URL del proveedor"),
            providerType = config.type,
        )
    }
}
