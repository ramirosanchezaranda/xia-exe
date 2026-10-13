package com.controlxia.brain.llm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

/** Imagen adjunta a un mensaje (ej. captura de pantalla), ya en base64. */
data class ImageData(
    val base64: String,
    val mediaType: String = "image/png",
)

/** Llamada a herramienta pedida por el modelo. */
data class ToolCall(
    /** ID del proveedor (Anthropic/OpenAI) o sintético (Gemini). */
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

/**
 * Un mensaje de la conversación.
 *
 * - USER/SYSTEM: [content] y opcionalmente [images].
 * - ASSISTANT: [content] y opcionalmente [toolCalls].
 * - TOOL: resultado de la tool [toolCallId]/[toolName], con [content] y [images].
 *
 * [raw] guarda la respuesta original del proveedor [rawProvider] para
 * reenviarla sin tocar (Anthropic exige devolver los bloques de thinking tal
 * cual y Gemini las "thought signatures"). Si la conversación sigue con otro
 * proveedor (respaldo), se ignora y se reconstruye desde los campos comunes.
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val images: List<ImageData> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
    val raw: JsonElement? = null,
    val rawProvider: LlmProviderType? = null,
)

/**
 * Definición de una herramienta que el LLM puede invocar.
 * [parameters] es un JSON Schema estándar (el mismo formato lo aceptan
 * Anthropic, OpenAI y Gemini con envoltorios distintos).
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * Respuesta del modelo: texto para el usuario y/o llamadas a herramientas.
 * [assistantMessage] es el mensaje listo para agregar al historial.
 */
data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall>,
    val assistantMessage: ChatMessage,
)

/**
 * Error del proveedor. [statusCode] es el HTTP (null si fue de red) y
 * [spokenMessage] una frase corta para decir en voz alta; el detalle completo
 * queda en [message] para el historial y el log.
 */
class LlmException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : Exception(message, cause) {

    /** Espera sugerida por el proveedor ante un 429, si la informó. */
    var retryAfterMs: Long? = null

    val spokenMessage: String
        get() = when {
            statusCode == 429 -> "El proveedor de inteligencia artificial me está limitando por exceso de uso. " +
                "Probá de nuevo en unos segundos, o configurá un proveedor de respaldo."
            statusCode == 401 || statusCode == 403 -> "La clave del proveedor de inteligencia artificial no es válida. Revisala en la configuración."
            statusCode == 404 -> "El proveedor no encuentra ese modelo. Revisá el nombre en la configuración."
            statusCode != null && statusCode >= 500 -> "El proveedor de inteligencia artificial está con problemas. Probá en un rato."
            statusCode == null && cause != null -> "No me pude conectar con el proveedor de inteligencia artificial."
            else -> "Tuve un problema con el proveedor de inteligencia artificial."
        }
}
