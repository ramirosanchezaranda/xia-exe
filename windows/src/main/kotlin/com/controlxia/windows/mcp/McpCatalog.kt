package com.controlxia.windows.mcp

/** Con qué se ejecuta un servidor MCP local. */
enum class McpRuntime(val displayName: String, val executable: String, val wingetId: String, val downloadUrl: String) {
    NODE("Node.js", "npx", "OpenJS.NodeJS.LTS", "https://nodejs.org"),
    UV("uv (Python)", "uvx", "astral-sh.uv", "https://docs.astral.sh/uv/"),
    NONE("—", "", "", ""),
}

/** Un dato que el servidor necesita (por ejemplo, un token). */
data class EnvField(val name: String, val label: String, val secret: Boolean = true, val help: String = "")

/**
 * Integración conocida: un servidor MCP probado para un programa. Los
 * comandos salen de la documentación oficial de cada proyecto.
 */
data class CatalogEntry(
    val id: String,
    val name: String,
    /** Qué se puede pedir por voz, en una frase. */
    val description: String,
    val runtime: McpRuntime,
    /** Comando (vacío si es por [url]). {DOCUMENTS}, {DESKTOP}, {DOWNLOADS} se reemplazan por las carpetas del usuario. */
    val command: List<String> = emptyList(),
    val url: String = "",
    /** Nombres en el menú Inicio que indican que el programa está instalado. Vacío = sirve siempre. */
    val detectApps: List<String> = emptyList(),
    /** Palabras del pedido que activan sus herramientas (sin tildes). */
    val keywords: List<String>,
    val env: List<EnvField> = emptyList(),
    /** Paso manual que hay que hacer en el programa. */
    val setupNote: String = "",
    /** Puede hacer cosas delicadas (comandos, archivos): se marca como avanzada. */
    val advanced: Boolean = false,
)

