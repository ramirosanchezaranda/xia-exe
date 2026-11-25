package com.controlxia.windows.actions

import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.schema
import com.controlxia.brain.tools.str
import com.controlxia.brain.tools.tool
import kotlinx.coroutines.delay
import java.awt.Robot
import java.awt.event.KeyEvent
import java.util.Locale

/**
 * Comandos de programa por su significado ("nueva pestaña", "guardá",
 * "negrita"): se traducen al atajo correcto según el programa activo y el
 * idioma de Office (en Office en castellano, Ctrl+N es negrita y Ctrl+B buscar).
 */
object Shortcuts {

    const val CTRL = KeyEvent.VK_CONTROL
    const val SHIFT = KeyEvent.VK_SHIFT
    const val ALT = KeyEvent.VK_ALT
    const val WIN = KeyEvent.VK_WINDOWS
    const val LEFT = KeyEvent.VK_LEFT
    const val RIGHT = KeyEvent.VK_RIGHT
    const val F11 = KeyEvent.VK_F11

    private val robot by lazy { Robot().apply { autoDelay = 15 } }

    /** Acciones conocidas y una descripción corta para el modelo. */
    val ACTIONS = linkedMapOf(
        "copy" to "copiar", "paste" to "pegar", "cut" to "cortar", "undo" to "deshacer", "redo" to "rehacer",
        "select_all" to "seleccionar todo", "save" to "guardar", "save_as" to "guardar como", "find" to "buscar",
        "print" to "imprimir", "new" to "nuevo", "open" to "abrir", "new_tab" to "nueva pestaña",
        "close_tab" to "cerrar pestaña", "reopen_tab" to "reabrir pestaña", "next_tab" to "pestaña siguiente",
        "previous_tab" to "pestaña anterior", "reload" to "recargar", "back" to "atrás", "forward" to "adelante",
        "address_bar" to "barra de direcciones", "zoom_in" to "acercar", "zoom_out" to "alejar", "zoom_reset" to "zoom normal",
        "bold" to "negrita", "italic" to "cursiva", "underline" to "subrayado", "enter" to "enter", "escape" to "escape",
        "delete" to "suprimir", "scroll_down" to "bajar", "scroll_up" to "subir", "page_top" to "ir arriba", "page_bottom" to "ir abajo",
        "switch_app" to "cambiar de programa (Alt+Tab)", "task_manager" to "administrador de tareas", "screenshot" to "recorte de pantalla",
    )

    private val OFFICE = setOf("winword", "excel", "powerpnt", "outlook", "onenote")

    /** Las teclas de [action] en el programa [process], o null si no se conoce. */
    fun keysFor(action: String, process: String, spanishOffice: Boolean = isSpanish()): List<Int>? {
        val es = process.lowercase() in OFFICE && spanishOffice
        fun key(c: Char) = c.uppercaseChar().code
        return when (action) {
            "copy" -> listOf(CTRL, key('c'))
            "paste" -> listOf(CTRL, key('v'))
            "cut" -> listOf(CTRL, key('x'))
            "undo" -> listOf(CTRL, key('z'))
            "redo" -> listOf(CTRL, key('y'))
            "select_all" -> listOf(CTRL, key(if (es) 'e' else 'a'))
            // Mayús+F12 guarda en Word y Excel en cualquier idioma.
            "save" -> if (process.lowercase() in OFFICE) listOf(SHIFT, KeyEvent.VK_F12) else listOf(CTRL, key('s'))
            "save_as" -> listOf(KeyEvent.VK_F12)
            "find" -> listOf(CTRL, key(if (es) 'b' else 'f'))
            "print" -> listOf(CTRL, key('p'))
            "new" -> listOf(CTRL, key(if (es) 'u' else 'n'))
            "open" -> listOf(CTRL, key(if (es) 'a' else 'o'))
            "new_tab" -> listOf(CTRL, key('t'))
            "close_tab" -> listOf(CTRL, key('w'))
            "reopen_tab" -> listOf(CTRL, SHIFT, key('t'))
            "next_tab" -> listOf(CTRL, KeyEvent.VK_TAB)
            "previous_tab" -> listOf(CTRL, SHIFT, KeyEvent.VK_TAB)
            "reload" -> listOf(KeyEvent.VK_F5)
            "back" -> listOf(ALT, LEFT)
            "forward" -> listOf(ALT, RIGHT)
            "address_bar" -> listOf(CTRL, key('l'))
            "zoom_in" -> listOf(CTRL, KeyEvent.VK_ADD)
            "zoom_out" -> listOf(CTRL, KeyEvent.VK_SUBTRACT)
            "zoom_reset" -> listOf(CTRL, key('0'))
            "bold" -> listOf(CTRL, key(if (es) 'n' else 'b'))
            "italic" -> listOf(CTRL, key(if (es) 'k' else 'i'))
            "underline" -> listOf(CTRL, key(if (es) 's' else 'u'))
            "enter" -> listOf(KeyEvent.VK_ENTER)
            "escape" -> listOf(KeyEvent.VK_ESCAPE)
            "delete" -> listOf(KeyEvent.VK_DELETE)
            "scroll_down" -> listOf(KeyEvent.VK_PAGE_DOWN)
            "scroll_up" -> listOf(KeyEvent.VK_PAGE_UP)
            "page_top" -> listOf(CTRL, KeyEvent.VK_HOME)
            "page_bottom" -> listOf(CTRL, KeyEvent.VK_END)
            "switch_app" -> listOf(ALT, KeyEvent.VK_TAB)
            "task_manager" -> listOf(CTRL, SHIFT, KeyEvent.VK_ESCAPE)
            "screenshot" -> listOf(WIN, SHIFT, key('s'))
            else -> null
        }
    }

    /** ¿Office está en castellano? Se asume el idioma de Windows. */
    fun isSpanish(): Boolean = Locale.getDefault().language == "es"

    fun press(keys: List<Int>) {
        keys.forEach { robot.keyPress(it) }
        keys.reversed().forEach { robot.keyRelease(it) }
    }

    fun tools(): List<Tool> = listOf(
        tool(
            "app_command",
            "Hace una acción común en el programa activo con su atajo correcto: " + ACTIONS.entries.joinToString(", ") { "${it.key} (${it.value})" },
            ActionCategory.SCREEN_CONTROL,
            schema { string("action", "Acción", required = true, enum = ACTIONS.keys.toList()) },
            progress = { "${ACTIONS[it.str("action")] ?: "Listo"}…" },
        ) { args ->
            val action = args.str("action")
            val window = WindowManager.active()
            val keys = keysFor(action, window?.process.orEmpty())
                ?: return@tool ToolResult("No conozco la acción $action", isError = true)
            window?.let { WindowManager.focus(it) }
            press(keys)
            delay(250)
            ToolResult("Listo: ${ACTIONS[action]}${window?.let { " en ${it.process}" } ?: ""}")
        },
    )
}
