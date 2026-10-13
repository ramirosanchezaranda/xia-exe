package com.controlxia.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import com.controlxia.desktop.ui.AppContext
import com.controlxia.desktop.ui.OnboardingWindow
import com.controlxia.desktop.ui.OverlayWindow
import com.controlxia.desktop.ui.SettingsWindow
import com.controlxia.desktop.ui.Texts
import com.controlxia.desktop.ui.TrayIcons
import com.controlxia.desktop.ui.phaseLabel
import com.controlxia.windows.assistant.AssistantController
import com.controlxia.windows.assistant.Log
import com.controlxia.windows.config.HistoryStore
import com.controlxia.windows.config.SecretStore
import com.controlxia.windows.config.SettingsRepository
import com.controlxia.windows.platform.SingleInstance
import javax.swing.JOptionPane

/**
 * Punto de entrada. `--tray` (lo usa el arranque con Windows) inicia
 * minimizado en la bandeja; sin argumentos abre la configuración.
 */
fun main(args: Array<String>) {
    // Vosk devuelve UTF-8; sin esto JNA lo lee como cp1252 y "podrías" llega como "podrÃ­as".
    // Tiene que fijarse antes de que se cargue cualquier clase de JNA.
    System.setProperty("jna.encoding", "UTF-8")
    if (!SingleInstance.acquire()) {
        JOptionPane.showMessageDialog(null, "ControlXIA ya está abierto: buscalo en la bandeja del sistema.")
        return
    }
    Thread.setDefaultUncaughtExceptionHandler { _, e -> Log.e("Error no controlado", e) }

    val settings = SettingsRepository()
    val secrets = SecretStore.create()
    val history = HistoryStore()
    val controller = AssistantController(settings, secrets, history)
    val app = AppContext(settings, secrets, history, controller)
    controller.start()
    Log.i("ControlXIA iniciado")

    val startInTray = "--tray" in args

    application {
        val ui by controller.ui.collectAsState()
        val s by settings.state.collectAsState()
        var settingsOpen by remember { mutableStateOf(!startInTray || !s.onboardingDone) }
        val trayState = rememberTrayState()

        LaunchedEffect(Unit) {
            controller.notices.collect { trayState.sendNotification(Notification(it.title, it.message, Notification.Type.Info)) }
        }

        Tray(
            icon = TrayIcons.forPhase(ui.phase),
            state = trayState,
            tooltip = "${s.agentName} · ${phaseLabel(ui.phase)}",
            onAction = { settingsOpen = true },
            menu = {
                Item(Texts.TRAY_TALK.format(s.agentName), onClick = { controller.activate() })
                CheckboxItem(Texts.TRAY_PAUSE, checked = s.listeningPaused, onCheckedChange = { controller.setPaused(it) })
                Item(Texts.TRAY_SETTINGS, onClick = { settingsOpen = true })
                Item(Texts.TRAY_FORGET, onClick = { controller.forgetConversation() })
                Separator()
                Item(Texts.TRAY_EXIT, onClick = {
                    controller.shutdown()
                    exitApplication()
                })
            },
        )

        if (ui.overlayVisible) OverlayWindow(ui, controller)

        if (settingsOpen) {
            if (!s.onboardingDone) {
                OnboardingWindow(app, onDone = { settingsOpen = false }, onClose = { settingsOpen = false })
            } else {
                // Cerrar la ventana no cierra la app: sigue en la bandeja.
                SettingsWindow(app, onClose = { settingsOpen = false })
            }
        }
    }
}
