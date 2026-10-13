package com.controlxia.brain.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Utilidades para la pantalla de configuración del LLM (RF-21). */
object LlmDiagnostics {

    /**
     * Manda un pedido mínimo al proveedor. Devuelve un texto de éxito o el
     * error real de la API (clave inválida, modelo inexistente, sin red…).
     */
    suspend fun testConnection(
        config: LlmConfig,
        factory: (LlmConfig) -> LlmProvider = LlmProviderFactory::create,
    ): Result<String> = runCatching {
        val started = System.currentTimeMillis()
        val response = factory(config).complete(
            listOf(ChatMessage(ChatRole.USER, "Respondé solamente con la palabra OK.")),
        )
        val ms = System.currentTimeMillis() - started
        "Conectado a ${config.model} en $ms ms. Respuesta: \"${response.text.trim().take(40)}\""
    }

    /** Modelos instalados en un servidor Ollama (GET /api/tags). */
    suspend fun ollamaModels(baseUrl: String): List<String> {
        val response = HttpJson.get(OllamaProvider.rootUrl(baseUrl) + "/api/tags")
        return (response["models"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("name").stringOrNull }
    }
}
