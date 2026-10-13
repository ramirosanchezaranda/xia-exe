package com.controlxia.brain.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ollama local con su API nativa (/api/chat). A diferencia de la ruta
 * compatible con OpenAI, permite fijar el tamaño de contexto (por defecto
 * Ollama usa uno chico y recorta en silencio las instrucciones y las tools) y
 * mantener el modelo cargado en memoria entre pedidos.
 * https://github.com/ollama/ollama/blob/main/docs/api.md#generate-a-chat-completion
 */
class OllamaProvider(
    private val model: String,
    baseUrl: String,
    private val contextTokens: Int = DEFAULT_CONTEXT,
    private val keepAlive: String = "30m",
) : LlmProvider {

    private val url = rootUrl(baseUrl) + "/api/chat"

    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse {
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("keep_alive", keepAlive)
            put("options", buildJsonObject { put("num_ctx", contextTokens) })
            put("messages", toWireMessages(messages))
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            })
                        })
                    }
                })
            }
        }
        return parse(HttpJson.post(url, emptyMap(), body))
    }

    internal fun parse(response: JsonObject): LlmResponse {
        val message = response["message"] as? JsonObject
            ?: throw LlmException("Respuesta de Ollama sin message: ${response.toString().take(300)}")
        val calls = message["tool_calls"].arrayOrEmpty.mapIndexedNotNull { i, el ->
            val function = (el as? JsonObject)?.get("function") as? JsonObject ?: return@mapIndexedNotNull null
            // Ollama devuelve los argumentos como objeto; algunos modelos, como texto JSON.
            val args = when (val raw = function["arguments"]) {
                is JsonObject -> raw
                is JsonPrimitive -> runCatching { HttpJson.json.parseToJsonElement(raw.content) as? JsonObject }.getOrNull()
                else -> null
            } ?: JsonObject(emptyMap())
            ToolCall("ollama_${System.nanoTime()}_$i", function["name"].stringOrNull.orEmpty(), args)
        }
        val text = message["content"].stringOrNull.orEmpty().trim()
        return LlmResponse(text, calls, ChatMessage(ChatRole.ASSISTANT, text, toolCalls = calls))
    }

    internal fun toWireMessages(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        val pendingImages = mutableListOf<ImageData>()

        fun flushImages(addTo: kotlinx.serialization.json.JsonArrayBuilder) {
            if (pendingImages.isEmpty()) return
            addTo.add(message("user", "Captura de pantalla devuelta por la herramienta.", pendingImages.toList()))
            pendingImages.clear()
        }

        for (msg in messages) {
            if (msg.role == ChatRole.TOOL) {
                add(buildJsonObject {
                    put("role", "tool")
                    put("tool_name", msg.toolName.orEmpty())
                    put("content", msg.content.ifBlank { "ok" })
                })
                pendingImages += msg.images
                continue
            }
            flushImages(this)
            when (msg.role) {
                ChatRole.SYSTEM -> add(message("system", msg.content))
                ChatRole.USER -> add(message("user", msg.content, msg.images))
                ChatRole.ASSISTANT -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", msg.content)
                    if (msg.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            msg.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("function", buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    })
                                })
                            }
                        })
                    }
                })
                ChatRole.TOOL -> Unit
            }
        }
        flushImages(this)
    }

    private fun message(role: String, content: String, images: List<ImageData> = emptyList()) = buildJsonObject {
        put("role", role)
        put("content", content)
        if (images.isNotEmpty()) put("images", buildJsonArray { images.forEach { add(JsonPrimitive(it.base64)) } })
    }

    companion object {
        /** Alcanza para instrucciones + 25 tools + varios pasos con historial. */
        const val DEFAULT_CONTEXT = 16_384

        /** Acepta "http://localhost:11434" o la URL vieja con "/v1". */
        fun rootUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/').removeSuffix("/v1").removeSuffix("/api")
    }
}
