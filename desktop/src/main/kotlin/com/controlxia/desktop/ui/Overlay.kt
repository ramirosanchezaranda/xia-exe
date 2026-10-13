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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.controlxia.windows.assistant.AssistantController
import com.controlxia.windows.assistant.AssistantUi
import com.controlxia.windows.assistant.ChatLine
import com.controlxia.windows.assistant.Phase
import com.controlxia.windows.assistant.Speaker

/**
 * Indicador flotante siempre visible (RF-06), en forma de chat: toda la
 * conversación en curso, lo que va entendiendo, el estado y, si hace falta,
 * los botones para confirmar (RF-17). Se puede hablar o escribir.
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
        position = WindowPosition.Aligned(Alignment.BottomEnd),
        size = DpSize(440.dp, 480.dp),
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
            Box(Modifier.fillMaxSize().padding(end = 16.dp, bottom = 56.dp), contentAlignment = Alignment.BottomEnd) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Paper, RoundedCornerShape(6.dp))
                        .border(1.dp, Hairline, RoundedCornerShape(6.dp))
                        .padding(14.dp),
                ) {
                    Header(ui, onClose = { typing = false; controller.dismiss() }, onKeyboard = { typing = !typing })
                    Conversation(ui, Modifier.weight(1f, fill = false))
                    when {
                        ui.confirmQuestion != null -> Confirmation(ui.confirmQuestion!!, controller)
                        wantsTyping -> TypeBox(onSubmit = { controller.submitText(it) })
                    }
                    Footer(ui, controller)
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

/** Los mensajes de la conversación, con lo que se va escuchando al final. */
@Composable
private fun Conversation(ui: AssistantUi, modifier: Modifier) {
    val listState = rememberLazyListState()
    val pending = ui.transcript.isNotBlank()
    val count = ui.conversation.size + if (pending) 1 else 0
    LaunchedEffect(count, ui.transcript) {
        if (count > 0) listState.scrollToItem(count - 1)
    }
    if (count == 0) {
        val hint = ui.status.ifBlank { ui.wakeStatus }
        if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ui.conversation) { line -> Bubble(line, ui.agentName) }
        if (pending) item { Bubble(ChatLine(Speaker.USER, ui.transcript), ui.agentName, live = true) }
    }
}

@Composable
private fun Bubble(line: ChatLine, agentName: String, live: Boolean = false) {
    val mine = line.speaker == Speaker.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(if (mine) Accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                (if (mine) "Vos" else agentName).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (mine) Accent else Muted,
            )
            Text(
                line.text,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (live) FontStyle.Italic else FontStyle.Normal,
                color = if (live) Muted else Ink,
            )
            line.actions.forEach { action ->
                Text("→ $action", style = MaterialTheme.typography.labelSmall, color = Accent, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun Footer(ui: AssistantUi, controller: AssistantController) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        val status = if (ui.conversation.isEmpty() && ui.transcript.isBlank()) "" else ui.status
        Text(status, style = MaterialTheme.typography.labelSmall, color = Accent, modifier = Modifier.weight(1f))
        if (ui.phase == Phase.SPEAKING) {
            TextButton(onClick = { controller.stopSpeaking() }) { Text(Texts.STOP) }
        }
        if (ui.phase == Phase.IDLE && ui.voiceInputReady && ui.conversation.isNotEmpty()) {
            TextButton(onClick = { controller.activate() }) { Text("Hablar") }
        }
    }
}

@Composable
private fun Confirmation(question: String, controller: AssistantController) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(question, style = MaterialTheme.typography.titleSmall)
        Text("Respondé «sí» o «no», o usá los botones.", style = MaterialTheme.typography.bodySmall, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { controller.answerConfirmation(true) }) { Text(Texts.YES) }
            OutlinedButton(onClick = { controller.answerConfirmation(false) }) { Text(Texts.NO) }
        }
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
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).onKeyEvent { event ->
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
