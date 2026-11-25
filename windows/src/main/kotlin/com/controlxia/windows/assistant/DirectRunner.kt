package com.controlxia.windows.assistant

import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.PermissionPolicy
import com.controlxia.windows.actions.AppTools
import com.controlxia.windows.actions.InfoTools
import com.controlxia.windows.actions.ScreenSnapshot
import com.controlxia.windows.actions.Shortcuts
import com.controlxia.windows.actions.TimerService
import com.controlxia.windows.actions.WindowManager
import com.controlxia.windows.actions.WindowsToolset
import com.controlxia.windows.platform.Win32
import com.controlxia.windows.voice.normalizeSpeech
import java.time.LocalDateTime

/**
 * Hace los [DirectCommands] sin pasar por el modelo. Devuelve la frase a
 * decir, o null si el pedido no se pudo resolver así (y entonces va al modelo).
 */
class DirectRunner(
    private val toolset: WindowsToolset,
    private val policy: (ActionCategory) -> PermissionPolicy,
    private val confirm: suspend (String) -> Boolean,
    /** Muestra (o con null oculta) los números sobre la ventana activa. */
    private val showNumbers: (ScreenSnapshot?) -> Unit,
) {
    suspend fun run(command: DirectCommands.Command): String? = when (command) {
        is DirectCommands.Volume -> toolset.volume.volume(command.change, command.percent).text
        is DirectCommands.Media -> {
            Win32.tapVirtualKey(
                when (command.action) {
                    "next" -> Win32.VK_MEDIA_NEXT_TRACK
                    "previous" -> Win32.VK_MEDIA_PREV_TRACK
                    else -> Win32.VK_MEDIA_PLAY_PAUSE
                },
            )
            when (command.action) { "next" -> "Siguiente"; "previous" -> "Anterior"; else -> "Listo" }
        }
        is DirectCommands.Window -> {
            val target = if (command.target.isBlank()) WindowManager.active() else WindowManager.find(command.target)
            when {
                target == null -> if (command.target.isBlank()) "No hay una ventana activa" else null
                command.action == "close" && !allowed(ActionCategory.CLOSE_APPS, "¿Cierro ${target.title}?") -> "Listo, no la cierro."
                else -> WindowManager.act(command.action, target)
            }
        }
        is DirectCommands.SwitchTo -> WindowManager.find(command.name)?.let { w ->
            WindowManager.focus(w)
            "Listo"
        }
        DirectCommands.ShowDesktop -> {
            Shortcuts.press(listOf(Shortcuts.WIN, 'D'.code))
            "Listo"
        }
        is DirectCommands.AppCommand -> {
            val window = WindowManager.active()
            val keys = Shortcuts.keysFor(command.action, window?.process.orEmpty())
            if (keys == null || !allowed(ActionCategory.SCREEN_CONTROL, "")) null
            else {
                window?.let { WindowManager.focus(it) }
                Shortcuts.press(keys)
                "Listo"
            }
        }
        is DirectCommands.Numbers -> if (command.show) {
            val snapshot = toolset.control.read()
            showNumbers(snapshot)
            if (snapshot.elements.isEmpty()) "No encontré botones en esta ventana" else "Decime el número"
        } else {
            showNumbers(null)
            "Listo"
        }
        is DirectCommands.TapNumber -> {
            if (toolset.control.last == null) toolset.control.read()
            tapChecked(command.number)
        }
        is DirectCommands.TapLabel -> toolset.control.find(command.label)?.let { tapChecked(it.number) }
        is DirectCommands.Type -> if (command.text.isBlank() || !allowed(ActionCategory.SCREEN_CONTROL, "")) null else {
            WindowManager.active()?.let { WindowManager.focus(it) }
            Win32.typeUnicode(command.text)
            "Listo"
        }
        is DirectCommands.Open -> open(command.name)
        DirectCommands.Time -> InfoTools.timePhrase(LocalDateTime.now())
        DirectCommands.Date -> InfoTools.datePhrase(LocalDateTime.now())
        is DirectCommands.Timer -> {
            toolset.timers.schedule("Temporizador", System.currentTimeMillis() + command.seconds * 1000L, isReminder = false)
            "Listo, temporizador de ${TimerService.durationPhrase(command.seconds)}"
        }
        DirectCommands.Lock -> if (allowed(ActionCategory.LOCK_SCREEN, "¿Bloqueo la computadora?")) {
            Win32.lockWorkstation(); "Bloqueada"
        } else "Listo, no la bloqueo."
        DirectCommands.ScreenOff -> if (allowed(ActionCategory.LOCK_SCREEN, "¿Apago la pantalla?")) {
            Win32.monitorsOff(); "Listo"
        } else "Listo, no la apago."
    }

    private suspend fun tapChecked(number: Int): String {
        if (toolset.control.isSensitive(number) &&
            !allowed(ActionCategory.SENSITIVE, "¿Toco ${toolset.control.describe(number)}?")
        ) return "Listo, no lo toco."
        val result = toolset.control.tap(number)
        showNumbers(null)
        return result
    }

    /** Programa o carpeta por nombre; si no hay uno claro, que lo resuelva el modelo. */
    private suspend fun open(name: String): String? {
        if (AppTools.knownFolder(name) != null || AppTools.systemApp(name) != null) return toolset.apps.open(name).text
        val apps = runCatching { toolset.apps.apps() }.getOrDefault(emptyList())
        val match = AppTools.bestMatch(name, apps) { it.name } ?: return null
        // Solo si se parece mucho: con dudas decide el modelo.
        if (AppTools.score(name.normalizeSpeech(), match.name.normalizeSpeech()) < 80) return null
        return toolset.apps.open(match.name).text
    }

    private suspend fun allowed(category: ActionCategory, question: String): Boolean = when (policy(category)) {
        PermissionPolicy.ALLOW -> true
        PermissionPolicy.BLOCK -> false
        PermissionPolicy.ASK -> question.isNotBlank() && confirm(question)
    }
}
