package com.controlxia.windows.mcp

import com.controlxia.brain.mcp.HttpTransport
import com.controlxia.brain.mcp.McpCallResult
import com.controlxia.brain.mcp.McpClient
import com.controlxia.brain.mcp.McpTool
import com.controlxia.brain.mcp.McpToolInfo
import com.controlxia.brain.mcp.StdioTransport
import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolGroup
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.normalizeText
import com.controlxia.brain.tools.schema
import com.controlxia.brain.tools.str
import com.controlxia.brain.tools.tool
import com.controlxia.windows.assistant.Log
import com.controlxia.windows.config.SecretStore
import com.controlxia.windows.config.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Estado de un servidor, para la configuración. */
sealed interface McpStatus {
    data object Off : McpStatus
    data class Starting(val message: String) : McpStatus
    data class Ready(val tools: Int) : McpStatus
    data class Failed(val message: String) : McpStatus
}

/**
 * Arranca, mantiene y apaga los servidores MCP configurados, y los ofrece al
 * cerebro como grupos de herramientas (uno por programa).
 */
class McpManager(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
    /** Nombres de los programas instalados (menú Inicio), para detectar integraciones. */
    private val installedApps: suspend () -> List<String> = { emptyList() },
) {
    private class Connection(val config: McpServerConfig, val client: McpClient, val transport: AutoCloseable, val tools: List<McpToolInfo>)

    private val connections = ConcurrentHashMap<String, Connection>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _statuses = MutableStateFlow<Map<String, McpStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, McpStatus>> = _statuses.asStateFlow()

    fun start() {
        scope.launch {
            settings.state.map { it.mcpServers }.distinctUntilChanged().collect { sync(it) }
        }
    }

    /** Conecta los habilitados, apaga los que se sacaron o deshabilitaron. */
    private suspend fun sync(configs: List<McpServerConfig>) {
        val wanted = configs.filter { it.enabled }.associateBy { it.id }
        connections.keys.filter { id -> wanted[id] != connections[id]?.config }.forEach { id ->
            lock(id).withLock { disconnectNow(id) }
        }
        configs.filter { !it.enabled }.forEach { setStatus(it.id, McpStatus.Off) }
        // De a uno: arrancar varios `npx` a la vez satura la PC.
        for (config in wanted.values) {
            if (connections[config.id]?.config != config) runCatching { connect(config) }
        }
    }

    /** Arranca el servidor y lista sus herramientas. Devuelve cuántas tiene. */
    suspend fun connect(config: McpServerConfig): Int = lock(config.id).withLock {
        connections[config.id]?.takeIf { it.config == config }?.let { return it.tools.size }
        disconnectNow(config.id)
        missingSecret(config)?.let { name ->
            setStatus(config.id, McpStatus.Failed("Falta $name"))
            throw IllegalStateException("Falta $name: cargalo en Configuración › Programas")
        }
        setStatus(config.id, McpStatus.Starting(if (config.isHttp) "Conectando…" else "Iniciando (la primera vez descarga el paquete)…"))
        val transport = if (config.isHttp) {
            val token = if (config.usesToken) secrets.get(McpServerConfig.secretKey(config.id, TOKEN)) else ""
            HttpTransport(config.url, if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token"))
        } else {
            val path = McpRuntimes.freshPath()
            StdioTransport(
                commandLine = McpRuntimes.commandLine(McpRuntimes.expandArgs(config.command), path),
                env = buildMap {
                    put("PATH", path)
                    // Python en Windows escribe en cp1252 si no se le pide UTF-8.
                    put("PYTHONIOENCODING", "utf-8")
                    put("PYTHONUTF8", "1")
                    putAll(config.env)
                    config.secretEnv.forEach { name -> put(name, secrets.get(McpServerConfig.secretKey(config.id, name))) }
                },
                workingDir = File(System.getProperty("user.home")),
            )
        }
        val client = McpClient(transport)
        try {
            client.connect(timeoutMs = FIRST_START_TIMEOUT_MS)
            val tools = client.listTools()
            connections[config.id] = Connection(config, client, transport, tools)
            setStatus(config.id, McpStatus.Ready(tools.size))
            Log.i("MCP ${config.name}: ${tools.size} herramientas")
            tools.size
        } catch (e: Exception) {
            client.close()
            setStatus(config.id, McpStatus.Failed((e.message ?: e.toString()).take(300)))
            Log.e("MCP ${config.name}", e)
            throw e
        }
    }

    private fun missingSecret(config: McpServerConfig): String? =
        config.secretEnv.firstOrNull { secrets.get(McpServerConfig.secretKey(config.id, it)).isBlank() }

    private fun disconnectNow(id: String) {
        connections.remove(id)?.client?.close()
        setStatus(id, McpStatus.Off)
    }

    fun stopAll() {
        connections.keys.toList().forEach { disconnectNow(it) }
    }

    /** Llama una herramienta; si el servidor se cayó, lo reinicia una vez. */
    private suspend fun call(serverId: String, tool: String, args: JsonObject): McpCallResult {
        val connection = connections[serverId] ?: throw IllegalStateException("El servidor no está conectado")
        val live = if ((connection.transport as? StdioTransport)?.isAlive == false) {
            // Se saca la conexión muerta primero: si no, connect() la daría por buena.
            lock(serverId).withLock { disconnectNow(serverId) }
            connect(connection.config)
            connections[serverId] ?: throw IllegalStateException("No se pudo reiniciar el servidor")
        } else {
            connection
        }
        return live.client.callTool(tool, args)
    }

    /** Un grupo de herramientas por servidor conectado. */
    fun toolGroups(): List<ToolGroup> = connections.values.map { c ->
        val keywords = (c.config.keywords + c.config.name.split(' ')).map { normalizeText(it) }.filter { it.length > 2 }
        ToolGroup(
            id = "mcp_${c.config.id}",
            description = c.config.name + (c.config.description.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
            keywords = keywords.distinct(),
            tools = c.tools.map { info -> McpTool(c.config.id, c.config.name, info) { name, args -> call(c.config.id, name, args) } },
        )
    }

    // ----------------------------------------------------------- Catálogo

    /** Programas del catálogo que están instalados en la PC (o que sirven siempre). */
    suspend fun detected(): Set<String> {
        val apps = runCatching { installedApps() }.getOrDefault(emptyList()).map { normalizeText(it) }
        return McpCatalog.entries.filter { e ->
            e.detectApps.isEmpty() || e.detectApps.any { d ->
                val n = normalizeText(d)
                apps.any { it == n || it.startsWith(n) || it.contains(" $n") }
            }
        }.map { it.id }.toSet()
    }

    /**
     * Instala una integración del catálogo: el runtime (Node.js o uv) si
     * falta, la agrega y la conecta. Devuelve una frase con el resultado.
     */
    suspend fun install(entry: CatalogEntry, onStatus: (String) -> Unit = {}): String {
        McpRuntimes.install(entry.runtime, onStatus)?.let { return it }
        val config = settings.current.mcpServers.firstOrNull { it.id == entry.id }?.copy(enabled = true)
            ?: McpServerConfig.fromCatalog(entry)
        settings.update { s -> s.copy(mcpServers = s.mcpServers.filterNot { it.id == config.id } + config) }
        missingSecret(config)?.let { name ->
            val field = entry.env.firstOrNull { it.name == name }
            return "Agregué ${entry.name}, pero falta ${field?.label ?: name}. Cargalo en Configuración › Programas." +
                (field?.help?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
        }
        onStatus("Conectando ${entry.name}…")
        return try {
            val count = connect(config)
            "Listo, ${entry.name} quedó conectado con $count herramientas." +
                (entry.setupNote.takeIf { it.isNotBlank() }?.let { " Ojo: $it" } ?: "")
        } catch (e: Exception) {
            "Agregué ${entry.name} pero no arrancó: ${e.message?.take(200)}" +
                (entry.setupNote.takeIf { it.isNotBlank() }?.let { " Revisá: $it" } ?: "")
        }
    }

    /** Herramientas para manejar las integraciones por voz. */
    fun voiceTools(): List<Tool> = listOf(
        tool(
            "list_integrations",
            "Lista las integraciones con programas (MCP): cuáles están conectadas y cuáles se pueden instalar.",
            ActionCategory.INFO,
        ) {
            val detected = detected()
            val configured = settings.current.mcpServers.associateBy { it.id }
            val status = statuses.value
            val lines = McpCatalog.entries.filter { it.id in detected || it.id in configured }.map { e ->
                val st = when (status[e.id]) {
                    is McpStatus.Ready -> "conectada"
                    is McpStatus.Failed -> "con error"
                    is McpStatus.Starting -> "iniciando"
                    else -> if (configured[e.id]?.enabled == true) "agregada" else "disponible"
                }
                "${e.id}: ${e.name} ($st) — ${e.description}"
            } + settings.current.mcpServers.filter { it.source != McpSource.CATALOG }.map { c ->
                "${c.id}: ${c.name} (${if (status[c.id] is McpStatus.Ready) "conectada" else "agregada"})"
            }
            ToolResult(lines.joinToString("\n").ifBlank { "No hay integraciones para los programas de esta PC" })
        },
        tool(
            "install_integration",
            "Instala y conecta una integración con un programa (por ejemplo excel, word, browser, files, blender). " +
                "Usá el id que da list_integrations.",
            ActionCategory.INSTALL,
            schema { string("id", "Id de la integración", required = true, enum = McpCatalog.entries.map { it.id }) },
            confirm = { "¿Instalo la integración con ${McpCatalog.byId(it.str("id"))?.name ?: it.str("id")}? Puede descargar programas." },
            progress = { "Instalando ${McpCatalog.byId(it.str("id"))?.name ?: "la integración"}…" },
        ) { args ->
            val entry = McpCatalog.byId(args.str("id")) ?: return@tool ToolResult("No conozco esa integración", isError = true)
            ToolResult(install(entry))
        },
        tool(
            "disable_integration",
            "Desconecta una integración con un programa.",
            ActionCategory.INSTALL,
            schema { string("id", "Id de la integración", required = true) },
            confirm = { "¿Desconecto ${it.str("id")}?" },
        ) { args ->
            val id = args.str("id")
            if (settings.current.mcpServers.none { it.id == id }) return@tool ToolResult("No está agregada", isError = true)
            settings.update { s -> s.copy(mcpServers = s.mcpServers.map { if (it.id == id) it.copy(enabled = false) else it }) }
            ToolResult("Listo, desconecté $id")
        },
    )

    private fun setStatus(id: String, status: McpStatus) = _statuses.update { it + (id to status) }

    private fun lock(id: String) = locks.getOrPut(id) { Mutex() }

    companion object {
        const val TOKEN = "TOKEN"
        /** La primera vez `npx`/`uvx` descargan el paquete: puede tardar. */
        private const val FIRST_START_TIMEOUT_MS = 5 * 60_000L
    }
}
