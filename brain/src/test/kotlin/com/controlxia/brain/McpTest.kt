package com.controlxia.brain

import com.controlxia.brain.llm.HttpJson
import com.controlxia.brain.mcp.HttpTransport
import com.controlxia.brain.mcp.McpClient
import com.controlxia.brain.mcp.McpTool
import com.controlxia.brain.mcp.McpToolInfo
import com.controlxia.brain.mcp.McpTransport
import com.controlxia.brain.mcp.StdioTransport
import com.controlxia.brain.tools.ActionCategory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpTest {

    /** Servidor MCP falso en memoria: contesta como lo haría uno real. */
    private class FakeServer : McpTransport {
        lateinit var deliver: (JsonObject) -> Unit
        val received = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
        override suspend fun start(onMessage: (JsonObject) -> Unit) { deliver = onMessage }
        override suspend fun send(message: JsonObject) {
            received += message
            val id = message["id"] ?: return
            val method = (message["method"] as? JsonPrimitive)?.content ?: return // respuesta del cliente
            val result = when (method) {
                "initialize" -> """{"protocolVersion":"2025-06-18","serverInfo":{"name":"excel","version":"1.0"},"capabilities":{}}"""
                "tools/list" -> if ((message["params"] as JsonObject)["cursor"] == null) {
                    """{"tools":[{"name":"read_data","description":"Lee celdas","inputSchema":{"type":"object","properties":{"path":{"type":"string"}}},"annotations":{"readOnlyHint":true}}],"nextCursor":"p2"}"""
                } else {
                    """{"tools":[{"name":"write_data","description":"Escribe celdas","inputSchema":{"type":"object","properties":{"cells":{"type":"string"}}}}]}"""
                }
                "tools/call" -> """{"content":[{"type":"text","text":"A1 = 42"}],"isError":false}"""
                else -> """{}"""
            }
            deliver(HttpJson.json.parseToJsonElement("""{"jsonrpc":"2.0","id":$id,"result":$result}""").jsonObject)
        }
        override fun close() = Unit
    }

    @Test
    fun `saluda, lista con paginas y llama herramientas`() = runTest {
        val server = FakeServer()
        val client = McpClient(server)
        assertEquals("excel", client.connect().name)
        assertTrue(server.received.any { (it["method"] as? JsonPrimitive)?.content == "notifications/initialized" })
        val tools = client.listTools()
        assertEquals(listOf("read_data", "write_data"), tools.map { it.name })
        assertTrue(tools[0].readOnly)
        assertEquals("A1 = 42", client.callTool("read_data", JsonObject(emptyMap())).text)
    }

    @Test
    fun `contesta el ping del servidor`() = runBlocking {
        val server = FakeServer()
        val client = McpClient(server)
        client.connect()
        server.deliver(HttpJson.json.parseToJsonElement("""{"jsonrpc":"2.0","id":"s1","method":"ping"}""").jsonObject)
        repeat(40) { if (server.received.none { (it["id"] as? JsonPrimitive)?.content == "s1" }) Thread.sleep(25) }
        assertTrue(server.received.any { (it["id"] as? JsonPrimitive)?.content == "s1" && it["result"] != null })
    }

    @Test
    fun `adapta herramientas MCP con nombre valido, esquema limpio y permisos`() {
        val info = McpToolInfo(
            "write data!", null, "Escribe celdas en un libro de Excel",
            HttpJson.json.parseToJsonElement(
                """{"${'$'}schema":"x","type":"object","additionalProperties":false,"properties":{"title":{"type":"string","default":"a"},"cells":{"${'$'}ref":"#/x"}}}""",
            ).jsonObject,
            readOnly = false, destructive = false,
        )
        val tool = McpTool("Excel (local)", "Excel", info) { _, _ -> error("no") }
        assertEquals("excel_local__write_data_", tool.spec.name)
        assertTrue(tool.spec.description.startsWith("[Excel] "))
        val schema = tool.spec.parameters
        assertFalse(schema.containsKey("\$schema") || schema.containsKey("additionalProperties"))
        val props = schema["properties"]!!.jsonObject
        assertTrue(props.containsKey("title"), "un campo llamado title no se borra")
        assertFalse(props["title"]!!.jsonObject.containsKey("default"))
        assertEquals("object", props["cells"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(ActionCategory.PROGRAM_CHANGE, tool.category)
        assertEquals(ActionCategory.PROGRAM_READ, McpTool("x", "X", info.copy(readOnly = true)) { _, _ -> error("no") }.category)
    }

    @Test
    fun `resultado con texto, imagen y recorte`() {
        val r = McpClient.parseCallResult(
            HttpJson.json.parseToJsonElement(
                """{"content":[{"type":"text","text":"${"x".repeat(5000)}"},{"type":"image","data":"AAAA","mimeType":"image/png"}],"isError":true}""",
            ).jsonObject,
        )
        assertTrue(r.isError)
        assertTrue(r.text.endsWith("[…recortado]"))
        assertEquals("AAAA", r.images.single().base64)
    }

    @Test
    fun `HTTP con sesion, respuestas JSON y SSE`() = runTest {
        val web = MockWebServer()
        web.enqueue(MockResponse().setHeader("Content-Type", "application/json").setHeader("Mcp-Session-Id", "ses-1")
            .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","serverInfo":{"name":"figma","version":"1"}}}"""))
        web.enqueue(MockResponse().setResponseCode(202))
        web.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
                "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"get_code\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n",
        ))
        web.start()
        val client = McpClient(HttpTransport(web.url("/mcp").toString(), mapOf("Authorization" to "Bearer t")))
        assertEquals("figma", client.connect().name)
        assertEquals("get_code", client.listTools().single().name)
        web.takeRequest()
        val notification = web.takeRequest()
        assertEquals("ses-1", notification.getHeader("Mcp-Session-Id"))
        val list = web.takeRequest()
        assertEquals("ses-1", list.getHeader("Mcp-Session-Id"))
        assertEquals("2025-06-18", list.getHeader("MCP-Protocol-Version"))
        assertEquals("Bearer t", list.getHeader("Authorization"))
        web.shutdown()
    }

    @Test
    fun `servidor real por stdio`() = runBlocking {
        val python = listOf("python3", "python").firstOrNull { cmd ->
            runCatching { ProcessBuilder(cmd, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return@runBlocking // sin Python no se puede probar el proceso real
        val dir = Files.createTempDirectory("mcp").toFile()
        val script = File(dir, "server.py").apply {
            writeText(
                """
                import json, sys
                print("aviso que no es JSON", flush=True)
                for line in sys.stdin:
                    msg = json.loads(line)
                    if "id" not in msg:
                        continue
                    m = msg["method"]
                    if m == "initialize":
                        r = {"protocolVersion": "2025-06-18", "serverInfo": {"name": "eco", "version": "1"}, "capabilities": {}}
                    elif m == "tools/list":
                        r = {"tools": [{"name": "eco", "description": "repite", "inputSchema": {"type": "object", "properties": {"texto": {"type": "string"}}}}]}
                    elif m == "tools/call":
                        r = {"content": [{"type": "text", "text": "eco: " + msg["params"]["arguments"]["texto"]}]}
                    else:
                        r = {}
                    print(json.dumps({"jsonrpc": "2.0", "id": msg["id"], "result": r}), flush=True)
                """.trimIndent(),
            )
        }
        val client = McpClient(StdioTransport(listOf(python, "-I", script.absolutePath), env = mapOf("PYTHONIOENCODING" to "utf-8")))
        try {
            assertEquals("eco", client.connect(timeoutMs = 20_000).name)
            assertEquals("eco", client.listTools().single().name)
            val args = buildJsonObject { put("texto", "hola, ñandú") }
            assertEquals("eco: hola, ñandú", client.callTool("eco", args).text)
        } finally {
            client.close()
        }
    }
}
