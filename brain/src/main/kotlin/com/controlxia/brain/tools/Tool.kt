package com.controlxia.brain.tools

import com.controlxia.brain.llm.ImageData
import com.controlxia.brain.llm.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * Una herramienta que el LLM puede invocar. Cada plataforma (Windows,
 * Android) registra las suyas; el agente solo conoce esta interfaz.
 */
interface Tool {
    val spec: ToolSpec

    /** Categoría de permiso: decide si se pide confirmación (RF-17/RF-18). */
    val category: ActionCategory

    /**
     * Si es true y es la única acción del pedido, su resultado se dice tal
     * cual sin volver a consultar al LLM (ahorra un viaje de ida y vuelta).
     */
    val speakDirectly: Boolean get() = false

    /** Pregunta de confirmación, ej. "¿Apago la computadora?". */
    fun confirmationQuestion(args: JsonObject): String =
        "¿Ejecuto ${spec.name}?"

    /** Texto de estado mientras corre, ej. "Abriendo Spotify…". */
    fun progressText(args: JsonObject): String = "Trabajando…"

    /** Ejecuta la acción. Las excepciones se reportan al LLM como error. */
    suspend fun run(args: JsonObject): ToolResult
}

/** Resultado de una tool: texto para el LLM (o para decir) y una imagen opcional. */
data class ToolResult(
    val text: String,
    val image: ImageData? = null,
    val isError: Boolean = false,
)

/**
 * Atajo para definir una tool en pocas líneas:
 * ```
 * tool("get_time", "Dice la hora actual.", ActionCategory.INFO) { ToolResult("Son las 10") }
 * ```
 */
fun tool(
    name: String,
    description: String,
    category: ActionCategory,
    parameters: JsonObject = schema { },
    speakDirectly: Boolean = false,
    confirm: (JsonObject) -> String = { "¿Ejecuto $name?" },
    progress: (JsonObject) -> String = { "Trabajando…" },
    run: suspend (JsonObject) -> ToolResult,
): Tool = object : Tool {
    override val spec = ToolSpec(name, description, parameters)
    override val category = category
    override val speakDirectly = speakDirectly
    override fun confirmationQuestion(args: JsonObject) = confirm(args)
    override fun progressText(args: JsonObject) = progress(args)
    override suspend fun run(args: JsonObject) = run(args)
}
