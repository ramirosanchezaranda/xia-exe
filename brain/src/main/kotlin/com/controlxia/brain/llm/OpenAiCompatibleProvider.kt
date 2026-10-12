package com.controlxia.brain.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Cliente de la API Chat Completions con function calling e imágenes. Sirve
 * para OpenAI y para cualquier servicio compatible (Groq, OpenRouter, Mistral,
 * Ollama local, etc.) cambiando la [baseUrl].
 */
class OpenAiCompatibleProvider(
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
    private val providerType: LlmProviderType = LlmProviderType.OPENAI_COMPATIBLE,
) : LlmProvider {

    private val url = baseUrl.trimEnd('/') + "/chat/completions"

    override suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): LlmResponse {
        val body = buildJsonObject {
            put("model", model)
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

        val headers = buildMap {
            // Ollama local no requiere key; solo mandamos el header si hay una.
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
        }

        return parse(HttpJson.post(url, headers, body))
    }

    internal fun parse(response: JsonObject): LlmResponse {
        val message = (response["choices"] as? JsonArray)?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("message") as? JsonObject }
            ?: throw LlmException("Respuesta sin choices: ${response.toString().take(300)}")

        val calls = message["tool_calls"].arrayOrEmpty.mapIndexedNotNull { i, el ->
            val obj = el as? JsonObject ?: return@mapIndexedNotNull null
            val function = obj["function"] as? JsonObject ?: return@mapIndexedNotNull null
            val rawArgs = function["arguments"].stringOrNull ?: "{}"
            val args = runCatching {
                HttpJson.json.parseToJsonElement(rawArgs.ifBlank { "{}" }) as? JsonObject
            }.getOrNull() ?: JsonObject(emptyMap())
            ToolCall(
                id = obj["id"].stringOrNull ?: "call_$i",
                name = function["name"].stringOrNull.orEmpty(),
                arguments = args,
            )
        }
        val text = message["content"].stringOrNull.orEmpty()
        return LlmResponse(
            text = text,
            toolCalls = calls,
            assistantMessage = ChatMessage(ChatRole.ASSISTANT, text, toolCalls = calls),
        )
    }

    /**
     * Los mensajes "tool" no admiten imágenes en Chat Completions: las
     * capturas se agregan en un mensaje "user" justo después del bloque de
     * resultados.
     */
    internal fun toWireMessages(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        val pendingImages = mutableListOf<ImageData>()

        fun flushImages(addTo: kotlinx.serialization.json.JsonArrayBuilder) {
            if (pendingImages.isEmpty()) return
            addTo.add(userMessage("Captura de pantalla devuelta por la herramienta.", pendingImages.toList()))
            pendingImages.clear()
        }

        for (msg in messages) {
            if (msg.role == ChatRole.TOOL) {
                add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", msg.toolCallId.orEmpty())
                    put("content", msg.content.ifBlank { "ok" })
                })
                pendingImages += msg.images
                continue
            }
            flushImages(this)
            when (msg.role) {
                ChatRole.SYSTEM -> add(buildJsonObject {
                    put("role", "system")
                    put("content", msg.content)
                })
                ChatRole.USER -> add(userMessage(msg.content, msg.images))
                ChatRole.ASSISTANT -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", msg.content)
                    if (msg.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            msg.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.arguments.toString())
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

    private fun userMessage(text: String, images: List<ImageData>) = buildJsonObject {
        put("role", "user")
        if (images.isEmpty()) {
            put("content", text)
        } else {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
                images.forEach { image ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:${image.mediaType};base64,${image.base64}")
                        })
                    })
                }
            })
        }
    }
}
