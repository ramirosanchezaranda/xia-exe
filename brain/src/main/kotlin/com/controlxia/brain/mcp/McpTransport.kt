package com.controlxia.brain.mcp

import com.controlxia.brain.llm.HttpJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Por dónde viajan los mensajes JSON-RPC de MCP. El cliente no sabe si del
 * otro lado hay un proceso local (stdio) o un servidor HTTP.
 */
interface McpTransport : AutoCloseable {
    /** Arranca y entrega cada mensaje que llega a [onMessage]. */
    suspend fun start(onMessage: (JsonObject) -> Unit)

    suspend fun send(message: JsonObject)

    /** Últimas líneas de error del servidor, para diagnosticar. */
    fun diagnostics(): String = ""
}

class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Servidor MCP local: un proceso que habla JSON por stdin/stdout, una línea
 * por mensaje (así arrancan `npx …` y `uvx …`). Lo que escribe en stderr se
 * guarda para mostrar el error si no arranca.
 */
class StdioTransport(
    private val commandLine: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val workingDir: File? = null,
) : McpTransport {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stderr = ArrayDeque<String>()

    override suspend fun start(onMessage: (JsonObject) -> Unit) = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(commandLine).redirectErrorStream(false)
        workingDir?.let { builder.directory(it) }
        builder.environment().putAll(env)
        val p = try {
            builder.start()
        } catch (e: Exception) {
            throw McpException("No se pudo ejecutar ${commandLine.firstOrNull()}: ${e.message}", e)
        }
        process = p
        writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
        scope.launch {
            p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val text = line.trim()
                    if (!text.startsWith("{")) continue // algunos servidores imprimen avisos por stdout
                    val obj = runCatching { HttpJson.json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: continue
                    runCatching { onMessage(obj) }
                }
            }
        }
        scope.launch {
            p.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) synchronized(stderr) {
                    stderr.addLast(line.take(300))
                    while (stderr.size > 20) stderr.removeFirst()
                }
            }
        }
        Unit
    }

    override suspend fun send(message: JsonObject) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val w = writer ?: throw McpException("El servidor no está iniciado")
            if (process?.isAlive != true) throw McpException("El servidor se cerró. ${diagnostics()}".trim())
            w.write(message.toString())
            w.write("\n")
            w.flush()
        }
    }

    val isAlive: Boolean get() = process?.isAlive == true

    override fun diagnostics(): String = synchronized(stderr) { stderr.takeLast(6).joinToString("\n") }

    override fun close() {
        runCatching { writer?.close() }
        process?.let { p ->
            p.descendants().forEach { runCatching { it.destroy() } }
            p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        scope.cancel()
    }
}

/**
 * Servidor MCP por HTTP ("Streamable HTTP"): cada mensaje es un POST y la
 * respuesta llega como JSON o como eventos SSE. Sirve para servidores que ya
 * corren (Figma de escritorio) o remotos con token.
 */
class HttpTransport(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = defaultClient,
) : McpTransport {

    private var onMessage: (JsonObject) -> Unit = {}
    @Volatile private var sessionId: String? = null
    @Volatile var protocolVersion: String? = null

    override suspend fun start(onMessage: (JsonObject) -> Unit) {
        this.onMessage = onMessage
    }

    override suspend fun send(message: JsonObject) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
                sessionId?.let { header("Mcp-Session-Id", it) }
                protocolVersion?.let { header("MCP-Protocol-Version", it) }
            }
            .post(message.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                response.header("Mcp-Session-Id")?.let { sessionId = it }
                if (response.code == 202) return@use
                val body = response.body ?: return@use
                if (!response.isSuccessful) {
                    throw McpException("HTTP ${response.code}: ${body.string().take(300)}")
                }
                val type = response.header("Content-Type").orEmpty()
                if (type.contains("text/event-stream")) {
                    readEvents(body.charStream().buffered()) { deliver(it) }
                } else {
                    deliver(body.string())
                }
            }
        } catch (e: McpException) {
            throw e
        } catch (e: Exception) {
            throw McpException("No se pudo conectar con $url: ${e.message}", e)
        }
    }

    private fun deliver(text: String) {
        if (text.isBlank()) return
        when (val el = runCatching { HttpJson.json.parseToJsonElement(text) }.getOrNull()) {
            is JsonObject -> onMessage(el)
            is JsonArray -> el.filterIsInstance<JsonObject>().forEach(onMessage)
            else -> Unit
        }
    }

    override fun close() {
        // Se avisa al servidor que la sesión terminó, si la había.
        val id = sessionId ?: return
        runCatching {
            client.newCall(Request.Builder().url(url).header("Mcp-Session-Id", id).delete().build()).execute().close()
        }
    }

    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        /** Lee eventos SSE: junta las líneas "data:" hasta una línea vacía. */
        internal fun readEvents(reader: java.io.BufferedReader, onData: (String) -> Unit) {
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) onData(data.toString())
                        data.clear()
                    }
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trimStart())
                    }
                }
            }
            if (data.isNotEmpty()) onData(data.toString())
        }
    }
}
