package com.controlxia.brain.mcp

import com.controlxia.brain.llm.ImageData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Una herramienta que ofrece un servidor MCP. */
data class McpToolInfo(
    val name: String,
    val title: String?,
    val description: String,
    val inputSchema: JsonObject,
    /** El servidor dice que solo lee (no cambia nada). */
    val readOnly: Boolean,
    /** El servidor dice que puede borrar o pisar cosas. */
    val destructive: Boolean,
)

data class McpCallResult(val text: String, val images: List<ImageData>, val isError: Boolean)

data class McpServerInfo(val name: String, val version: String, val protocolVersion: String)

/**
 * Cliente del Model Context Protocol: se presenta al servidor, lista sus
 * herramientas y las llama. https://modelcontextprotocol.io/specification
 */
class McpClient(
    private val transport: McpTransport,
    private val requestTimeoutMs: Long = 60_000,
    private val clientName: String = "ControlXIA",
    private val clientVersion: String = "0.2.0",
) : AutoCloseable {

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var serverInfo: McpServerInfo? = null
        private set

    /** Arranca el transporte y hace el saludo inicial (puede tardar si `npx` descarga el paquete). */
    suspend fun connect(timeoutMs: Long = 180_000): McpServerInfo {
        transport.start(::onMessage)
        val result = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { put("roots", buildJsonObject { put("listChanged", false) }) })
                put("clientInfo", buildJsonObject {
                    put("name", clientName)
                    put("version", clientVersion)
                })
            },
            timeoutMs,
        )
        val server = result["serverInfo"] as? JsonObject
        val info = McpServerInfo(
            name = server.str("name").ifBlank { "servidor" },
            version = server.str("version"),
            protocolVersion = result.str("protocolVersion").ifBlank { PROTOCOL_VERSION },
        )
        (transport as? HttpTransport)?.protocolVersion = info.protocolVersion
        notify("notifications/initialized")
        serverInfo = info
        return info
    }

    suspend fun listTools(): List<McpToolInfo> {
        val tools = mutableListOf<McpToolInfo>()
        var cursor: String? = null
        do {
            val result = request("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            (result["tools"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { t ->
                val annotations = t["annotations"] as? JsonObject
                tools += McpToolInfo(
                    name = t.str("name"),
                    title = t.str("title").ifBlank { annotations.str("title") }.ifBlank { null },
                    description = t.str("description"),
                    inputSchema = t["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") },
                    readOnly = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true,
                    destructive = (annotations?.get("destructiveHint") as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
            cursor = result.str("nextCursor").ifBlank { null }
        } while (cursor != null && tools.size < MAX_TOOLS)
        return tools.filter { it.name.isNotBlank() }
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        val result = request("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
        return parseCallResult(result)
    }

    private suspend fun request(method: String, params: JsonObject, timeoutMs: Long = requestTimeoutMs): JsonObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            transport.send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            })
            val response = try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw McpException("El servidor no respondió a $method a tiempo. ${transport.diagnostics()}".trim(), e)
            }
            (response["error"] as? JsonObject)?.let { error ->
                throw McpException("Error del servidor: ${error.str("message").ifBlank { error.toString() }}")
            }
            return response["result"] as? JsonObject ?: JsonObject(emptyMap())
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun notify(method: String) {
        transport.send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        })
    }

    private fun onMessage(message: JsonObject) {
        val id = (message["id"] as? JsonPrimitive)?.content?.toLongOrNull()
        val method = message.str("method")
        when {
            // Respuesta a un pedido nuestro.
            method.isEmpty() && id != null -> pending[id]?.complete(message)
            // Pedido del servidor: se contesta lo básico para que no se quede esperando.
            method.isNotEmpty() && message["id"] != null && message["id"] !is JsonNull -> scope.launch {
                runCatching { transport.send(answerServerRequest(message["id"]!!, method)) }
            }
            // Notificaciones (progreso, logs, cambios de lista): no hacen falta.
            else -> Unit
        }
    }

    private fun answerServerRequest(id: JsonElement, method: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        when (method) {
            "ping" -> put("result", buildJsonObject { })
            "roots/list" -> put("result", buildJsonObject { put("roots", buildJsonArray { }) })
            else -> put("error", buildJsonObject {
                put("code", -32601)
                put("message", "ControlXIA no soporta $method")
            })
        }
    }

    override fun close() {
        pending.values.forEach { it.cancel() }
        scope.cancel()
        runCatching { transport.close() }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private const val MAX_TOOLS = 200
        private const val MAX_TEXT = 4_000

        /** Arma el resultado legible: textos juntos, imágenes aparte, recortado para no gastar tokens. */
        fun parseCallResult(result: JsonObject): McpCallResult {
            val text = StringBuilder()
            val images = mutableListOf<ImageData>()
            (result["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { block ->
                when (block.str("type")) {
                    "text" -> text.appendLine(block.str("text"))
                    "image" -> {
                        val data = block.str("data")
                        if (data.isNotBlank()) images += ImageData(data, block.str("mimeType").ifBlank { "image/png" })
                    }
                    "resource" -> (block["resource"] as? JsonObject)?.let { r ->
                        text.appendLine(r.str("text").ifBlank { r.str("uri") })
                    }
                    "resource_link" -> text.appendLine("${block.str("name")} ${block.str("uri")}".trim())
                }
            }
            if (text.isBlank()) (result["structuredContent"] as? JsonObject)?.let { text.append(it.toString()) }
            val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
            val clean = text.toString().trim().let { if (it.length > MAX_TEXT) it.take(MAX_TEXT) + " […recortado]" else it }
            return McpCallResult(clean.ifBlank { if (isError) "Error sin detalle" else "Listo" }, images.take(2), isError)
        }
    }
}

internal fun JsonObject?.str(key: String): String =
    (this?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()
