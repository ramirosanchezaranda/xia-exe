package com.controlxia.windows.actions

import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.tool
import com.controlxia.windows.platform.PowerShellHost
import com.controlxia.windows.platform.Win32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * RF-15: bloquear, apagar pantalla, suspender, reiniciar y apagar. Las de
 * energía son de la categoría POWER, que por defecto pide confirmación.
 * Antes de suspender o apagar se espera un momento para que el asistente
 * termine de hablar.
 */
class PowerTools(private val shell: PowerShellHost, private val scope: CoroutineScope) {

    fun all(): List<Tool> = listOf(
        tool("lock_pc", "Bloquea la computadora (como Windows+L).", ActionCategory.LOCK_SCREEN) {
            delayed { Win32.lockWorkstation() }
            ToolResult("Bloqueando la computadora")
        },
        tool("screen_off", "Apaga la pantalla; se prende al mover el mouse.", ActionCategory.LOCK_SCREEN) {
            delayed { Win32.monitorsOff() }
            ToolResult("Apagando la pantalla")
        },
        tool(
            "sleep_pc", "Suspende la computadora.", ActionCategory.POWER,
            confirm = { "¿Suspendo la computadora?" },
        ) {
            delayed {
                shell.run(
                    "Add-Type -AssemblyName System.Windows.Forms; " +
                        "[void][System.Windows.Forms.Application]::SetSuspendState('Suspend', \$false, \$false)",
                )
            }
            ToolResult("Suspendiendo")
        },
        tool(
            "restart_pc", "Reinicia la computadora.", ActionCategory.POWER,
            confirm = { "¿Reinicio la computadora? Se va a cerrar todo lo abierto." },
        ) {
            ProcessBuilder("shutdown", "/r", "/t", "10").start()
            ToolResult("Reiniciando en 10 segundos. Decime cancelá el apagado si te arrepentís.")
        },
        tool(
            "shutdown_pc", "Apaga la computadora.", ActionCategory.POWER,
            confirm = { "¿Apago la computadora? Se va a cerrar todo lo abierto." },
        ) {
            ProcessBuilder("shutdown", "/s", "/t", "10").start()
            ToolResult("Apagando en 10 segundos. Decime cancelá el apagado si te arrepentís.")
        },
        tool("cancel_shutdown", "Cancela un apagado o reinicio programado.", ActionCategory.INFO) {
            ProcessBuilder("shutdown", "/a").start().waitFor()
            ToolResult("Cancelé el apagado")
        },
    )

    /** Deja unos segundos para que se diga la respuesta antes de ejecutar. */
    private fun delayed(action: suspend () -> Unit) {
        scope.launch {
            delay(2_500)
            runCatching { action() }
        }
    }
}
