package com.controlxia.brain.agent

import com.controlxia.brain.llm.ChatMessage
import com.controlxia.brain.llm.ChatRole
import com.controlxia.brain.llm.LlmConfig
import com.controlxia.brain.llm.LlmException
import com.controlxia.brain.llm.LlmProvider
import com.controlxia.brain.llm.LlmProviderFactory
import com.controlxia.brain.llm.LlmResponse
import com.controlxia.brain.llm.ToolCall
import com.controlxia.brain.llm.ToolSpec
import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Confirmer
import com.controlxia.brain.tools.PermissionPolicy
import com.controlxia.brain.tools.PermissionStore
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** Qué pasó con una acción pedida por el modelo. */
enum class ActionStatus { DONE, FAILED, DENIED, BLOCKED }

data class ExecutedAction(
    val tool: String,
    val arguments: JsonObject,
    val result: String,
    val status: ActionStatus,
)

/** Resultado de procesar un pedido: la frase a decir y lo que se ejecutó. */
data class AssistantReply(
    val spoken: String,
    val actions: List<ExecutedAction> = emptyList(),
    val error: String? = null,
)

/**
 * Cerebro del asistente. Toma el pedido transcripto, lo manda al LLM con el
 * catálogo de tools y ejecuta en bucle lo que el modelo pida hasta que
 * responde con texto (ej. "abrí el bloc de notas y escribí hola" son varios
 * pasos). Aplica las políticas de permiso de cada acción (RF-17/RF-18) y usa
 * proveedores de respaldo si el principal falla.
 *
 * Nunca lanza (salvo cancelación): los errores se traducen a una frase.
 */
