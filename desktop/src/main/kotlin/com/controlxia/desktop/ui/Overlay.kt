package com.controlxia.desktop.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.controlxia.windows.assistant.AssistantController
import com.controlxia.windows.assistant.AssistantUi
import com.controlxia.windows.assistant.Phase

/**
 * Indicador flotante siempre visible (RF-06): estado, lo que va entendiendo,
 * la respuesta y, si hace falta, los botones para confirmar (RF-17).
 *
 * No toma el foco salvo que el usuario quiera escribir: así no le roba el
 * teclado a la ventana en la que el asistente escribe o hace clic.
 */
@Composable
fun OverlayWindow(ui: AssistantUi, controller: AssistantController) {
    var typing by remember { mutableStateOf(false) }
    val busy = ui.phase == Phase.THINKING || ui.phase == Phase.SPEAKING || ui.phase == Phase.CONFIRMING
    val wantsTyping = (typing || !ui.voiceInputReady) && !busy
    val state = rememberWindowState(
        position = WindowPosition.Aligned(Alignment.BottomCenter),
        size = DpSize(480.dp, 230.dp),
    )
    Window(
        onCloseRequest = { controller.dismiss() },
        state = state,
        title = Texts.APP,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = wantsTyping,
    ) {
        // Al empezar a procesar, el indicador suelta el foco: así las teclas que
        // escriba el asistente van a la ventana de trabajo y no a esta.
        LaunchedEffect(busy) {
            if (busy) {
                typing = false
                window.focusableWindowState = false
                if (window.isFocused) {
                    window.isVisible = false
                    window.isVisible = true
                }
            }
        }
        XiaTheme {
            Box(Modifier.fillMaxSize().padding(bottom = 56.dp), contentAlignment = Alignment.BottomCenter) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Paper, RoundedCornerShape(4.dp))
                        .border(1.dp, Hairline, RoundedCornerShape(4.dp))
                        .padding(16.dp),
                ) {
                    Header(ui, onClose = { typing = false; controller.dismiss() }, onKeyboard = { typing = !typing })
                    Spacer(Modifier.padding(4.dp))
                    when {
                        ui.confirmQuestion != null -> Confirmation(ui.confirmQuestion!!, controller)
                        wantsTyping ->
                            TypeBox(onSubmit = { controller.submitText(it) })
                        else -> Body(ui)
                    }
                    if (ui.phase == Phase.SPEAKING) {
                        TextButton(onClick = { controller.stopSpeaking() }) { Text(Texts.STOP) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(ui: AssistantUi, onClose: () -> Unit, onKeyboard: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PhaseDot(ui.phase)
        Spacer(Modifier.width(10.dp))
        Text(
            "${ui.agentName} · ${phaseLabel(ui.phase)}".uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Muted,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onKeyboard) { Text("⌨", color = Muted) }
        TextButton(onClick = onClose) { Text("✕", color = Muted) }
    }
}

@Composable
private fun Body(ui: AssistantUi) {
    if (ui.transcript.isNotBlank()) {
        Text("“${ui.transcript}”", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (ui.response.isNotBlank()) {
        Text(
            ui.response,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    val status = ui.status.ifBlank { if (ui.transcript.isBlank() && ui.response.isBlank()) ui.wakeStatus else "" }
    if (status.isNotBlank()) {
        Text(status, style = MaterialTheme.typography.labelSmall, color = Accent, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Confirmation(question: String, controller: AssistantController) {
    Text(question, style = MaterialTheme.typography.titleMedium)
    Text("Respondé «sí» o «no», o usá los botones.", style = MaterialTheme.typography.bodySmall, color = Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
        Button(onClick = { controller.answerConfirmation(true) }) { Text(Texts.YES) }
        OutlinedButton(onClick = { controller.answerConfirmation(false) }) { Text(Texts.NO) }
    }
}

@Composable
private fun TypeBox(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(Texts.TYPE_HINT) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().onKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && event.key == Key.Enter && text.isNotBlank()) {
                onSubmit(text)
                text = ""
                true
            } else {
                false
            }
        },
    )
}

/** Punto de estado; late suave mientras escucha o piensa. */
@Composable
private fun PhaseDot(phase: Phase) {
    val color = when (phase) {
        Phase.LISTENING -> Accent
        Phase.THINKING -> Amber
        Phase.SPEAKING -> Green
        Phase.CONFIRMING -> Warn
        Phase.PAUSED -> Muted
        else -> Ink
    }
    val pulse = rememberInfiniteTransition()
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (phase == Phase.LISTENING || phase == Phase.THINKING) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    Box(Modifier.size(12.dp).alpha(alpha).background(color, CircleShape))
}

fun phaseLabel(phase: Phase): String = when (phase) {
    Phase.STARTING -> Texts.PHASE_STARTING
    Phase.IDLE -> Texts.PHASE_IDLE
    Phase.PAUSED -> Texts.PHASE_PAUSED
    Phase.LISTENING -> Texts.PHASE_LISTENING
    Phase.THINKING -> Texts.PHASE_THINKING
    Phase.SPEAKING -> Texts.PHASE_SPEAKING
    Phase.CONFIRMING -> Texts.PHASE_CONFIRMING
}
