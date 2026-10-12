package com.controlxia.brain.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Cliente de la Messages API de Anthropic con tool use nativo e imágenes.
 * https://docs.anthropic.com/en/api/messages
 */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String,
    private val effort: String? = null,
    private val baseUrl: String = "https://api.anthropic.com",
) : LlmProvider {

    override suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): LlmResponse {
        // Anthropic recibe el system prompt por fuera de la lista de mensajes.
        val system = messages.filter { it.role == ChatRole.SYSTEM }
            .joinToString("\n") { it.content }

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            if (system.isNotBlank()) put("system", system)
            put("messages", toWireMessages(messages.filter { it.role != ChatRole.SYSTEM }))
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.parameters)
                        })
                    }
                })
            }
            if (!effort.isNullOrBlank()) {
                put("output_config", buildJsonObject { put("effort", effort) })
            }
            // Respaldo del lado del servidor: si un clasificador rechaza el
            // pedido, la API lo reintenta sola con otro modelo.
            if (model in SERVER_FALLBACK_MODELS) put("fallbacks", "default")
        }

        val headers = buildMap {
            put("x-api-key", apiKey)
            put("anthropic-version", "2023-06-01")
            if (model in SERVER_FALLBACK_MODELS) put("anthropic-beta", "server-side-fallback-2026-07-01")
        }

        val response = HttpJson.post("${baseUrl.trimEnd('/')}/v1/messages", headers, body)
        return parse(response)
    }

    internal fun parse(response: JsonObject): LlmResponse {
        val content = response["content"] as? JsonArray
            ?: throw LlmException("Respuesta sin contenido: ${response.toString().take(300)}")

        if (response["stop_reason"].stringOrNull == "refusal") {
            val text = "No puedo ayudarte con eso."
            return LlmResponse(text, emptyList(), ChatMessage(ChatRole.ASSISTANT, text))
        }

        val calls = mutableListOf<ToolCall>()
        val text = StringBuilder()
        for (block in content) {
            val obj = block as? JsonObject ?: continue
            when (obj["type"].stringOrNull) {
                "text" -> text.append(obj["text"].stringOrNull.orEmpty())
                "tool_use" -> calls += ToolCall(
                    id = obj["id"].stringOrNull.orEmpty(),
                    name = obj["name"].stringOrNull.orEmpty(),
                    arguments = obj["input"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            }
        }
        val message = ChatMessage(
            role = ChatRole.ASSISTANT,
            content = text.toString(),
            toolCalls = calls,
            // Se reenvía tal cual: incluye bloques de thinking que la API
            // exige recibir sin modificar en el siguiente paso.
            raw = content,
            rawProvider = LlmProviderType.ANTHROPIC,
        )
        return LlmResponse(text.toString(), calls, message)
    }

    /**
     * Convierte el historial al formato de Anthropic. Los resultados de tools
     * consecutivos van todos juntos en un único mensaje "user".
     */
    internal fun toWireMessages(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        var pendingResults = mutableListOf<JsonObject>()

        fun flushResults(addTo: kotlinx.serialization.json.JsonArrayBuilder) {
            if (pendingResults.isEmpty()) return
            addTo.add(buildJsonObject {
                put("role", "user")
                put("content", JsonArray(pendingResults))
            })
            pendingResults = mutableListOf()
        }

        for (msg in messages) {
            if (msg.role == ChatRole.TOOL) {
                pendingResults += buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", msg.toolCallId.orEmpty())
                    if (msg.isError) put("is_error", true)
                    put("content", buildJsonArray {
                        add(textBlock(msg.content.ifBlank { "ok" }))
                        msg.images.forEach { add(imageBlock(it)) }
                    })
                }
                continue
            }
            flushResults(this)
            when (msg.role) {
                ChatRole.USER -> add(buildJsonObject {
                    put("role", "user")
                    if (msg.images.isEmpty()) {
                        put("content", msg.content)
                    } else {
                        put("content", buildJsonArray {
                            msg.images.forEach { add(imageBlock(it)) }
                            add(textBlock(msg.content))
                        })
                    }
                })
                ChatRole.ASSISTANT -> add(buildJsonObject {
                    put("role", "assistant")
                    val raw = msg.raw
                    if (msg.rawProvider == LlmProviderType.ANTHROPIC && raw is JsonArray) {
                        put("content", raw)
                    } else if (msg.toolCalls.isEmpty()) {
                        put("content", msg.content.ifBlank { "…" })
                    } else {
                        put("content", buildJsonArray {
                            if (msg.content.isNotBlank()) add(textBlock(msg.content))
                            msg.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", call.id)
                                    put("name", call.name)
                                    put("input", call.arguments)
                                })
                            }
                        })
                    }
                })
                else -> Unit
            }
        }
        flushResults(this)
    }

    private fun textBlock(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun imageBlock(image: ImageData) = buildJsonObject {
        put("type", "image")
        put("source", buildJsonObject {
            put("type", "base64")
            put("media_type", image.mediaType)
            put("data", image.base64)
        })
    }

    private companion object {
        /** Modelos que aceptan `fallbacks: "default"` en la API de Anthropic. */
        val SERVER_FALLBACK_MODELS = setOf(
            "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5",
        )
    }
}
