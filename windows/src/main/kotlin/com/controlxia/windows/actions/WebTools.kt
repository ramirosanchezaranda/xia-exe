package com.controlxia.windows.actions

import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.schema
import com.controlxia.brain.tools.str
import com.controlxia.brain.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder

/** RF-12: abrir páginas y buscar en internet con el navegador predeterminado. */
object WebTools {

    fun all(): List<Tool> = listOf(
        tool(
            "open_url",
            "Abre una página web en el navegador, ej: youtube.com, mercadolibre.com.ar.",
            ActionCategory.WEB,
            schema { string("url", "Dirección de la página", required = true) },
            progress = { "Abriendo ${it.str("url")}…" },
        ) { args ->
            val url = normalizeUrl(args.str("url")) ?: return@tool ToolResult("Dirección inválida", isError = true)
            browse(url)
            ToolResult("Abriendo ${URI(url).host ?: url}")
        },
        tool(
            "web_search",
            "Busca en internet y abre los resultados en el navegador.",
            ActionCategory.WEB,
            schema {
                string("query", "Qué buscar", required = true)
                string("site", "Dónde buscar", enum = listOf("google", "youtube", "wikipedia", "maps"))
            },
            progress = { "Buscando ${it.str("query")}…" },
        ) { args ->
            val query = args.str("query")
            browse(searchUrl(query, args.str("site")))
            ToolResult("Buscando $query")
        },
    )

    fun normalizeUrl(raw: String): String? {
        val t = raw.trim().replace(" ", "")
        if (t.isEmpty() || t.startsWith("file:") || t.startsWith("javascript:")) return null
        val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        return runCatching { URI(withScheme).toString() }.getOrNull()
    }

    fun searchUrl(query: String, site: String): String {
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        return when (site) {
            "youtube" -> "https://www.youtube.com/results?search_query=$q"
            "wikipedia" -> "https://es.wikipedia.org/w/index.php?search=$q"
            "maps" -> "https://www.google.com/maps/search/$q"
            else -> "https://www.google.com/search?q=$q"
        }
    }

    private suspend fun browse(url: String) = withContext(Dispatchers.IO) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        } else {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
        }
    }
}
