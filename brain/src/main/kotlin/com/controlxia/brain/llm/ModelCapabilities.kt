package com.controlxia.brain.llm

/**
 * Qué puede hacer un modelo, deducido de su nombre. Sirve para no ofrecerle
 * herramientas que no puede usar (por ejemplo, capturas de pantalla a un
 * modelo que no acepta imágenes, que si no devuelve error).
 */
object ModelCapabilities {

    private val visionPatterns = listOf(
        "claude", "gpt-4o", "gpt-4.1", "gpt-5", "o3", "o4", "gemini", "llava", "vision", "-vl", "vl:", "vl-",
        "qwen2.5vl", "qwen3-vl", "pixtral", "gemma3", "llama-4", "llama4", "minicpm-v", "moondream", "granite3.2-vision",
    )
    private val textOnlyPatterns = listOf("gpt-oss", "deepseek-r1", "-instruct-text")

    /** true si el modelo acepta imágenes. Ante la duda, false: es más seguro no ofrecer la captura. */
    fun supportsVision(type: LlmProviderType, model: String): Boolean {
        val m = model.lowercase()
        if (textOnlyPatterns.any { it in m }) return false
        return when (type) {
            LlmProviderType.ANTHROPIC, LlmProviderType.GEMINI -> true
            else -> visionPatterns.any { it in m }
        }
    }
}
