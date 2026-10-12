package com.controlxia.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.controlxia.brain.llm.LlmProviderType
import java.awt.Desktop
import java.net.URI

/**
 * Asistente de primera vez (RF-22): nombre, cerebro (clave y prueba),
 * reconocimiento de voz y micrófono, palabra de activación.
 */
@Composable
fun OnboardingWindow(app: AppContext, onDone: () -> Unit, onClose: () -> Unit) {
    val state = rememberWindowState(size = DpSize(720.dp, 680.dp), position = WindowPosition.Aligned(Alignment.Center))
    var step by remember { mutableStateOf(0) }
    val steps = listOf("Bienvenida", "Cerebro", "Micrófono y voz", "Activación")

    Window(onCloseRequest = onClose, state = state, title = Texts.ONBOARDING_TITLE) {
        XiaTheme {
            Surface(Modifier.fillMaxSize(), color = Paper) {
                Column(Modifier.fillMaxSize().padding(32.dp)) {
                    Text(
                        "PASO ${step + 1} DE ${steps.size} · ${steps[step].uppercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Accent,
                    )
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
                        when (step) {
                            0 -> WelcomeStep(app)
                            1 -> BrainStep(app)
                            2 -> VoiceStep(app)
                            else -> WakeStep(app)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (step > 0) OutlinedButton(onClick = { step-- }) { Text(Texts.BACK) }
                        Spacer(Modifier.weight(1f))
                        if (step < steps.lastIndex) {
                            Button(onClick = { step++ }) { Text(Texts.NEXT) }
                        } else {
                            Button(onClick = {
                                app.settings.update { it.copy(onboardingDone = true) }
                                onDone()
                            }) { Text(Texts.FINISH) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(app: AppContext) {
    val s by app.settings.state.collectAsState()
    Text("Hola. Vamos a dejar listo tu asistente.", style = MaterialTheme.typography.headlineMedium)
    VSpace(12)
    Text(
        "Vive en la bandeja del sistema, escucha una palabra de activación y hace cosas en la PC: " +
            "abrir programas, buscar en internet, manejar el volumen, poner temporizadores y, si lo " +
            "permitís, usar el mouse y el teclado. Las acciones riesgosas siempre piden confirmación.",
        style = MaterialTheme.typography.bodyLarge,
    )
    SectionLabel("¿Cómo se va a llamar?")
    OutlinedTextField(
        value = s.agentName,
        onValueChange = { name ->
            app.settings.update { it.copy(agentName = name, wake = it.wake.copy(voskPhrase = name.lowercase())) }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BrainStep(app: AppContext) {
    val s by app.settings.state.collectAsState()
    Text("Elegí el cerebro", style = MaterialTheme.typography.headlineMedium)
    Hint("Es el modelo de lenguaje que entiende tus pedidos. Podés cambiarlo cuando quieras sin reiniciar.")
    VSpace()
    Dropdown(
        label = "Proveedor",
        options = LlmProviderType.values().toList(),
        selected = s.llm.active,
        display = { it.displayName },
        onSelect = { v -> app.settings.update { it.copy(llm = it.llm.copy(active = v)) } },
    )
    ProviderEditor(app, s.llm.active)
    Hint("¿Sin internet? Elegí Ollama e instalalo desde ollama.com; corre el modelo en tu PC.")
}

@Composable
private fun VoiceStep(app: AppContext) {
    Text("Micrófono y reconocimiento de voz", style = MaterialTheme.typography.headlineMedium)
    Hint("Windows tiene que dejar que las apps de escritorio usen el micrófono.")
    OutlinedButton(
        onClick = { runCatching { Desktop.getDesktop().browse(URI("ms-settings:privacy-microphone")) } },
        modifier = Modifier.padding(top = 8.dp),
    ) { Text("Abrir permisos de micrófono de Windows") }
    VoskModelCard(app)
    SectionLabel("Voz del asistente")
    Hint("Podés elegir la voz, el tono y la velocidad después, en Configuración › Voz.")
}

@Composable
private fun WakeStep(app: AppContext) {
    val s by app.settings.state.collectAsState()
    Text("Cómo lo llamás", style = MaterialTheme.typography.headlineMedium)
    WakeWordEditor(app)
    SectionLabel("Atajo de teclado")
    Text("También podés abrirlo con ${s.hotkey} desde cualquier programa.", style = MaterialTheme.typography.bodyMedium)
}
