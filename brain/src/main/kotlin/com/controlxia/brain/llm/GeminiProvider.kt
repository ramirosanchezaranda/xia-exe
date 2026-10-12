package com.controlxia.brain.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Cliente de la API generateContent de Google Gemini con function calling e
 * imágenes. https://ai.google.dev/api/generate-content
 */
class GeminiProvider(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) : LlmProvider {

    override suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): LlmResponse {
        val system = messages.filter { it.role == ChatRole.SYSTEM }
            .joinToString("\n") { it.content }

        val body = buildJsonObject {
            if (system.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray { add(textPart(system)) })
                })
            }
            put("contents", toWireContents(messages.filter { it.role != ChatRole.SYSTEM }))
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    // Gemini rechaza "properties" vacío.
                                    if (hasProperties(tool.parameters)) put("parameters", tool.parameters)
                                })
                            }
                        })
                    })
                })
            }
        }

        val response = HttpJson.post(
            url = "${baseUrl.trimEnd('/')}/v1beta/models/$model:generateContent",
            headers = mapOf("x-goog-api-key" to apiKey),
            body = body,
        )
        return parse(response)
    }

    internal fun parse(response: JsonObject): LlmResponse {
        val content = (response["candidates"] as? JsonArray)?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("content") as? JsonObject }
            ?: throw LlmException("Respuesta sin candidates: ${response.toString().take(300)}")
        val parts = content["parts"] as? JsonArray ?: JsonArray(emptyList())

        val calls = mutableListOf<ToolCall>()
        val text = StringBuilder()
        parts.forEachIndexed { i, part ->
            val obj = part as? JsonObject ?: return@forEachIndexed
            val functionCall = obj["functionCall"] as? JsonObject
            if (functionCall != null) {
                calls += ToolCall(
                    id = functionCall["id"].stringOrNull ?: "gemini_${System.nanoTime()}_$i",
                    name = functionCall["name"].stringOrNull.orEmpty(),
                    arguments = functionCall["args"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            } else if (obj["thought"].stringOrNull != "true") {
                text.append(obj["text"].stringOrNull.orEmpty())
            }
        }
        val message = ChatMessage(
            role = ChatRole.ASSISTANT,
            content = text.toString(),
            toolCalls = calls,
            // Las partes originales llevan las "thought signatures" que Gemini
            // pide recibir de vuelta en el siguiente paso.
            raw = parts,
            rawProvider = LlmProviderType.GEMINI,
        )
        return LlmResponse(text.toString(), calls, message)
    }

    internal fun toWireContents(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        var pendingParts = mutableListOf<JsonElement>()

        fun flushResults(addTo: kotlinx.serialization.json.JsonArrayBuilder) {
            if (pendingParts.isEmpty()) return
            addTo.add(buildJsonObject {
                put("role", "user")
                put("parts", JsonArray(pendingParts))
            })
            pendingParts = mutableListOf()
        }

        for (msg in messages) {
            if (msg.role == ChatRole.TOOL) {
                pendingParts += buildJsonObject {
                    put("functionResponse", buildJsonObject {
                        put("name", msg.toolName.orEmpty())
                        put("response", buildJsonObject {
                            put(if (msg.isError) "error" else "result", msg.content.ifBlank { "ok" })
                        })
                    })
                }
                msg.images.forEach { pendingParts += imagePart(it) }
                continue
            }
            flushResults(this)
            when (msg.role) {
                ChatRole.USER -> add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        msg.images.forEach { add(imagePart(it)) }
                        add(textPart(msg.content))
                    })
                })
                ChatRole.ASSISTANT -> add(buildJsonObject {
                    put("role", "model")
                    val raw = msg.raw
                    if (msg.rawProvider == LlmProviderType.GEMINI && raw is JsonArray && raw.isNotEmpty()) {
                        put("parts", raw)
                    } else {
                        put("parts", buildJsonArray {
                            if (msg.content.isNotBlank() || msg.toolCalls.isEmpty()) {
                                add(textPart(msg.content.ifBlank { "…" }))
                            }
                            msg.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("functionCall", buildJsonObject {
                                        put("name", call.name)
                                        put("args", call.arguments)
                                    })
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

    private fun hasProperties(schema: JsonObject): Boolean =
        (schema["properties"] as? JsonObject)?.isNotEmpty() == true

    private fun textPart(text: String) = buildJsonObject { put("text", text) }

    private fun imagePart(image: ImageData) = buildJsonObject {
        put("inlineData", buildJsonObject {
            put("mimeType", image.mediaType)
            put("data", image.base64)
        })
    }
}
