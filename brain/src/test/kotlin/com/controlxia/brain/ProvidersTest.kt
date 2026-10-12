package com.controlxia.brain

import com.controlxia.brain.llm.AnthropicProvider
import com.controlxia.brain.llm.ChatMessage
import com.controlxia.brain.llm.ChatRole
import com.controlxia.brain.llm.GeminiProvider
import com.controlxia.brain.llm.HttpJson
import com.controlxia.brain.llm.ImageData
import com.controlxia.brain.llm.OpenAiCompatibleProvider
import com.controlxia.brain.llm.ToolCall
import com.controlxia.brain.llm.ToolSpec
import com.controlxia.brain.tools.schema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProvidersTest {

    private val call = ToolCall("tu_1", "look_at_screen", JsonObject(emptyMap()))
    private val history = listOf(
        ChatMessage(ChatRole.USER, "mirá la pantalla"),
        ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(call)),
        ChatMessage(
            ChatRole.TOOL, "captura 1280x720", images = listOf(ImageData("AAAA")),
            toolCallId = "tu_1", toolName = "look_at_screen",
        ),
    )

    @Test
    fun `anthropic agrupa tool_result con imagen en un mensaje user`() {
        val wire = AnthropicProvider("k", "m").toWireMessages(history)
        assertEquals(3, wire.size)
        val result = wire[2].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("tu_1", result["tool_use_id"]!!.jsonPrimitive.content)
        val blocks = result["content"]!!.jsonArray
        assertEquals("image", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic parsea tool_use y conserva el contenido crudo`() {
        val response = HttpJson.json.parseToJsonElement(
            """{"content":[{"type":"thinking","thinking":"","signature":"s"},
               {"type":"text","text":"Abro"},
               {"type":"tool_use","id":"tu_9","name":"open_app","input":{"app_name":"Spotify"}}],
               "stop_reason":"tool_use"}""",
        ).jsonObject
        val parsed = AnthropicProvider("k", "m").parse(response)
        assertEquals("Abro", parsed.text)
        assertEquals("Spotify", parsed.toolCalls.single().arguments["app_name"]!!.jsonPrimitive.content)
        // El bloque de thinking vuelve intacto en el siguiente pedido.
        val replay = AnthropicProvider("k", "m").toWireMessages(listOf(parsed.assistantMessage))
        assertEquals(3, replay[0].jsonObject["content"]!!.jsonArray.size)
    }

    @Test
    fun `openai pone las imagenes de tools en un mensaje user aparte`() {
        val wire = OpenAiCompatibleProvider("", "m", "http://x/v1").toWireMessages(history)
        assertEquals(listOf("user", "assistant", "tool", "user"), wire.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val args = wire[1].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!
        assertEquals("{}", args.jsonPrimitive.content)
    }

    @Test
    fun `openai parsea content null sin devolver la palabra null`() {
        val response = HttpJson.json.parseToJsonElement(
            """{"choices":[{"message":{"role":"assistant","content":null,
               "tool_calls":[{"id":"c1","type":"function","function":{"name":"get_time","arguments":"{}"}}]}}]}""",
        ).jsonObject
        val parsed = OpenAiCompatibleProvider("", "m", "http://x/v1").parse(response)
        assertEquals("", parsed.text)
        assertEquals("get_time", parsed.toolCalls.single().name)
    }

    @Test
    fun `gemini manda functionResponse e imagen en el mismo turno user`() {
        val wire = GeminiProvider("k", "m").toWireContents(history)
        assertEquals(listOf("user", "model", "user"), wire.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val parts = wire[2].jsonObject["parts"]!!.jsonArray
        assertTrue(parts[0].jsonObject.containsKey("functionResponse"))
        assertTrue(parts[1].jsonObject.containsKey("inlineData"))
    }

    @Test
    fun `anthropic por HTTP envia tools y devuelve el error real de la API`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid x-api-key"}}"""))
        server.start()
        val provider = AnthropicProvider("mala", "claude-haiku-5-5", baseUrl = server.url("/").toString())
        val error = runCatching {
            provider.complete(listOf(ChatMessage(ChatRole.USER, "hola")), listOf(ToolSpec("t", "d", schema { })))
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("invalid x-api-key"))
        val sent = HttpJson.json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(sent["tools"] is JsonArray)
        server.shutdown()
    }
}
