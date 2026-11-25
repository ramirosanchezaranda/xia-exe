package com.controlxia.windows.actions

import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.schema
import com.controlxia.brain.tools.str
import com.controlxia.brain.tools.tool
import com.controlxia.windows.config.AppPaths
import com.controlxia.windows.platform.Win32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.delay

/** Una ventana abierta de otro programa. */
data class WindowInfo(val handle: WinDef.HWND, val title: String, val process: String)

/**
 * Ventanas por voz: cambiar a un programa, minimizar, maximizar, cerrar,
 * acomodar a un costado y mostrar el escritorio.
 */
object WindowManager {

    private val ownPid = ProcessHandle.current().pid().toInt()

    /** Ventanas visibles con título, en orden de arriba hacia abajo, sin las de ControlXIA. */
    fun list(): List<WindowInfo> {
        if (!AppPaths.isWindows) return emptyList()
        val out = mutableListOf<WindowInfo>()
        User32.INSTANCE.EnumWindows({ hwnd, _ ->
            if (User32.INSTANCE.IsWindowVisible(hwnd)) {
                val exStyle = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
                val chars = CharArray(512)
                val len = User32.INSTANCE.GetWindowText(hwnd, chars, chars.size)
                val title = String(chars, 0, len).trim()
                val pid = IntByReference()
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid)
                if (title.isNotEmpty() && pid.value != ownPid && exStyle and WS_EX_TOOLWINDOW == 0 && title !in IGNORED) {
                    out += WindowInfo(hwnd, title, processName(pid.value))
                }
            }
            true
        }, null)
        return out
    }

    /** La ventana en la que está trabajando el usuario (la de arriba que no es de ControlXIA). */
    fun active(): WindowInfo? = list().firstOrNull()

    fun find(query: String): WindowInfo? {
        val all = list()
        return AppTools.bestMatch(query, all) { "${it.process} ${it.title}" } ?: AppTools.bestMatch(query, all) { it.title }
    }

    suspend fun focus(w: WindowInfo) {
        if (isMinimized(w)) User32.INSTANCE.ShowWindow(w.handle, WinUser.SW_RESTORE)
        // Windows solo deja pasar al frente si "hubo una tecla": un Alt suelto lo habilita.
        Win32.tapVirtualKey(VK_MENU)
        User32.INSTANCE.SetForegroundWindow(w.handle)
        delay(150)
    }

    private fun isMinimized(w: WindowInfo): Boolean {
        val placement = WinUser.WINDOWPLACEMENT()
        User32.INSTANCE.GetWindowPlacement(w.handle, placement)
        return placement.showCmd == WinUser.SW_SHOWMINIMIZED
    }

    suspend fun act(action: String, w: WindowInfo): String {
        when (action) {
            "minimize" -> User32.INSTANCE.ShowWindow(w.handle, WinUser.SW_MINIMIZE)
            "maximize" -> User32.INSTANCE.ShowWindow(w.handle, WinUser.SW_MAXIMIZE)
            "restore" -> User32.INSTANCE.ShowWindow(w.handle, WinUser.SW_RESTORE)
            "close" -> User32.INSTANCE.PostMessage(w.handle, WinUser.WM_CLOSE, WinDef.WPARAM(0), WinDef.LPARAM(0))
            "snap_left", "snap_right" -> {
                focus(w)
                Shortcuts.press(listOf(Shortcuts.WIN, if (action == "snap_left") Shortcuts.LEFT else Shortcuts.RIGHT))
            }
            "fullscreen" -> {
                focus(w)
                Shortcuts.press(listOf(Shortcuts.F11))
            }
            else -> return "No conozco la acción $action"
        }
        return when (action) {
            "minimize" -> "Minimicé ${w.process}"
            "maximize" -> "Maximicé ${w.process}"
            "restore" -> "Restauré ${w.process}"
            "close" -> "Cerré ${w.title}"
            "snap_left" -> "Puse ${w.process} a la izquierda"
            "snap_right" -> "Puse ${w.process} a la derecha"
            else -> "Pantalla completa"
        }
    }

    fun tools(): List<Tool> = listOf(
        tool(
            "switch_to_window",
            "Pasa a una ventana abierta por el nombre del programa o su título (ej. Chrome, Word, la planilla de gastos).",
            ActionCategory.OPEN_APPS,
            schema { string("name", "Programa o título de la ventana", required = true) },
            progress = { "Cambiando a ${it.str("name")}…" },
        ) { args ->
            val w = find(args.str("name")) ?: return@tool ToolResult("No hay una ventana abierta de ${args.str("name")}", isError = true)
            focus(w)
            ToolResult("Listo, estás en ${w.title}")
        },
        tool(
            "window_action",
            "Minimiza, maximiza, restaura, cierra, pone a un costado o en pantalla completa una ventana. Sin nombre usa la ventana activa.",
            ActionCategory.OPEN_APPS,
            schema {
                string("action", "Qué hacer", required = true, enum = listOf("minimize", "maximize", "restore", "close", "snap_left", "snap_right", "fullscreen"))
                string("name", "Programa o título; vacío = la ventana activa")
            },
        ) { args ->
            val name = args.str("name")
            val w = (if (name.isBlank()) active() else find(name))
                ?: return@tool ToolResult("No encontré la ventana", isError = true)
            ToolResult(act(args.str("action"), w))
        },
        tool("show_desktop", "Minimiza todo y muestra el escritorio (o vuelve a mostrar las ventanas).", ActionCategory.OPEN_APPS) {
            Shortcuts.press(listOf(Shortcuts.WIN, 'D'.code))
            ToolResult("Listo")
        },
        tool("list_windows", "Lista las ventanas abiertas (programa y título).", ActionCategory.INFO) {
            ToolResult(list().joinToString("\n") { "${it.process}: ${it.title}" }.ifBlank { "No hay ventanas abiertas" })
        },
    )

    private fun processName(pid: Int): String = ProcessHandle.of(pid.toLong()).flatMap { it.info().command() }
        .map { java.io.File(it).nameWithoutExtension }.orElse("")

    private const val WS_EX_TOOLWINDOW = 0x00000080
    private const val VK_MENU = 0x12
    private val IGNORED = setOf("Program Manager", "Microsoft Text Input Application")
}
