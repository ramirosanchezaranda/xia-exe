package com.controlxia.brain.mcp

import com.controlxia.brain.llm.ToolSpec
import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Una herramienta de un servidor MCP vista como herramienta de Xia. El nombre
 * lleva el prefijo del servidor ("excel__write_data") para que no choque con
 * otras, y el esquema se limpia de lo que algunos proveedores rechazan.
 */
class McpTool(
    val serverId: String,
    private val serverLabel: String,
    private val info: McpToolInfo,
    private val call: suspend (String, JsonObject) -> McpCallResult,
) : Tool {

    override val spec = ToolSpec(
        name = toolName(serverId, info.name),
        description = "[$serverLabel] " + info.description.replace(Regex("\\s+"), " ").trim().take(MAX_DESCRIPTION)
            .ifBlank { info.title ?: info.name },
        parameters = sanitizeSchema(info.inputSchema),
    )

    /** Lo que solo lee no pide permiso; lo que cambia algo, sí (se puede cambiar en Permisos). */
    override val category = if (info.readOnly && !info.destructive) ActionCategory.PROGRAM_READ else ActionCategory.PROGRAM_CHANGE

    override fun confirmationQuestion(args: JsonObject) =
        "¿Dejo que $serverLabel haga «${info.title ?: info.name.replace('_', ' ')}»?"

    override fun progressText(args: JsonObject) = "$serverLabel: ${info.title ?: info.name.replace('_', ' ')}…"

    override suspend fun run(args: JsonObject): ToolResult {
        val result = call(info.name, args)
        return ToolResult(result.text, result.images.firstOrNull(), result.isError)
    }

    companion object {
        private const val MAX_DESCRIPTION = 280

        /** Nombre válido para todos los proveedores: letras, números, _ y -, hasta 64. */
        fun toolName(serverId: String, tool: String): String {
            val prefix = serverId.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(20)
            val name = tool.replace(Regex("[^A-Za-z0-9_-]+"), "_")
            return "${prefix}__$name".take(64)
        }

        /**
         * Quita del JSON Schema lo que algunos proveedores rechazan ($schema,
         * $ref sin resolver, additionalProperties en Gemini…). Lo esencial
         * (tipos, propiedades, requeridos, enums, descripciones) queda igual.
         */
        fun sanitizeSchema(schema: JsonObject): JsonObject {
            val clean = sanitize(schema) as? JsonObject ?: JsonObject(emptyMap())
            return if (clean["type"] == null) JsonObject(clean + ("type" to JsonPrimitive("object"))) else clean
        }

        private val DROP = setOf("\$schema", "\$id", "\$comment", "\$defs", "definitions", "additionalProperties", "examples", "default", "title")

        private fun sanitize(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> {
                if (element.containsKey("\$ref")) {
                    buildJsonObject {
                        put("type", "object")
                        element["description"]?.let { put("description", it) }
                    }
                } else {
                    JsonObject(element.filterKeys { it !in DROP }.mapValues { (key, value) ->
                        // Dentro de "properties" las claves son nombres de campos, no palabras de schema.
                        if (key == "properties" && value is JsonObject) {
                            JsonObject(value.mapValues { (_, v) -> sanitize(v) })
                        } else {
                            sanitize(value)
                        }
                    })
                }
            }
            is JsonArray -> JsonArray(element.map { sanitize(it) })
            else -> element
        }
    }
}
