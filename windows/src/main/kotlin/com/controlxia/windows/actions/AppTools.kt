package com.controlxia.windows.actions

import com.controlxia.brain.llm.HttpJson
import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.bool
import com.controlxia.brain.tools.schema
import com.controlxia.brain.tools.str
import com.controlxia.brain.tools.tool
import com.controlxia.windows.platform.PowerShellHost
import com.controlxia.windows.platform.psQuote
import com.controlxia.windows.voice.normalizeSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class AppEntry(val name: String, val appId: String)
data class OpenWindow(val pid: Long, val processName: String, val title: String)

/**
 * RF-11: abrir y cerrar programas por nombre. El índice sale de
 * Get-StartApps, que incluye tanto programas clásicos como apps de la Store
 * (Spotify, WhatsApp…), y se abren con shell:AppsFolder igual que desde Inicio.
 */
class AppTools(private val shell: PowerShellHost) {

    @Volatile private var cache: Pair<Long, List<AppEntry>>? = null

    fun all(): List<Tool> = listOf(
        tool(
            "open_app",
            "Abre un programa instalado por su nombre, ej: Spotify, Chrome, Bloc de notas, Calculadora, Word.",
            ActionCategory.OPEN_APPS,
            schema { string("app_name", "Nombre del programa tal como lo dijo el usuario", required = true) },
            progress = { "Abriendo ${it.str("app_name")}…" },
        ) { args -> open(args.str("app_name")) },
        tool(
            "close_app",
            "Cierra un programa abierto (le pide que se cierre, como la X de la ventana).",
            ActionCategory.CLOSE_APPS,
            schema {
                string("app_name", "Nombre del programa o de la ventana", required = true)
                boolean("force", "Forzar el cierre si no responde (puede perder cambios sin guardar)")
            },
            confirm = { "¿Cierro ${it.str("app_name")}?" },
            progress = { "Cerrando ${it.str("app_name")}…" },
        ) { args -> close(args.str("app_name"), args.bool("force")) },
        tool(
            "list_open_windows",
            "Lista las ventanas abiertas (programa y título). Útil antes de cerrar o cambiar de ventana.",
            ActionCategory.INFO,
        ) { ToolResult(openWindows().joinToString("\n") { "${it.processName}: ${it.title}" }.ifBlank { "No hay ventanas abiertas" }) },
    )

    suspend fun open(name: String): ToolResult {
        if (name.isBlank()) return ToolResult("Falta el nombre del programa", isError = true)
        val entry = bestMatch(name, apps()) { it.name }
        if (entry != null) {
            withContext(Dispatchers.IO) {
                ProcessBuilder("explorer.exe", "shell:AppsFolder\\${entry.appId}").start()
            }
            return ToolResult("Abriendo ${entry.name}")
        }
        // Último intento: ejecutables conocidos por Windows (notepad, calc, chrome…).
        return runCatching {
            shell.run("Start-Process ${psQuote(name)}")
            ToolResult("Abriendo $name")
        }.getOrElse { ToolResult("No encontré un programa llamado $name", isError = true) }
    }

    suspend fun close(name: String, force: Boolean): ToolResult {
        val window = bestMatch(name, openWindows()) { "${it.processName} ${it.title}" }
            ?: bestMatch(name, openWindows()) { it.title }
            ?: return ToolResult("No encontré una ventana abierta de $name", isError = true)
        val script = if (force) "Stop-Process -Id ${window.pid} -Force"
        else "[void](Get-Process -Id ${window.pid}).CloseMainWindow()"
        shell.run(script)
        return ToolResult("Cerrando ${window.title.ifBlank { window.processName }}")
    }

    suspend fun apps(): List<AppEntry> {
        cache?.let { (at, list) -> if (System.currentTimeMillis() - at < CACHE_MS && list.isNotEmpty()) return list }
        val out = shell.run("Get-StartApps | Select-Object Name, AppID | ConvertTo-Json -Compress", timeoutMs = 30_000)
        val list = parseObjects(out).mapNotNull { o ->
            val n = o.text("Name"); val id = o.text("AppID")
            if (n.isBlank() || id.isBlank()) null else AppEntry(n, id)
        }
        cache = System.currentTimeMillis() to list
        return list
    }

    /** Precarga el índice en segundo plano para que el primer "abrí…" sea rápido. */
    suspend fun warmUp() { runCatching { apps() } }

    suspend fun openWindows(): List<OpenWindow> {
        val out = shell.run(
            "Get-Process | Where-Object { \$_.MainWindowTitle } | " +
                "Select-Object Id, ProcessName, MainWindowTitle | ConvertTo-Json -Compress",
        )
        return parseObjects(out).map { o ->
            OpenWindow(o.text("Id").toLongOrNull() ?: 0, o.text("ProcessName"), o.text("MainWindowTitle"))
        }
    }

    companion object {
        private const val CACHE_MS = 10 * 60 * 1000L

        /** ConvertTo-Json devuelve un objeto suelto si hay un solo resultado. */
        internal fun parseObjects(json: String): List<JsonObject> {
            if (json.isBlank()) return emptyList()
            return when (val el: JsonElement = HttpJson.json.parseToJsonElement(json)) {
                is JsonArray -> el.filterIsInstance<JsonObject>()
                is JsonObject -> listOf(el)
                else -> emptyList()
            }
        }

        private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

        /**
         * Busca la entrada que mejor coincide con lo dicho: exacta, empieza
         * igual, contiene la palabra o se parece (tolera errores de la
         * transcripción). Devuelve null si nada se parece lo suficiente.
         */
        fun <T> bestMatch(query: String, items: List<T>, label: (T) -> String): T? {
            val q = query.normalizeSpeech()
            if (q.isEmpty()) return null
            return items
                .map { it to score(q, label(it).normalizeSpeech()) }
                .filter { it.second >= 50 }
                .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { label(it.first).length })
                .firstOrNull()?.first
        }

        internal fun score(q: String, name: String): Int {
            if (name.isEmpty()) return 0
            if (name == q) return 100
            if (name.startsWith("$q ") || name.startsWith(q)) return 90
            val words = name.split(' ')
            if (q in words || words.windowed(q.split(' ').size).any { it.joinToString(" ") == q }) return 80
            if (name.contains(q)) return 70
            val best = (words + name).maxOf { similarity(q, it) }
            return (best * 70).toInt()
        }

        /** 1 - distancia de Levenshtein normalizada. */
        internal fun similarity(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return 1.0 - dp[b.length].toDouble() / maxOf(a.length, b.length)
        }
    }
}