class Assistant(
    /** Proveedor principal seguido de los de respaldo. Vacío = sin configurar. */
    private val llmConfigs: () -> List<LlmConfig>,
    private val tools: () -> List<Tool>,
    private val permissions: PermissionStore,
    private val confirmer: Confirmer,
    private val systemPrompt: () -> String,
    private val providerFactory: (LlmConfig) -> LlmProvider = LlmProviderFactory::create,
    private val maxSteps: Int = 15,
) {

    val memory = ConversationMemory()
    private val mutex = Mutex()

    suspend fun process(
        command: String,
        onProgress: (String) -> Unit = {},
    ): AssistantReply = mutex.withLock {
        val configs = llmConfigs()
        if (configs.isEmpty()) {
            return AssistantReply(
                spoken = "Necesito que configures el cerebro para poder entenderte.",
                error = "LLM sin configurar",
            )
        }

        val available = tools().filter { permissions.policy(it.category) != PermissionPolicy.BLOCK }
        val byName = available.associateBy { it.spec.name }
        val specs = available.map { it.spec }
        val actions = mutableListOf<ExecutedAction>()
        // Dentro de un mismo pedido, una categoría confirmada no se vuelve a preguntar.
        val approved = mutableSetOf<ActionCategory>()
        // Cuántas veces se pidió cada acción idéntica en este pedido.
        val repeats = mutableMapOf<String, Int>()

        memory.startTurn(command)

        try {
            repeat(maxSteps) { step ->
                val messages = buildList {
                    add(ChatMessage(ChatRole.SYSTEM, systemPrompt()))
                    addAll(memory.history())
                }
                val response = completeWithFallback(configs, messages, specs)
                memory.add(response.assistantMessage)

                if (response.toolCalls.isEmpty()) {
                    val spoken = toSpeakable(response.text).ifBlank { "Listo." }
                    return AssistantReply(spoken, actions)
                }

                var denied = false
                val results = mutableListOf<Pair<Tool?, ToolResult>>()
                for (call in response.toolCalls) {
                    val tool = byName[call.name]
                    val signature = call.name + call.arguments.toString()
                    val count = (repeats[signature] ?: 0) + 1
                    repeats[signature] = count
                    val result = when {
                        denied -> ToolResult("No ejecutado: el usuario canceló el pedido.", isError = true)
                        count > MAX_IDENTICAL_CALLS -> ToolResult(
                            "No repitas esta acción con los mismos datos: ya se ejecutó. Si no dio el resultado " +
                                "esperado, probá otra herramienta o decile al usuario qué pasó.",
                            isError = true,
                        )
                        tool == null -> ToolResult("La herramienta ${call.name} no existe o está bloqueada.", isError = true)
                        else -> {
                            val outcome = authorize(tool, call, approved)
                            if (outcome != null) {
                                if (outcome.status == ActionStatus.DENIED) denied = true
                                actions += outcome
                                ToolResult(outcome.result, isError = true)
                            } else {
                                onProgress(tool.progressText(call.arguments))
                                runTool(tool, call).also { r ->
                                    actions += ExecutedAction(
                                        tool = call.name,
                                        arguments = call.arguments,
                                        result = r.text,
                                        status = if (r.isError) ActionStatus.FAILED else ActionStatus.DONE,
                                    )
                                }
                            }
                        }
                    }
                    results += tool to result
                    memory.add(
                        ChatMessage(
                            role = ChatRole.TOOL,
                            content = result.text,
                            images = listOfNotNull(result.image),
                            toolCallId = call.id,
                            toolName = call.name,
                            isError = result.isError,
                        ),
                    )
                }

                if (denied) {
                    val spoken = "Listo, no lo hago."
                    memory.add(ChatMessage(ChatRole.ASSISTANT, spoken))
                    return AssistantReply(spoken, actions)
                }

                // Atajo de latencia: "¿qué hora es?" no necesita otra vuelta al LLM.
                val direct = step == 0 && results.all { (tool, r) -> tool?.speakDirectly == true && !r.isError }
                if (direct) {
                    val spoken = results.joinToString(". ") { it.second.text }
                    memory.add(ChatMessage(ChatRole.ASSISTANT, spoken))
                    return AssistantReply(spoken, actions)
                }
                onProgress("Pensando…")
            }
            val spoken = "Me quedé sin pasos para terminar eso. ¿Lo intentamos de otra forma?"
            memory.add(ChatMessage(ChatRole.ASSISTANT, spoken))
            return AssistantReply(spoken, actions, error = "Límite de $maxSteps pasos")
        } catch (e: CancellationException) {
            memory.add(ChatMessage(ChatRole.ASSISTANT, "(pedido cancelado)"))
            throw e
        } catch (e: LlmException) {
            // Se dice una frase corta; el JSON del proveedor queda en el historial.
            memory.add(ChatMessage(ChatRole.ASSISTANT, "(error: ${e.message})"))
            return AssistantReply(e.spokenMessage, actions, error = e.message)
        } catch (e: Exception) {
            val spoken = "Tuve un problema para procesar eso: ${e.message}"
            memory.add(ChatMessage(ChatRole.ASSISTANT, "(error: ${e.message})"))
            return AssistantReply(spoken, actions, error = e.message)
        }
    }

    /** Devuelve null si la acción puede ejecutarse, o el motivo por el que no. */
    private suspend fun authorize(
        tool: Tool,
        call: ToolCall,
        approved: MutableSet<ActionCategory>,
    ): ExecutedAction? = when (permissions.policy(tool.category)) {
        PermissionPolicy.ALLOW -> null
        PermissionPolicy.BLOCK -> ExecutedAction(
            call.name, call.arguments, "Acción bloqueada por el usuario en la configuración.", ActionStatus.BLOCKED,
        )
        PermissionPolicy.ASK -> {
            if (tool.category in approved) {
                null
            } else if (confirmer.confirm(tool.confirmationQuestion(call.arguments))) {
                approved += tool.category
                null
            } else {
                ExecutedAction(call.name, call.arguments, "El usuario no autorizó la acción.", ActionStatus.DENIED)
            }
        }
    }

    private suspend fun runTool(tool: Tool, call: ToolCall): ToolResult = try {
        tool.run(call.arguments)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult("Error al ejecutar ${call.name}: ${e.message}", isError = true)
    }

    private companion object {
        const val MAX_IDENTICAL_CALLS = 2
    }

    private suspend fun completeWithFallback(
        configs: List<LlmConfig>,
        messages: List<ChatMessage>,
        specs: List<ToolSpec>,
    ): LlmResponse {
        var firstError: LlmException? = null
        for (config in configs) {
            try {
                return providerFactory(config).complete(messages, specs)
            } catch (e: LlmException) {
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: LlmException("Sin proveedores de LLM")
    }
}
