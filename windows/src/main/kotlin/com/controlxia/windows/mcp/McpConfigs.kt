package com.controlxia.windows.mcp

import com.controlxia.brain.llm.HttpJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** De dónde salió un servidor configurado. */
@Serializable
enum class McpSource { CATALOG, IMPORTED, CUSTOM }

/**
 * Un servidor MCP configurado. Los valores secretos (tokens) no van acá:
 * se guardan en el Administrador de credenciales con la clave [secretKey].
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val command: List<String> = emptyList(),
    val url: String = "",
    /** Variables no secretas. */
    val env: Map<String, String> = emptyMap(),
    /** Nombres de variables cuyo valor está en el Administrador de credenciales. */
    val secretEnv: List<String> = emptyList(),
    /** Para servidores HTTP: token que va como "Authorization: Bearer". */
    val usesToken: Boolean = false,
    val keywords: List<String> = emptyList(),
    val description: String = "",
    val source: McpSource = McpSource.CUSTOM,
    val catalogId: String? = null,
) {
    val isHttp: Boolean get() = url.isNotBlank()

    companion object {
        fun secretKey(serverId: String, name: String) = "mcp.$serverId.$name"

        fun fromCatalog(entry: CatalogEntry) = McpServerConfig(
            id = entry.id,
            name = entry.name,
            command = entry.command,
            url = entry.url,
            secretEnv = entry.env.filter { it.secret }.map { it.name },
            keywords = entry.keywords,
            description = entry.description,
            source = McpSource.CATALOG,
            catalogId = entry.id,
        )

        /** "npx -y paquete" → lista de partes, respetando comillas. */
        fun splitCommandLine(line: String): List<String> =
            Regex("\"([^\"]*)\"|(\\S+)").findAll(line).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
    }
}

/** Un servidor leído de la configuración de otra app, con sus secretos aparte. */
data class ImportedServer(val config: McpServerConfig, val secrets: Map<String, String>)

/**
 * Importa los servidores MCP que el usuario ya tiene en Claude Desktop o en
 * Cursor: mismo formato `mcpServers`. Las variables que parecen claves se
 * separan para guardarlas como secretos.
 */
object McpImport {

    data class Source(val label: String, val file: File)

    fun sources(): List<Source> {
        val home = System.getProperty("user.home")
        return listOfNotNull(
            System.getenv("APPDATA")?.let { Source("Claude Desktop", File(it, "Claude/claude_desktop_config.json")) },
            Source("Cursor", File(home, ".cursor/mcp.json")),
        ).filter { it.file.isFile }
    }

    fun parse(json: String, sourceLabel: String): List<ImportedServer> {
        val root = runCatching { HttpJson.json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
        val servers = root["mcpServers"] as? JsonObject ?: return emptyList()
        return servers.mapNotNull { (name, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            if ((obj["disabled"] as? JsonPrimitive)?.content == "true") return@mapNotNull null
            val command = (obj["command"] as? JsonPrimitive)?.content.orEmpty()
            val args = (obj["args"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            val url = (obj["url"] as? JsonPrimitive)?.content ?: (obj["serverUrl"] as? JsonPrimitive)?.content.orEmpty()
            if (command.isBlank() && url.isBlank()) return@mapNotNull null
            val env = (obj["env"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }
            val (secret, plain) = env.entries.partition { (k, _) -> looksSecret(k) }
            val id = "imp_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
            ImportedServer(
                config = McpServerConfig(
                    id = id,
                    name = name,
                    command = if (command.isBlank()) emptyList() else listOf(command) + args,
                    url = url,
                    env = plain.associate { it.key to it.value },
                    secretEnv = secret.map { it.key },
                    keywords = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in GENERIC },
                    description = "Importado de $sourceLabel",
                    source = McpSource.IMPORTED,
                ),
                secrets = secret.associate { it.key to it.value },
            )
        }
    }

    private fun looksSecret(name: String) =
        Regex("KEY|TOKEN|SECRET|PASSWORD|PASS|AUTH|CREDENTIAL", RegexOption.IGNORE_CASE).containsMatchIn(name)

    private val GENERIC = setOf("mcp", "server", "servidor", "local", "api")
}
