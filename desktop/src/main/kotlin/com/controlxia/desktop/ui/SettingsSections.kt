package com.controlxia.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.controlxia.brain.llm.LlmDiagnostics
import com.controlxia.brain.llm.LlmProviderType
import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.PermissionPolicy
import com.controlxia.windows.config.AppPaths
import com.controlxia.windows.config.groupConversations
import com.controlxia.windows.config.SecretStore
import com.controlxia.windows.config.WakeEngineType
import com.controlxia.windows.voice.PorcupineWakeWord
import com.controlxia.windows.voice.VoiceInfo
import com.controlxia.windows.voice.VoskModels
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ------------------------------------------------------------------ Asistente

@Composable
fun AssistantSection(app: AppContext) {
    val s by app.settings.state.collectAsState()
    val ui by app.controller.ui.collectAsState()

    SectionLabel("Nombre")
    OutlinedTextField(
        value = s.agentName,
        onValueChange = { name ->
            app.settings.update {
                // Si la frase de Vosk era el nombre, la acompaña.
                val phrase = if (it.wake.voskPhrase.equals(it.agentName, ignoreCase = true)) name.lowercase() else it.wake.voskPhrase
                it.copy(agentName = name, wake = it.wake.copy(voskPhrase = phrase))
            }
        },
        label = { Text("Cómo se llama el asistente") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    WakeWordEditor(app)

    SectionLabel("Estado")
    Text(ui.wakeStatus.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)

    SectionLabel("Atajo de teclado")
    OutlinedTextField(
        value = s.hotkey,
        onValueChange = { v -> app.settings.update { it.copy(hotkey = v) } },
        label = { Text("Combinación global, ej. Ctrl+Alt+X") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Hint("Abre el asistente desde cualquier programa (RF-04).")

    SectionLabel("Conversación")
    SwitchRow(
        "Modo conversación",
        "Después de responder sigue escuchando, sin repetir la palabra de activación. Decí «listo» o «gracias» para terminar.",
        s.conversationMode,
    ) { v -> app.settings.update { it.copy(conversationMode = v) } }
    if (s.conversationMode) {
        LabeledSlider("Silencio que cierra la conversación", s.followUpSeconds, 4..15, valueLabel = { "$it s" }) { v ->
            app.settings.update { it.copy(followUpSeconds = v) }
        }
    }

    Hairline()
    SwitchRow("Pausar la escucha", "Con la escucha pausada no se procesa audio del micrófono.", s.listeningPaused) {
        app.controller.setPaused(it)
    }
    SwitchRow("Arrancar con Windows", "Queda en la bandeja del sistema al iniciar sesión.", s.autostart) { v ->
        app.settings.update { it.copy(autostart = v) }
    }
}

/** Motor de palabra de activación y modelo de voz. Se reusa en el asistente de primera vez. */
@Composable
fun WakeWordEditor(app: AppContext) {
    val s by app.settings.state.collectAsState()

    SectionLabel("Palabra de activación")
    WakeEngineType.values().forEach { type ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s.wake.engine == type, onClick = {
                app.settings.update { it.copy(wake = it.wake.copy(engine = type)) }
            })
            Text(type.displayName, style = MaterialTheme.typography.bodyMedium)
        }
    }

    when (s.wake.engine) {
        WakeEngineType.VOSK -> {
            OutlinedTextField(
                value = s.wake.voskPhrase,
                onValueChange = { v -> app.settings.update { it.copy(wake = it.wake.copy(voskPhrase = v)) } },
                label = { Text("Frase que activa (ej. xia)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Hint("Vosk escucha solo esa frase, sin internet. Funciona con cualquier nombre.")
        }
        WakeEngineType.PORCUPINE -> {
            VSpace()
            SecretField("AccessKey de Picovoice", app.secrets.get(SecretStore.PORCUPINE_ACCESS_KEY)) {
                app.secrets.set(SecretStore.PORCUPINE_ACCESS_KEY, it)
                app.controller.reloadVoice()
            }
            Hint("Gratis en console.picovoice.ai. Es el mismo que usa la app de Android.")
            VSpace()
            Dropdown(
                label = "Palabra de fábrica (en inglés)",
                options = PorcupineWakeWord.builtInKeywords,
                selected = s.wake.porcupineKeyword,
                display = { it.lowercase().replace('_', ' ') },
                onSelect = { v -> app.settings.update { it.copy(wake = it.wake.copy(porcupineKeyword = v)) } },
            )
            VSpace()
            FileField("Modelo propio .ppn para Windows (opcional)", s.wake.customKeywordPath, listOf(".ppn")) { v ->
                app.settings.update { it.copy(wake = it.wake.copy(customKeywordPath = v)) }
            }
            FileField("Modelo de idioma .pv (para un .ppn en español)", s.wake.porcupineModelPath, listOf(".pv")) { v ->
                app.settings.update { it.copy(wake = it.wake.copy(porcupineModelPath = v)) }
            }
            Hint("Los .ppn son por plataforma: el de Android no sirve, hay que descargar el de Windows. Ver docs/PALABRA_PROPIA.md.")
            LabeledSlider(
                label = "Sensibilidad",
                value = (s.wake.sensitivity * 10).toInt(),
                range = 0..10,
                valueLabel = { "${it * 10} %" },
            ) { v -> app.settings.update { it.copy(wake = it.wake.copy(sensitivity = v / 10f)) } }
            Hint("Más alta detecta mejor pero se activa sola con más facilidad (música, videollamadas).")
        }
        WakeEngineType.NONE -> Hint("Se activa solo con el atajo de teclado.")
    }

    VoskModelCard(app)
}

@Composable
fun VoskModelCard(app: AppContext) {
    val s by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installedTick by remember { mutableStateOf(0) }
    val dir = VoskModels.resolveDir(s.voskModelPath)
    val installed = remember(dir, installedTick) { VoskModels.isInstalled(dir) }

    SectionLabel("Reconocimiento de voz (Vosk, sin internet)")
    if (installed) {
        Text("Modelo instalado en ${dir.absolutePath}", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Falta el modelo en español (~40 MB, se descarga una sola vez).", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = RowSpacing, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Button(enabled = progress == null, onClick = {
                error = null
                progress = 0f
                scope.launch {
                    runCatching { VoskModels.download { p -> progress = p } }
                        .onSuccess {
                            installedTick++
                            app.controller.reloadVoice()
                        }
                        .onFailure { error = "No se pudo descargar: ${it.message}" }
                    progress = null
                }
            }) { Text("Descargar modelo") }
            TextButton(onClick = {
                pickFile("Carpeta del modelo (elegí cualquier archivo adentro)", emptyList())?.let { path ->
                    val folder = java.io.File(path).parentFile
                    val root = if (VoskModels.isInstalled(folder)) folder else folder.parentFile
                    app.settings.update { it.copy(voskModelPath = root.absolutePath) }
                }
            }) { Text("Usar otro modelo…") }
        }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
        error?.let { Hint(it, Warn) }
    }
}

// ------------------------------------------------------------------- Cerebro

@Composable
fun LlmSection(app: AppContext) {
    val s by app.settings.state.collectAsState()

    SectionLabel("Proveedor principal")
    Dropdown(
        label = "Se usa para entender los pedidos",
        options = LlmProviderType.values().toList(),
        selected = s.llm.active,
        display = { it.displayName },
        onSelect = { v -> app.settings.update { it.copy(llm = it.llm.copy(active = v)) } },
    )
    ProviderEditor(app, s.llm.active)

    Hairline()
    SectionLabel("Respaldo")
    val fallbackOptions = listOf<LlmProviderType?>(null) + LlmProviderType.values().filter { it != s.llm.active }
    Dropdown(
        label = "Si el principal falla, se usa este",
        options = fallbackOptions,
        selected = s.llm.fallback,
        display = { it?.displayName ?: "Sin respaldo" },
        onSelect = { v -> app.settings.update { it.copy(llm = it.llm.copy(fallback = v)) } },
    )
    s.llm.fallback?.let { ProviderEditor(app, it) }
}

/** Modelo, clave, URL y prueba de conexión de un proveedor (RF-21). */
@Composable
fun ProviderEditor(app: AppContext, type: LlmProviderType) {
    val s by app.settings.state.collectAsState()
    val p = s.llm.forType(type)
    val scope = rememberCoroutineScope()
    var testResult by remember(type) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember(type) { mutableStateOf(false) }
    var ollamaModels by remember(type) { mutableStateOf<List<String>>(emptyList()) }

    fun updateProvider(transform: (com.controlxia.windows.config.ProviderSettings) -> com.controlxia.windows.config.ProviderSettings) =
        app.settings.update { it.copy(llm = it.llm.copy(providers = it.llm.providers + (type to transform(it.llm.forType(type))))) }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(type.displayName.uppercase(), style = MaterialTheme.typography.labelSmall, color = Accent)
        OutlinedTextField(
            value = p.model,
            onValueChange = { v -> updateProvider { it.copy(model = v.trim()) } },
            label = { Text("Modelo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        val suggestions = (type.suggestedModels + ollamaModels).distinct()
        if (suggestions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                suggestions.take(5).forEach { m ->
                    TextButton(onClick = { updateProvider { it.copy(model = m) } }) {
                        Text(m, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (type.needsApiKey || type == LlmProviderType.OPENAI_COMPATIBLE) {
            VSpace()
            SecretField(
                if (type.needsApiKey) "API key" else "API key (si el servicio la pide)",
                app.secrets.get(SecretStore.llmKey(type)),
            ) { app.secrets.set(SecretStore.llmKey(type), it) }
            Hint("Se guarda en el Administrador de credenciales de Windows, no en un archivo.")
        }
        if (type.needsBaseUrl) {
            OutlinedTextField(
                value = p.baseUrl.ifBlank { type.defaultBaseUrl.orEmpty() },
                onValueChange = { v -> updateProvider { it.copy(baseUrl = v.trim()) } },
                label = { Text(if (type == LlmProviderType.OLLAMA) "URL de Ollama" else "URL base (compatible OpenAI)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        if (type == LlmProviderType.ANTHROPIC) {
            VSpace()
            Dropdown(
                label = "Esfuerzo de razonamiento",
                options = listOf("", "low", "medium", "high"),
                selected = p.effort,
                display = {
                    when (it) {
                        "low" -> "Bajo (más rápido, recomendado para voz)"
                        "medium" -> "Medio"
                        "high" -> "Alto (más lento)"
                        else -> "Predeterminado del modelo"
                    }
                },
                onSelect = { v -> updateProvider { it.copy(effort = v) } },
            )
            Hint("Haiku es el más rápido; Opus, el más capaz para manejar la pantalla.")
        }
        Row(horizontalArrangement = RowSpacing, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            OutlinedButton(enabled = !testing, onClick = {
                val config = app.controller.llm.resolve(type)
                if (config == null) {
                    testResult = false to (app.controller.llm.missing(type) ?: "Faltan datos")
                    return@OutlinedButton
                }
                testing = true
                scope.launch {
                    testResult = LlmDiagnostics.testConnection(config).fold(
                        onSuccess = { true to it },
                        onFailure = { false to (it.message ?: it.toString()) },
                    )
                    testing = false
                }
            }) { Text(if (testing) Texts.TESTING else Texts.TEST_CONNECTION) }
            if (type == LlmProviderType.OLLAMA) {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { LlmDiagnostics.ollamaModels(p.baseUrl.ifBlank { type.defaultBaseUrl!! }) }
                            .onSuccess { ollamaModels = it; testResult = true to "Modelos instalados: ${it.joinToString().ifBlank { "ninguno" }}" }
                            .onFailure { testResult = false to "¿Está corriendo Ollama? ${it.message}" }
                    }
                }) { Text("Ver modelos instalados") }
            }
        }
        testResult?.let { (ok, msg) -> Hint(msg, if (ok) Green else Warn) }
    }
}

// ----------------------------------------------------------------------- Voz

@Composable
fun VoiceSection(app: AppContext) {
    val s by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<VoiceInfo>?>(null) }
    LaunchedEffect(Unit) { voices = app.controller.voices() }

    fun updateVoice(transform: (com.controlxia.windows.config.VoicePreferences) -> com.controlxia.windows.config.VoicePreferences) =
        app.settings.update { it.copy(voice = transform(it.voice)) }

    SectionLabel("Voz de Windows")
    val list = voices
    if (list == null) {
        Text("Cargando voces…", style = MaterialTheme.typography.bodySmall)
    } else {
        val options = listOf<VoiceInfo?>(null) + list
        Dropdown(
            label = "Voz",
            options = options,
            selected = list.firstOrNull { it.name == s.voice.voiceName },
            display = { it?.let { v -> "${v.name} · ${v.gender} · ${v.language}" } ?: "Predeterminada del sistema" },
            onSelect = { v -> updateVoice { it.copy(voiceName = v?.name.orEmpty()) } },
        )
        if (list.none { it.language.contains("spa", true) || it.language.contains("españ", true) }) {
            Hint("No hay voces en español instaladas. Agregalas en Configuración de Windows › Hora e idioma › Voz.", Warn)
        }
    }

    SectionLabel("Cómo suena")
    LabeledSlider("Velocidad", s.voice.rate, -10..10) { v -> updateVoice { it.copy(rate = v) } }
    LabeledSlider("Tono", s.voice.pitch, -10..10) { v -> updateVoice { it.copy(pitch = v) } }
    LabeledSlider("Volumen", s.voice.volume, 0..100, valueLabel = { "$it %" }) { v -> updateVoice { it.copy(volume = v) } }
    Button(onClick = { scope.launch { app.controller.testVoice() } }, modifier = Modifier.padding(top = 8.dp)) {
        Text("Probar voz")
    }
}

// ------------------------------------------------------------------ Permisos

@Composable
fun PermissionsSection(app: AppContext) {
    val s by app.settings.state.collectAsState()
    Hint("Elegí qué puede hacer el asistente sin preguntar (RF-18). Las acciones bloqueadas ni siquiera se le ofrecen al LLM.")
    ActionCategory.values().forEach { category ->
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(category.displayName, style = MaterialTheme.typography.titleSmall)
                Hint(category.description)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PermissionPolicy.values().forEach { policy ->
                    val selected = s.policy(category) == policy
                    val onClick = { app.settings.update { it.copy(permissions = it.permissions + (category to policy)) } }
                    if (selected) Button(onClick = onClick) { Text(policy.displayName, style = MaterialTheme.typography.labelSmall) }
                    else OutlinedButton(onClick = onClick) { Text(policy.displayName, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- Historial

@Composable
fun HistorySection(app: AppContext) {
    val s by app.settings.state.collectAsState()
    val entries by app.history.entries.collectAsState()
    val format = remember { DateTimeFormatter.ofPattern("dd/MM HH:mm") }

    SwitchRow("Guardar historial", "Se guarda solo en esta PC.", s.historyEnabled) { v ->
        app.settings.update { it.copy(historyEnabled = v) }
    }
    Row(horizontalArrangement = RowSpacing) {
        OutlinedButton(onClick = { app.history.clear() }, enabled = entries.isNotEmpty()) { Text("Borrar historial") }
    }
    if (entries.isEmpty()) {
        Hint("Todavía no hay pedidos.")
    }
    entries.groupConversations().asReversed().take(100).forEach { group ->
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            val first = Instant.ofEpochMilli(group.first().timestamp).atZone(ZoneId.systemDefault())
            Text(
                first.format(format) + if (group.size > 1) " · conversación de ${group.size} pedidos" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            group.forEach { e ->
                Text("Vos: ${e.command}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                Text("${s.agentName}: ${e.response}", style = MaterialTheme.typography.bodyMedium)
                e.actions.forEach { Text("→ $it", style = MaterialTheme.typography.labelSmall, color = Accent) }
            }
        }
        Hairline()
    }
}

// ---------------------------------------------------------------- Privacidad

@Composable
fun PrivacySection(app: AppContext) {
    SectionLabel("Qué sale de la PC")
    Text(
        "La palabra de activación y la transcripción corren en tu computadora. El audio no se guarda " +
            "ni se envía a ningún lado. Solo el texto de tu pedido viaja al proveedor de LLM que elegiste " +
            "(o a ningún lado si usás Ollama local). Si permitís «Ver la pantalla», las capturas también " +
            "se envían al LLM.",
        style = MaterialTheme.typography.bodyMedium,
    )
    SectionLabel("Dónde se guarda todo")
    Text(
        "Configuración e historial: ${AppPaths.dataDir.absolutePath}\n" +
            "Claves: Administrador de credenciales de Windows (ControlXIA/…).",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = RowSpacing, modifier = Modifier.padding(top = 12.dp)) {
        OutlinedButton(onClick = { runCatching { Desktop.getDesktop().open(AppPaths.dataDir) } }) { Text("Abrir carpeta") }
        OutlinedButton(onClick = { app.controller.forgetConversation() }) { Text(Texts.TRAY_FORGET) }
        OutlinedButton(onClick = {
            runCatching { Desktop.getDesktop().browse(URI("ms-settings:privacy-microphone")) }
        }) { Text("Permisos de micrófono") }
    }
}
