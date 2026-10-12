package com.controlxia.brain

import com.controlxia.brain.agent.ActionStatus
import com.controlxia.brain.agent.Assistant
import com.controlxia.brain.llm.ChatMessage
import com.controlxia.brain.llm.ChatRole
import com.controlxia.brain.llm.LlmConfig
import com.controlxia.brain.llm.LlmException
import com.controlxia.brain.llm.LlmProvider
import com.controlxia.brain.llm.LlmProviderType
import com.controlxia.brain.llm.LlmResponse
import com.controlxia.brain.llm.ToolCall
import com.controlxia.brain.llm.ToolSpec
import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.PermissionPolicy
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.schema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantTest {

    private val config = LlmConfig(LlmProviderType.ANTHROPIC, "key", "model")

    /** Proveedor guionado: devuelve las respuestas en orden y registra lo recibido. */
    private class ScriptedProvider(vararg responses: LlmResponse) : LlmProvider {
        val queue = ArrayDeque(responses.toList())
        val received = mutableListOf<List<ChatMessage>>()
        override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse {
            received += messages
            return queue.removeFirst()
        }
    }

    private class FakeTool(
        name: String,
        override val category: ActionCategory,
        override val speakDirectly: Boolean = false,
        private val output: String = "hecho",
    ) : Tool {
        var runs = 0
        override val spec = ToolSpec(name, "desc", schema { })
        override suspend fun run(args: JsonObject): ToolResult {
            runs++
            return ToolResult(output)
        }
    }

    private fun text(t: String) = LlmResponse(t, emptyList(), ChatMessage(ChatRole.ASSISTANT, t))

    private fun call(name: String, id: String = "id_$name"): LlmResponse {
        val c = ToolCall(id, name, JsonObject(emptyMap()))
        return LlmResponse("", listOf(c), ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(c)))
    }

    private fun assistant(
        provider: LlmProvider,
        tools: List<Tool>,
        policies: Map<ActionCategory, PermissionPolicy> = emptyMap(),
        confirm: Boolean = true,
        configs: List<LlmConfig> = listOf(config),
        factory: (LlmConfig) -> LlmProvider = { provider },
    ) = Assistant(
        llmConfigs = { configs },
        tools = { tools },
        permissions = { policies[it] ?: it.defaultPolicy },
        confirmer = { confirm },
        systemPrompt = { "sistema" },
        providerFactory = factory,
    )

    @Test
    fun `respuesta de texto se dice tal cual`() = runTest {
        val reply = assistant(ScriptedProvider(text("Hola")), emptyList()).process("hola")
        assertEquals("Hola", reply.spoken)
    }

    @Test
    fun `tool de respuesta directa evita una segunda llamada al LLM`() = runTest {
        val tool = FakeTool("get_time", ActionCategory.INFO, speakDirectly = true, output = "Son las 10")
        val provider = ScriptedProvider(call("get_time"))
        val reply = assistant(provider, listOf(tool)).process("qué hora es")
        assertEquals("Son las 10", reply.spoken)
        assertEquals(1, provider.received.size)
    }

    @Test
    fun `bucle de varios pasos devuelve el resultado al modelo`() = runTest {
        val open = FakeTool("open_app", ActionCategory.OPEN_APPS)
        val provider = ScriptedProvider(call("open_app"), text("Abrí Spotify"))
        val reply = assistant(provider, listOf(open)).process("abrí spotify")
        assertEquals("Abrí Spotify", reply.spoken)
        assertEquals(1, open.runs)
        val second = provider.received[1]
        val toolMsg = second.last()
        assertEquals(ChatRole.TOOL, toolMsg.role)
        assertEquals("id_open_app", toolMsg.toolCallId)
    }

    @Test
    fun `accion riesgosa sin confirmar no se ejecuta`() = runTest {
        val shutdown = FakeTool("shutdown", ActionCategory.POWER)
        val reply = assistant(ScriptedProvider(call("shutdown")), listOf(shutdown), confirm = false)
            .process("apagá la pc")
        assertEquals(0, shutdown.runs)
        assertEquals(ActionStatus.DENIED, reply.actions.single().status)
        assertEquals("Listo, no lo hago.", reply.spoken)
    }

    @Test
    fun `accion riesgosa confirmada se ejecuta`() = runTest {
        val shutdown = FakeTool("shutdown", ActionCategory.POWER)
        assistant(ScriptedProvider(call("shutdown"), text("Apagando")), listOf(shutdown), confirm = true)
            .process("apagá la pc")
        assertEquals(1, shutdown.runs)
    }

    @Test
    fun `categoria bloqueada no se ofrece ni se ejecuta`() = runTest {
        val shutdown = FakeTool("shutdown", ActionCategory.POWER)
        val provider = ScriptedProvider(call("shutdown"), text("No puedo"))
        val reply = assistant(
            provider, listOf(shutdown), policies = mapOf(ActionCategory.POWER to PermissionPolicy.BLOCK),
        ).process("apagá la pc")
        assertEquals(0, shutdown.runs)
        assertEquals("No puedo", reply.spoken)
    }

    @Test
    fun `si el proveedor principal falla usa el de respaldo`() = runTest {
        val backup = LlmConfig(LlmProviderType.OPENAI, "k", "m")
        val failing = object : LlmProvider {
            override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse =
                throw LlmException("HTTP 500")
        }
        val ok = ScriptedProvider(text("Desde el respaldo"))
        val reply = assistant(
            ok, emptyList(), configs = listOf(config, backup),
            factory = { if (it == config) failing else ok },
        ).process("hola")
        assertEquals("Desde el respaldo", reply.spoken)
    }

    @Test
    fun `sin configuracion pide configurar`() = runTest {
        val reply = assistant(ScriptedProvider(), emptyList(), configs = emptyList()).process("hola")
        assertTrue(reply.spoken.contains("configures"))
    }

    @Test
    fun `la memoria mantiene el contexto entre pedidos`() = runTest {
        val provider = ScriptedProvider(text("Uno"), text("Dos"))
        val a = assistant(provider, emptyList())
        a.process("primero")
        a.process("segundo")
        val history = provider.received[1].filter { it.role != ChatRole.SYSTEM }.map { it.content }
        assertEquals(listOf("primero", "Uno", "segundo"), history)
    }
}