object McpCatalog {

    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "excel", name = "Excel",
            description = "Crear y editar planillas: escribir datos, fórmulas, gráficos y tablas dinámicas.",
            runtime = McpRuntime.UV, command = listOf("uvx", "excel-mcp-server", "stdio"),
            detectApps = listOf("Excel"),
            keywords = listOf("excel", "planilla", "hoja de calculo", "xlsx", "celda", "grafico"),
        ),
        CatalogEntry(
            id = "word", name = "Word",
            description = "Crear y editar documentos de Word: texto, títulos, tablas, formato y PDF.",
            runtime = McpRuntime.UV, command = listOf("uvx", "--from", "office-word-mcp-server", "word_mcp_server"),
            detectApps = listOf("Word"),
            keywords = listOf("word", "documento", "docx", "carta", "informe"),
        ),
        CatalogEntry(
            id = "powerpoint", name = "PowerPoint",
            description = "Armar presentaciones: diapositivas, títulos, imágenes y tablas.",
            runtime = McpRuntime.UV, command = listOf("uvx", "--from", "office-powerpoint-mcp-server", "ppt_mcp_server"),
            detectApps = listOf("PowerPoint"),
            keywords = listOf("powerpoint", "presentacion", "diapositiva", "pptx", "slides"),
        ),
        CatalogEntry(
            id = "browser", name = "Navegador (Playwright)",
            description = "Usar páginas web: hacer clic, completar formularios, leer y navegar.",
            runtime = McpRuntime.NODE, command = listOf("npx", "-y", "@playwright/mcp@latest"),
            keywords = listOf("navegador", "pagina", "sitio", "formulario", "web", "chrome", "edge", "firefox", "login", "iniciar sesion"),
        ),
        CatalogEntry(
            id = "chrome", name = "Chrome (DevTools)",
            description = "Manejar e inspeccionar Google Chrome: abrir páginas, clics, capturas y consola.",
            runtime = McpRuntime.NODE, command = listOf("npx", "-y", "chrome-devtools-mcp@latest"),
            detectApps = listOf("Google Chrome", "Chrome"),
            keywords = listOf("chrome", "pestana", "devtools", "consola del navegador"),
        ),
        CatalogEntry(
            id = "files", name = "Archivos",
            description = "Buscar, leer, crear, mover y renombrar archivos en Documentos, Escritorio y Descargas.",
            runtime = McpRuntime.NODE,
            command = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "{DOCUMENTS}", "{DESKTOP}", "{DOWNLOADS}"),
            keywords = listOf("archivo", "archivos", "renombra", "mueve", "copia el", "busca el archivo", "lee el archivo", "txt", "pdf"),
        ),
        CatalogEntry(
            id = "memory", name = "Memoria",
            description = "Que el asistente se acuerde de datos tuyos entre sesiones (\"acordate que…\").",
            runtime = McpRuntime.NODE, command = listOf("npx", "-y", "@modelcontextprotocol/server-memory"),
            keywords = listOf("acordate", "recorda", "recordas", "te acordas", "memoria", "olvidate"),
        ),
        CatalogEntry(
            id = "fetch", name = "Leer páginas web",
            description = "Leer el contenido de una página para resumirla o responder sobre ella.",
            runtime = McpRuntime.UV, command = listOf("uvx", "mcp-server-fetch"),
            keywords = listOf("resumi", "resumime", "lee la pagina", "leeme", "que dice", "url", "link", "noticia"),
        ),
        CatalogEntry(
            id = "windows", name = "Control avanzado de Windows",
            description = "Manejar cualquier programa: ver la pantalla, tocar botones, escribir y usar atajos (Windows-MCP).",
            runtime = McpRuntime.UV, command = listOf("uvx", "windows-mcp"),
            keywords = listOf("boton", "menu", "pestana", "ventana", "programa", "tilda", "marca", "desmarca"),
            advanced = true,
        ),
        CatalogEntry(
            id = "terminal", name = "Terminal y procesos",
            description = "Ejecutar comandos, ver procesos y editar archivos de texto (Desktop Commander).",
            runtime = McpRuntime.NODE, command = listOf("npx", "-y", "@wonderwhy-er/desktop-commander"),
            keywords = listOf("terminal", "comando", "consola", "powershell", "proceso", "procesos", "script"),
            advanced = true,
        ),
        CatalogEntry(
            id = "blender", name = "Blender",
            description = "Modelar en 3D: crear y mover objetos, materiales y escenas.",
            runtime = McpRuntime.UV, command = listOf("uvx", "blender-mcp"),
            detectApps = listOf("Blender"),
            keywords = listOf("blender", "3d", "modelo", "escena", "objeto", "render"),
            setupNote = "En Blender instalá y activá el complemento de blender-mcp (addon.py del proyecto ahujasid/blender-mcp) y tocá «Connect to MCP server» en la barra lateral.",
        ),
        CatalogEntry(
            id = "obsidian", name = "Obsidian",
            description = "Buscar, leer y escribir notas de tu bóveda.",
            runtime = McpRuntime.UV, command = listOf("uvx", "mcp-obsidian"),
            detectApps = listOf("Obsidian"),
            keywords = listOf("obsidian", "nota", "notas", "boveda"),
            env = listOf(EnvField("OBSIDIAN_API_KEY", "API key de Local REST API", help = "Instalá el complemento «Local REST API» en Obsidian y copiá su clave.")),
            setupNote = "En Obsidian instalá y activá el complemento comunitario «Local REST API».",
        ),
        CatalogEntry(
            id = "notion", name = "Notion",
            description = "Buscar páginas, crear notas y editar bases de datos de Notion.",
            runtime = McpRuntime.NODE, command = listOf("npx", "-y", "@notionhq/notion-mcp-server"),
            detectApps = listOf("Notion"),
            keywords = listOf("notion", "pagina de notion", "base de datos"),
            env = listOf(EnvField("NOTION_TOKEN", "Token de integración de Notion", help = "Crealo en notion.so/my-integrations y compartí las páginas con la integración.")),
        ),
        CatalogEntry(
            id = "figma", name = "Figma",
            description = "Leer diseños de Figma: componentes, estilos y código del diseño seleccionado.",
            runtime = McpRuntime.NONE, url = "http://127.0.0.1:3845/mcp",
            detectApps = listOf("Figma"),
            keywords = listOf("figma", "diseno", "frame", "componente"),
            setupNote = "En Figma de escritorio, en el modo Dev, activá «Enable desktop MCP server».",
        ),
    )

    fun byId(id: String): CatalogEntry? = entries.firstOrNull { it.id == id }
}
