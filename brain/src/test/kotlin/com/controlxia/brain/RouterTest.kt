package com.controlxia.brain

import com.controlxia.brain.agent.Assistant
import com.controlxia.brain.agent.TaskCheck
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
import com.controlxia.brain.tools.ToolGroup
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.ToolRouter
import com.controlxia.brain.tools.str
import com.controlxia.brain.tools.tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouterTest {

    private val core = ToolGroup("basico", "hora y abrir", emptyList(), listOf(tool("get_time", "hora", ActionCategory.INFO) { ToolResult("10") }), alwaysOn = true)
    private var excelRuns = 0
    private val excel = ToolGroup(
        "excel", "planillas de Excel", listOf("excel", "planilla"),
        listOf(tool("excel__write", "escribe", ActionCategory.PROGRAM_READ) { excelRuns++; ToolResult("escrito") }),
    )
    private val windows = ToolGroup("ventanas", "ventanas", listOf("ventana", "minimiza"), listOf(tool("minimize", "min", ActionCategory.INFO) { ToolResult("ok") }))

    @Test
    fun `elige grupos por palabras clave sin importar tildes`() {
        assertEquals(setOf("excel"), ToolRouter.matching("Abrí la PLANILLA de gastos", listOf(core, excel, windows)))
        assertEquals(setOf("ventanas"), ToolRouter.matching("minimizá todo", listOf(core, excel, windows)))
        assertEquals(emptySet(), ToolRouter.matching("qué hora es", listOf(core, excel, windows)))
    }

    private class Scripted(vararg r: LlmResponse) : LlmProvider {
        val queue = ArrayDeque(r.toList())
        val offered = mutableListOf<List<String>>()
        override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse {
            offered += tools.map { it.name }
            return queue.removeFirst()
        }
    }

    private fun call(name: String, vararg args: Pair<String, String>): LlmResponse {
        val c = ToolCall("id_$name", name, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))
        return LlmResponse("", listOf(c), ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(c)))
    }

    private fun text(t: String) = LlmResponse(t, emptyList(), ChatMessage(ChatRole.ASSISTANT, t))

    private fun assistant(p: LlmProvider, groups: List<ToolGroup> = listOf(core, excel, windows), confirm: Boolean = true) = Assistant(
        llmConfigs = { listOf(LlmConfig(LlmProviderType.OLLAMA, "", "m")) },
        tools = { emptyList() },
        permissions = { it.defaultPolicy },
        confirmer = { confirm },
        systemPrompt = { "s" },
        providerFactory = { p },
        toolGroups = { groups },
    )

    @Test
    fun `solo manda los grupos del pedido y load_tools para el resto`() = runTest {
        val p = Scripted(text("Son las 10"))
        assistant(p).process("qué hora es")
        assertEquals(listOf("get_time", "load_tools"), p.offered.single())
    }

    @Test
    fun `el modelo carga un grupo que le falta y lo usa`() = runTest {
        val p = Scripted(call("load_tools", "group" to "excel"), call("excel__write"), text("Anoté el gasto"))
        val reply = assistant(p).process("anotá 500 pesos de nafta")
        assertEquals("Anoté el gasto", reply.spoken)
        assertEquals(1, excelRuns)
        assertFalse("excel__write" in p.offered[0])
        assertTrue("excel__write" in p.offered[1])
    }

    @Test
    fun `los grupos usados siguen disponibles en el pedido siguiente`() = runTest {
        val p = Scripted(call("excel__write"), text("Listo, escrito"), text("Dale"))
        val a = assistant(p)
        a.process("escribí en la planilla 42")
        a.process("y ahora sumale 3")
        assertTrue("excel__write" in p.offered.last())
    }

    @Test
    fun `si dice que hizo algo sin herramientas se lo marca una vez`() = runTest {
        val p = Scripted(text("Listo, abrí Spotify."), call("get_time"), text("Ahora sí"))
        assertEquals("Ahora sí", assistant(p).process("abrí spotify").spoken)
    }

    @Test
    fun `si el control no hacia falta el modelo contesta OK y queda su respuesta`() = runTest {
        val p = Scripted(text("Listo, ya está."), text("OK"))
        assertEquals("Listo, ya está.", assistant(p).process("decime algo lindo").spoken)
    }

    @Test
    fun `control de tarea`() {
        assertTrue(TaskCheck.claimsUndoneAction("abrí spotify", "Listo, abrí Spotify", 0))
        assertFalse(TaskCheck.claimsUndoneAction("abrí spotify", "Listo, abrí Spotify", 1))
        assertFalse(TaskCheck.claimsUndoneAction("¿qué es Spotify?", "Es una app; listo", 0))
        assertFalse(TaskCheck.claimsUndoneAction("contame un chiste", "Ahí va uno", 0))
    }

    @Test
    fun `tocar algo delicado pregunta cada vez aunque la categoria comun este permitida`() = runTest {
        var asked = 0
        val tap = object : Tool {
            override val spec = ToolSpec("screen_tap", "toca", com.controlxia.brain.tools.schema { string("label", "x") })
            override val category = ActionCategory.SCREEN_CONTROL
            override fun categoryFor(args: JsonObject) = if (args.str("label") == "Enviar") ActionCategory.SENSITIVE else category
            override suspend fun run(args: JsonObject) = ToolResult("tocado")
        }
        val group = ToolGroup("programa", "x", emptyList(), listOf(tap), alwaysOn = true)
        val p = Scripted(call("screen_tap", "label" to "Aceptar"), call("screen_tap", "label" to "Enviar"), text("ok listo"))
        val a = Assistant(
            llmConfigs = { listOf(LlmConfig(LlmProviderType.OLLAMA, "", "m")) },
            tools = { emptyList() },
            permissions = { if (it == ActionCategory.SENSITIVE) PermissionPolicy.ASK else PermissionPolicy.ALLOW },
            confirmer = { asked++; false },
            systemPrompt = { "s" },
            providerFactory = { p },
            toolGroups = { listOf(group) },
        )
        val reply = a.process("mandalo")
        assertEquals(1, asked)
        assertEquals("Listo, no lo hago.", reply.spoken)
    }

    @Test
    fun `sin respaldo espera lo que pide el proveedor y reintenta`() = runTest {
        var calls = 0
        val p = object : LlmProvider {
            override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse {
                calls++
                if (calls == 1) throw LlmException("HTTP 429", statusCode = 429).apply { retryAfterMs = 12_000 }
                return text("Ya está")
            }
        }
        val progress = mutableListOf<String>()
        val reply = assistant(p).process("contame algo") { progress += it }
        assertEquals("Ya está", reply.spoken)
        assertTrue(progress.any { it.contains("esperar 12 s") })
    }
}
