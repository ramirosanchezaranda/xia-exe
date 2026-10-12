package com.controlxia.windows.actions

import com.controlxia.brain.tools.Tool
import com.controlxia.windows.platform.PowerShellHost
import kotlinx.coroutines.CoroutineScope
import java.awt.Robot

/** Todas las acciones de Windows que el asistente ofrece al LLM. */
class WindowsToolset(shell: PowerShellHost, scope: CoroutineScope, val timers: TimerService) {

    private val robot by lazy { Robot().apply { autoDelay = 20 } }

    val apps = AppTools(shell)
    val screen = ScreenCapture { robot }
    private val volume = VolumeTools(shell)
    private val power = PowerTools(shell, scope)
    private val input = InputTools({ robot }, screen)

    val all: List<Tool> by lazy {
        InfoTools.all() +
            apps.all() +
            WebTools.all() +
            volume.all() +
            timers.tools() +
            power.all() +
            screen.tools() +
            input.all()
    }
}
