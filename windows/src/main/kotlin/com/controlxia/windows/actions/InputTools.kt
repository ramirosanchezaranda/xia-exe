package com.controlxia.windows.actions

import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.bool
import com.controlxia.brain.tools.int
import com.controlxia.brain.tools.intOrNull
import com.controlxia.brain.tools.schema
import com.controlxia.brain.tools.str
import com.controlxia.brain.tools.tool
import com.controlxia.windows.config.AppPaths
import com.controlxia.windows.platform.Win32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * Control de mouse y teclado (pedido para la v1). Pertenece a la categoría
 * INPUT_CONTROL, que por defecto pide confirmación una vez por pedido. Las
 * coordenadas vienen en el sistema de la última captura de [screen].
 */
class InputTools(private val robot: () -> Robot, private val screen: ScreenCapture) {

    fun all(): List<Tool> = listOf(
        tool(
            "mouse_click",
            "Hace clic en un punto de la pantalla, con coordenadas de la última captura de look_at_screen.",
            ActionCategory.INPUT_CONTROL,
            schema {
                integer("x", "Coordenada X en la captura", required = true)
                integer("y", "Coordenada Y en la captura", required = true)
                string("button", "Botón del mouse", enum = listOf("left", "right", "middle"))
                boolean("double", "Doble clic")
            },
            confirm = { "¿Te dejo que maneje el mouse y el teclado para esto?" },
            progress = { "Haciendo clic…" },
        ) { args ->
            val p = screen.toScreen(args.int("x"), args.int("y"))
            val mask = when (args.str("button")) {
                "right" -> InputEvent.BUTTON3_DOWN_MASK
                "middle" -> InputEvent.BUTTON2_DOWN_MASK
                else -> InputEvent.BUTTON1_DOWN_MASK
            }
            withRobot { r ->
                r.mouseMove(p.x, p.y)
                repeat(if (args.bool("double")) 2 else 1) {
                    r.mousePress(mask)
                    r.mouseRelease(mask)
                    r.delay(60)
                }
            }
            ToolResult("Clic en (${args.int("x")}, ${args.int("y")})")
        },
        tool(
            "mouse_move",
            "Mueve el mouse a un punto de la última captura, sin hacer clic.",
            ActionCategory.INPUT_CONTROL,
            schema {
                integer("x", "Coordenada X en la captura", required = true)
                integer("y", "Coordenada Y en la captura", required = true)
            },
            confirm = { "¿Te dejo que maneje el mouse y el teclado para esto?" },
        ) { args ->
            val p = screen.toScreen(args.int("x"), args.int("y"))
            withRobot { it.mouseMove(p.x, p.y) }
            ToolResult("Mouse movido")
        },
        tool(
            "scroll",
            "Hace scroll con la rueda del mouse donde esté el puntero. Positivo baja, negativo sube.",
            ActionCategory.INPUT_CONTROL,
            schema { integer("amount", "Cantidad de pasos, ej. 5 o -5", required = true) },
            confirm = { "¿Te dejo que maneje el mouse y el teclado para esto?" },
        ) { args ->
            withRobot { it.mouseWheel(args.intOrNull("amount") ?: 3) }
            ToolResult("Scroll hecho")
        },
        tool(
            "type_text",
            "Escribe texto en la ventana activa, como si se tipeara.",
            ActionCategory.INPUT_CONTROL,
            schema { string("text", "Texto a escribir", required = true) },
            confirm = { "¿Te dejo que maneje el mouse y el teclado para esto?" },
            progress = { "Escribiendo…" },
        ) { args ->
            val text = args.str("text")
            typeText(text)
            ToolResult("Escribí ${text.length} caracteres")
        },
        tool(
            "press_keys",
            "Presiona una tecla o combinación, ej: enter, esc, ctrl+s, alt+tab, win+d, ctrl+shift+esc.",
            ActionCategory.INPUT_CONTROL,
            schema { string("keys", "Combinación separada por +", required = true) },
            confirm = { "¿Te dejo que maneje el mouse y el teclado para esto?" },
        ) { args ->
            val codes = parseKeys(args.str("keys"))
                ?: return@tool ToolResult("No reconozco la combinación ${args.str("keys")}", isError = true)
            withRobot { r ->
                codes.forEach { r.keyPress(it) }
                codes.reversed().forEach { r.keyRelease(it) }
            }
            ToolResult("Presioné ${args.str("keys")}")
        },
    )

    private suspend fun typeText(text: String) {
        withContext(Dispatchers.IO) {
            if (AppPaths.isWindows) {
                Win32.typeUnicode(text)
            } else {
                // Fuera de Windows: se pega desde el portapapeles.
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                val r = robot()
                r.keyPress(KeyEvent.VK_CONTROL); r.keyPress(KeyEvent.VK_V)
                r.keyRelease(KeyEvent.VK_V); r.keyRelease(KeyEvent.VK_CONTROL)
            }
        }
        delay(SETTLE_MS)
    }

    private suspend fun withRobot(block: (Robot) -> Unit) {
        withContext(Dispatchers.IO) { block(robot()) }
        // Deja que la interfaz reaccione antes de la próxima captura.
        delay(SETTLE_MS)
    }

    companion object {
        private const val SETTLE_MS = 400L

        /** "ctrl+shift+esc" → códigos de tecla de AWT en orden. */
        fun parseKeys(combo: String): List<Int>? {
            val parts = combo.lowercase().split('+').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            return parts.map { keyCode(it) ?: return null }
        }

        private fun keyCode(key: String): Int? = when (key) {
            "ctrl", "control" -> KeyEvent.VK_CONTROL
            "alt" -> KeyEvent.VK_ALT
            "shift", "mayus", "mayús" -> KeyEvent.VK_SHIFT
            "win", "windows", "super", "meta" -> KeyEvent.VK_WINDOWS
            "enter", "intro", "return" -> KeyEvent.VK_ENTER
            "esc", "escape" -> KeyEvent.VK_ESCAPE
            "tab" -> KeyEvent.VK_TAB
            "space", "espacio" -> KeyEvent.VK_SPACE
            "backspace", "borrar", "retroceso" -> KeyEvent.VK_BACK_SPACE
            "delete", "del", "supr", "suprimir" -> KeyEvent.VK_DELETE
            "up", "arriba" -> KeyEvent.VK_UP
            "down", "abajo" -> KeyEvent.VK_DOWN
            "left", "izquierda" -> KeyEvent.VK_LEFT
            "right", "derecha" -> KeyEvent.VK_RIGHT
            "home", "inicio" -> KeyEvent.VK_HOME
            "end", "fin" -> KeyEvent.VK_END
            "pageup", "repag" -> KeyEvent.VK_PAGE_UP
            "pagedown", "avpag" -> KeyEvent.VK_PAGE_DOWN
            "printscreen", "impr" -> KeyEvent.VK_PRINTSCREEN
            else -> when {
                key.length == 1 && key[0] in 'a'..'z' -> KeyEvent.VK_A + (key[0] - 'a')
                key.length == 1 && key[0] in '0'..'9' -> KeyEvent.VK_0 + (key[0] - '0')
                key.matches(Regex("f([1-9]|1[0-2])")) -> KeyEvent.VK_F1 + key.drop(1).toInt() - 1
                else -> null
            }
        }
    }
}
