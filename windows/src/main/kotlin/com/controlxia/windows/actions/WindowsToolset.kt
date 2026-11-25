package com.controlxia.windows.actions

import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolGroup
import com.controlxia.windows.platform.PowerShellHost
import kotlinx.coroutines.CoroutineScope
import java.awt.Robot

/**
 * Todas las acciones de Windows, agrupadas: en cada pedido el cerebro manda
 * al modelo solo los grupos que corresponden a lo que se dijo.
 */
class WindowsToolset(shell: PowerShellHost, scope: CoroutineScope, val timers: TimerService) {

    private val robot by lazy { Robot().apply { autoDelay = 20 } }

    val apps = AppTools(shell)
    val screen = ScreenCapture { robot }
    val control = ScreenControl(shell)
    val volume = VolumeTools(shell)
    val power = PowerTools(shell, scope)
    private val input = InputTools({ robot }, screen)

    val all: List<Tool> by lazy { groups(vision = true).flatMap { it.tools } }

    /** Grupos de herramientas. Sin [vision], no se ofrecen capturas ni mouse por coordenadas. */
    fun groups(vision: Boolean, extra: List<ToolGroup> = emptyList()): List<ToolGroup> {
        val byName = (InfoTools.all() + apps.all() + WebTools.all() + volume.all() + timers.tools() + power.all() +
            screen.tools() + input.all() + control.tools() + WindowManager.tools() + Shortcuts.tools()).associateBy { it.spec.name }
        fun pick(vararg names: String) = names.mapNotNull { byName[it] }
            .filter { vision || it.spec.name !in VISION_TOOLS }
        return listOf(
            ToolGroup(
                "basico", "hora, abrir programas, carpetas y páginas, volumen, música y temporizador", emptyList(),
                pick("get_time", "get_date", "open_app", "open_folder", "open_url", "web_search", "volume", "media_control", "set_timer", "switch_to_window"),
                alwaysOn = true,
            ),
            ToolGroup(
                "ventanas", "minimizar, maximizar, cerrar y acomodar ventanas o programas",
                listOf("ventana", "ventanas", "minimiza", "maximiza", "restaura", "cerra", "cierra", "escritorio", "pantalla completa", "izquierda", "derecha", "costado", "acomoda"),
                pick("window_action", "show_desktop", "list_windows", "close_app"),
            ),
            ToolGroup(
                "programa", "tocar botones, escribir en campos y usar comandos (pestañas, guardar, copiar) en el programa activo",
                listOf("toca", "toque", "clic", "click", "boton", "campo", "escribi", "escribe", "pestana", "guarda", "copia", "pega", "corta", "deshace",
                    "rehace", "selecciona", "negrita", "cursiva", "subraya", "recarga", "atras", "adelante", "zoom", "menu", "numero", "completa", "busca en", "marca"),
                pick("screen_read", "screen_tap", "screen_type", "app_command"),
            ),
            ToolGroup(
                "pantalla", "mirar la pantalla y usar mouse y teclado libremente",
                listOf("pantalla", "mira", "ves", "mouse", "raton", "tecla", "teclado", "atajo", "scroll", "arrastra"),
                pick("look_at_screen", "mouse_click", "mouse_move", "scroll", "type_text", "press_keys"),
            ),
            ToolGroup(
                "recordatorios", "recordatorios y temporizadores pendientes",
                listOf("recorda", "recordatorio", "temporizador", "temporizadores", "alarma", "avisame", "cancela"),
                pick("set_reminder", "list_timers", "cancel_timer"),
            ),
            ToolGroup(
                "energia", "bloquear, apagar la pantalla, suspender, reiniciar y apagar la PC",
                listOf("bloquea", "apaga", "reinicia", "suspende", "computadora", "compu", "pc"),
                pick("lock_pc", "screen_off", "sleep_pc", "restart_pc", "shutdown_pc", "cancel_shutdown"),
            ),
        ).filter { it.tools.isNotEmpty() } + extra
    }

    companion object {
        val VISION_TOOLS = setOf("look_at_screen", "mouse_click", "mouse_move")
    }
}
