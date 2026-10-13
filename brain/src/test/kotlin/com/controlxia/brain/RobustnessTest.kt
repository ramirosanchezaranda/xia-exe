package com.controlxia.brain

import com.controlxia.brain.agent.Assistant
import com.controlxia.brain.llm.ChatMessage
import com.controlxia.brain.llm.ChatRole
import com.controlxia.brain.llm.HttpJson
import com.controlxia.brain.llm.ImageData
import com.controlxia.brain.llm.LlmConfig
import com.controlxia.brain.llm.LlmException
import com.controlxia.brain.llm.LlmProvider
import com.controlxia.brain.llm.LlmProviderFactory
import com.controlxia.brain.llm.LlmProviderType
import com.controlxia.brain.llm.LlmResponse
import com.controlxia.brain.llm.OllamaProvider
import com.controlxia.brain.llm.ToolCall
import com.controlxia.brain.llm.ToolSpec
import com.controlxia.brain.llm.retryAfter
import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.schema
import com.controlxia.brain.tools.tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobustnessTest {

    @Test
    fun `lee cuanto esperar ante un 429`() {
        assertEquals(12_465L, retryAfter(null, "Please try again in 12.465s. Need more tokens?"))
        assertEquals(300L, retryAfter(null, "try again in 300ms"))
        assertEquals(2_000L, retryAfter("2", ""))
        assertEquals(null, retryAfter(null, "otro error"))
    }

    @Test
    fun `los errores se dicen cortos, sin el JSON del proveedor`() {
        val e = LlmException("HTTP 429: {\"error\":{...}}", statusCode = 429)
        assertTrue(e.spokenMessage.contains("limitando"))
        assertFalse(e.spokenMessage.contains("{"))
        assertTrue(LlmException("x", statusCode = 401).spokenMessage.contains("clave"))
    }

    @Test
    fun `un 429 corto se reintenta una vez y despues anda`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Please try again in 0.1s."}}"""))
        server.enqueue(MockResponse().setBody("""{"message":{"role":"assistant","content":"hola"}}"""))
        server.start()
        val provider = OllamaProvider("qwen3:8b", server.url("/").toString())
        assertEquals("hola", provider.complete(listOf(ChatMessage(ChatRole.USER, "hola"))).text)
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `un 429 largo no se espera y pasa al respaldo`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Please try again in 12.465s."}}"""))
        server.start()
        val error = runCatching {
            OllamaProvider("m", server.url("/").toString()).complete(listOf(ChatMessage(ChatRole.USER, "hola")))
        }.exceptionOrNull() as LlmException
        assertEquals(429, error.statusCode)
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `ollama nativo fija contexto, mantiene el modelo y manda tools`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"open_folder","arguments":{"folder":"documentos"}}}]}}""",
        ))
        server.start()
        // La URL vieja con /v1 también funciona.
        val config = LlmConfig(LlmProviderType.OLLAMA, "", "qwen3:8b", server.url("/v1").toString())
        val response = LlmProviderFactory.create(config).complete(
            listOf(ChatMessage(ChatRole.USER, "abrí documentos")),
            listOf(ToolSpec("open_folder", "Abre carpetas", schema { string("folder", "carpeta", required = true) })),
        )
        assertEquals("documentos", response.toolCalls.single().arguments["folder"]!!.jsonPrimitive.content)
        val request = server.takeRequest()
        assertEquals("/api/chat", request.path)
        val body = HttpJson.json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(OllamaProvider.DEFAULT_CONTEXT, body["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.int)
        assertEquals("30m", body["keep_alive"]!!.jsonPrimitive.content)
        assertEquals("false", body["stream"]!!.jsonPrimitive.content)
        assertEquals(1, body["tools"]!!.jsonArray.size)
        server.shutdown()
    }

    @Test
    fun `ollama devuelve resultados de tools y capturas en el formato nativo`() {
        val call = ToolCall("c1", "look_at_screen", JsonObject(emptyMap()))
        val wire = OllamaProvider("m", "http://localhost:11434").toWireMessages(
            listOf(
                ChatMessage(ChatRole.USER, "mirá"),
                ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(call)),
                ChatMessage(ChatRole.TOOL, "captura", images = listOf(ImageData("AAAA")), toolCallId = "c1", toolName = "look_at_screen"),
            ),
        )
        assertEquals(listOf("user", "assistant", "tool", "user"), wire.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("look_at_screen", wire[2].jsonObject["tool_name"]!!.jsonPrimitive.content)
        assertEquals("AAAA", wire[3].jsonObject["images"]!!.jsonArray[0].jsonPrimitive.content)
        // Argumentos como texto JSON también se aceptan.
        val parsed = OllamaProvider("m", "http://x").parse(
            HttpJson.json.parseToJsonElement(
                """{"message":{"content":"","tool_calls":[{"function":{"name":"t","arguments":"{\"a\":1}"}}]}}""",
            ).jsonObject,
        )
        assertEquals(JsonPrimitive(1), parsed.toolCalls.single().arguments["a"])
    }

    @Test
    fun `una accion identica repetida se frena`() = runTest {
        var runs = 0
        val openApp = tool("open_app", "Abre programas", ActionCategory.OPEN_APPS) { runs++; ToolResult("Abriendo Internet Explorer") }
        val sameCall = { LlmResponse("", listOf(ToolCall("x", "open_app", JsonObject(emptyMap()))), ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(ToolCall("x", "open_app", JsonObject(emptyMap()))))) }
        val responses = ArrayDeque(listOf(sameCall(), sameCall(), sameCall(), sameCall(), LlmResponse("No pude", emptyList(), ChatMessage(ChatRole.ASSISTANT, "No pude"))))
        val provider = object : LlmProvider {
            override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>) = responses.removeFirst()
        }
        val reply = Assistant(
            llmConfigs = { listOf(LlmConfig(LlmProviderType.OLLAMA, "", "m")) },
            tools = { listOf(openApp) },
            permissions = { it.defaultPolicy },
            confirmer = { true },
            systemPrompt = { "s" },
            providerFactory = { provider },
        ).process("abrí documentos")
        assertEquals(2, runs)
        assertEquals("No pude", reply.spoken)
    }
}
