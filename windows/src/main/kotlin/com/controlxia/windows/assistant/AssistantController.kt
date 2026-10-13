package com.controlxia.windows.assistant

import com.controlxia.brain.agent.Assistant
import com.controlxia.brain.tools.Confirmer
import com.controlxia.windows.actions.ScheduledAlert
import com.controlxia.windows.actions.TimerService
import com.controlxia.windows.actions.WindowsToolset
import com.controlxia.windows.audio.AudioSink
import com.controlxia.windows.audio.Chime
import com.controlxia.windows.audio.Microphone
import com.controlxia.windows.config.AppSettings
import com.controlxia.windows.config.HistoryEntry
import com.controlxia.windows.config.HistoryStore
import com.controlxia.windows.config.LlmConfigResolver
import com.controlxia.windows.config.SecretStore
import com.controlxia.windows.config.SettingsRepository
import com.controlxia.windows.config.WakeEngineType
import com.controlxia.windows.platform.Autostart
import com.controlxia.windows.platform.GlobalHotkey
import com.controlxia.windows.platform.PowerShellHost
import com.controlxia.windows.voice.PorcupineWakeWord
import com.controlxia.windows.voice.SapiTextToSpeech
import com.controlxia.windows.voice.SpeechToText
import com.controlxia.windows.voice.TextToSpeech
import com.controlxia.windows.voice.VoiceInfo
import com.controlxia.windows.voice.VoskModels
import com.controlxia.windows.voice.VoskSpeechToText
import com.controlxia.windows.voice.VoskWakeWord
import com.controlxia.windows.voice.WakeWordEngine
import com.controlxia.windows.voice.parseYesNo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fase de la interacción, para el ícono de la bandeja y el indicador flotante (RF-06). */
enum class Phase { STARTING, IDLE, PAUSED, LISTENING, THINKING, SPEAKING, CONFIRMING }

data class AssistantUi(
    val phase: Phase = Phase.STARTING,
    val agentName: String = "Xia",
    /** Lo que se va entendiendo del pedido. */
    val transcript: String = "",
    val response: String = "",
    val status: String = "",
    val confirmQuestion: String? = null,
    /** Estado de la palabra de activación: "Decí «Xia»" o el error. */
    val wakeStatus: String = "",
    val voiceInputReady: Boolean = false,
    val overlayVisible: Boolean = false,
)

/** Avisos para mostrar como notificación de Windows. */
data class Notice(val title: String, val message: String)

/**
 * Orquestador de la app: conecta micrófono → palabra de activación →
 * transcripción → cerebro → acción → voz, y publica el estado para la UI.
 * No depende de Compose, así se puede probar y reusar.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class AssistantController(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val history: HistoryStore,
) {
    val llm = LlmConfigResolver(settings, secrets)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shell = PowerShellHost()
    private val tts: TextToSpeech = SapiTextToSpeech(shell)
    private val ttsMutex = Mutex()
    private val mic = Microphone()
    private val hotkey = GlobalHotkey { activate() }
    val timers = TimerService(scope)
    private val toolset = WindowsToolset(shell, scope, timers)

    private var stt: SpeechToText? = null
    private val wakeSink = WakeSink { scope.launch { activate() } }

    private val _ui = MutableStateFlow(AssistantUi(agentName = settings.current.agentName))
    val ui: StateFlow<AssistantUi> = _ui.asStateFlow()

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    private var sessionJob: Job? = null
    private var hideJob: Job? = null
    @Volatile private var pendingConfirmation: CompletableDeferred<Boolean>? = null

    private val confirmer = Confirmer { question -> askConfirmation(question) }

    val assistant = Assistant(
        llmConfigs = { llm.activeChain() },
        tools = { toolset.all },
        permissions = { settings.current.policy(it) },
        confirmer = confirmer,
        systemPrompt = { systemPrompt(settings.current) },
    )

    fun start() {
        timers.onFire = ::onAlert
        timers.restore()
        scope.launch { toolset.apps.warmUp() }
        scope.launch(Dispatchers.IO) {
            runCatching { (tts as? SapiTextToSpeech)?.prewarm() }.onFailure { Log.e("Voz de Windows", it) }
        }
        applyHotkey()
        Autostart.apply(settings.current.autostart)?.let { Log.i(it) }
        reloadVoice()
        // Cambios de configuración que requieren rearmar la escucha.
        scope.launch {
            // Se espera a que el usuario termine de tipear antes de rearmar.
            settings.state.map { Triple(it.wake, it.voskModelPath, it.listeningPaused) }
                .distinctUntilChanged().drop(1).debounce(SETTINGS_DEBOUNCE_MS).collect { reloadVoice() }
        }
        scope.launch {
            settings.state.map { it.hotkey }.distinctUntilChanged().drop(1)
                .debounce(SETTINGS_DEBOUNCE_MS).collect { applyHotkey() }
        }
        scope.launch {
            settings.state.map { it.autostart }.distinctUntilChanged().drop(1).collect {
                Autostart.apply(it)?.let { msg -> _notices.tryEmit(Notice("Arranque automático", msg)) }
            }
        }
        scope.launch {
            settings.state.map { it.agentName }.distinctUntilChanged().collect { name ->
                _ui.update { it.copy(agentName = name) }
            }
        }
    }

    // ---------------------------------------------------------------- Escucha

    /** Rearma micrófono, palabra de activación y transcripción según la configuración. */
    fun reloadVoice() {
        scope.launch(Dispatchers.IO) {
            val s = settings.current
            wakeSink.replace(null)
            if (s.listeningPaused) {
                mic.stop()
                _ui.update { it.copy(phase = Phase.PAUSED, wakeStatus = "Escucha pausada") }
                return@launch
            }

            val modelDir = VoskModels.resolveDir(s.voskModelPath)
            val model = runCatching { if (VoskModels.isInstalled(modelDir)) VoskModels.load(modelDir) else null }
                .onFailure { Log.e("No se pudo cargar Vosk", it) }.getOrNull()
            stt = model?.let { VoskSpeechToText(it) }

            val (engine, status) = buildWakeEngine(s, model)
            wakeSink.replace(engine)
            val micError = runCatching { mic.sink = wakeSink; mic.start() }.exceptionOrNull()
            if (micError != null) Log.e("Micrófono", micError)

            _ui.update {
                it.copy(
                    phase = if (it.phase == Phase.STARTING || it.phase == Phase.PAUSED) Phase.IDLE else it.phase,
                    wakeStatus = micError?.let { e -> "Sin micrófono: ${e.message}" } ?: status,
                    voiceInputReady = stt != null && micError == null,
                )
            }
        }
    }

    private fun buildWakeEngine(s: AppSettings, model: org.vosk.Model?): Pair<WakeWordEngine?, String> {
        val hotkeyHint = "o tocá ${s.hotkey}"
        return try {
            when (s.wake.engine) {
                WakeEngineType.NONE -> null to "Activá con ${s.hotkey}"
                WakeEngineType.VOSK -> {
                    if (model == null) null to "Falta el modelo de voz: descargalo en Configuración"
                    else VoskWakeWord(model, s.wake.voskPhrase.ifBlank { s.agentName }) to
                        "Decí «${s.wake.voskPhrase.ifBlank { s.agentName }}» $hotkeyHint"
                }
                WakeEngineType.PORCUPINE -> {
                    val engine = PorcupineWakeWord(
                        accessKey = secrets.get(SecretStore.PORCUPINE_ACCESS_KEY),
                        builtInKeyword = s.wake.porcupineKeyword,
                        customKeywordPath = s.wake.customKeywordPath.ifBlank { null },
                        modelPath = s.wake.porcupineModelPath.ifBlank { null },
                        sensitivity = s.wake.sensitivity,
                    )
                    val word = if (s.wake.customKeywordPath.isNotBlank()) s.agentName
                    else s.wake.porcupineKeyword.lowercase().replace('_', ' ')
                    engine to "Decí «$word» $hotkeyHint"
                }
            }
        } catch (e: Exception) {
            Log.e("Palabra de activación", e)
            null to "${e.message} — usá ${s.hotkey}"
        }
    }

    private fun applyHotkey() {
        hotkey.register(settings.current.hotkey)?.let { error ->
            Log.e(error)
            _notices.tryEmit(Notice("Atajo de teclado", error))
        }
    }

    fun setPaused(paused: Boolean) = settings.update { it.copy(listeningPaused = paused) }

    // ------------------------------------------------------------ Interacción

    /** Palabra de activación o atajo: empieza a escuchar un pedido. */
    fun activate() {
        val phase = _ui.value.phase
        if (phase == Phase.CONFIRMING) return
        // RF-08: si está hablando o pensando, se calla y escucha.
        tts.stop()
        sessionJob?.cancel()
        hideJob?.cancel()
        val canListen = !settings.current.listeningPaused && stt != null && mic.isRunning
        if (!canListen) {
            _ui.update {
                it.copy(
                    overlayVisible = true, transcript = "", response = "", confirmQuestion = null,
                    phase = if (settings.current.listeningPaused) Phase.PAUSED else Phase.IDLE,
                    status = "Escribí tu pedido",
                )
            }
            return
        }
        sessionJob = scope.launch { voiceSession() }
    }

    /** Pedido escrito desde el indicador flotante. */
    fun submitText(text: String) {
        if (text.isBlank()) return
        tts.stop()
        sessionJob?.cancel()
        hideJob?.cancel()
        sessionJob = scope.launch { handleCommand(text.trim()) }
    }

    fun answerConfirmation(yes: Boolean) {
        pendingConfirmation?.complete(yes)
    }

    fun stopSpeaking() = tts.stop()

    /** Cierra el indicador y cancela lo que esté en curso. */
    fun dismiss() {
        pendingConfirmation?.complete(false)
        tts.stop()
        sessionJob?.cancel()
        _ui.update { it.copy(overlayVisible = false, phase = idlePhase(), status = "", confirmQuestion = null) }
    }

    fun forgetConversation() = assistant.memory.clear()

    private suspend fun voiceSession() {
        try {
            _ui.update {
                it.copy(
                    phase = Phase.LISTENING, overlayVisible = true, transcript = "", response = "",
                    status = "Escuchando…", confirmQuestion = null,
                )
            }
            Chime.listening()
            delay(200) // que el sonido no entre en la transcripción
            val text = listenOnce { partial -> _ui.update { it.copy(transcript = partial) } }
            if (text.isBlank()) {
                _ui.update { it.copy(phase = idlePhase(), status = "No te escuché") }
                scheduleHide()
                return
            }
            handleCommand(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Sesión de voz", e)
            _ui.update { it.copy(phase = idlePhase(), status = "Error: ${e.message}") }
            scheduleHide()
        }
    }

    private suspend fun handleCommand(text: String) {
        _ui.update {
            it.copy(
                phase = Phase.THINKING, overlayVisible = true, transcript = text,
                response = "", status = "Pensando…", confirmQuestion = null,
            )
        }
        val reply = assistant.process(text) { progress -> _ui.update { it.copy(status = progress) } }
        if (settings.current.historyEnabled) {
            history.add(
                HistoryEntry(
                    timestamp = System.currentTimeMillis(),
                    command = text,
                    response = reply.spoken,
                    actions = reply.actions.map { "${it.tool}: ${it.result} (${it.status})" },
                ),
            )
        }
        reply.error?.let { Log.e("Asistente: $it") }
        _ui.update { it.copy(phase = Phase.SPEAKING, response = reply.spoken, status = "") }
        say(reply.spoken)
        _ui.update { it.copy(phase = idlePhase()) }
        scheduleHide()
    }

    /** Escucha una frase con el micrófono compartido y devuelve el texto. */
    private suspend fun listenOnce(onPartial: (String) -> Unit = {}): String {
        val engine = stt ?: return ""
        val session = engine.newSession(onPartial)
        mic.sink = session
        return try {
            session.await()
        } finally {
            mic.sink = wakeSink
            session.close()
        }
    }

    private suspend fun askConfirmation(question: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingConfirmation = answer
        val previous = _ui.value.phase
        _ui.update { it.copy(phase = Phase.CONFIRMING, confirmQuestion = question, overlayVisible = true, status = "") }
        val listener = scope.launch {
            say(question)
            if (stt == null || !mic.isRunning || settings.current.listeningPaused) return@launch
            repeat(2) {
                val heard = listenOnce { partial -> _ui.update { it.copy(transcript = partial) } }
                when (parseYesNo(heard)) {
                    true -> { answer.complete(true); return@launch }
                    false -> { answer.complete(false); return@launch }
                    null -> if (heard.isNotBlank()) say("Decime sí o no.")
                }
            }
        }
        val result = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { answer.await() } ?: false
        listener.cancel()
        tts.stop()
        pendingConfirmation = null
        _ui.update { it.copy(phase = previous, confirmQuestion = null, status = if (result) "Confirmado" else "Cancelado") }
        return result
    }

    private suspend fun say(text: String) = ttsMutex.withLock {
        runCatching { tts.speak(text, settings.current.voice) }
            .onFailure { if (it is CancellationException) throw it else Log.e("Voz", it) }
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(HIDE_AFTER_MS)
            _ui.update { if (it.phase == Phase.IDLE || it.phase == Phase.PAUSED) it.copy(overlayVisible = false) else it }
        }
    }

    private fun idlePhase() = if (settings.current.listeningPaused) Phase.PAUSED else Phase.IDLE

    private suspend fun onAlert(alert: ScheduledAlert) {
        val message = if (alert.isReminder) "Recordatorio: ${alert.label}" else "Terminó el temporizador: ${alert.label}"
        _notices.emit(Notice(if (alert.isReminder) "Recordatorio" else "Temporizador", message))
        Chime.alarm()
        delay(1_500)
        if (_ui.value.phase == Phase.IDLE || _ui.value.phase == Phase.PAUSED) say(message)
    }

    // ------------------------------------------------------- Configuración

    suspend fun voices(): List<VoiceInfo> = runCatching { tts.voices() }.getOrElse {
        Log.e("Voces SAPI", it)
        emptyList()
    }

    suspend fun testVoice() = say("Hola, soy ${settings.current.agentName}. Así sueno con esta configuración.")

    fun shutdown() {
        hotkey.unregister()
        mic.stop()
        wakeSink.replace(null)
        tts.stop()
        (tts as? SapiTextToSpeech)?.close()
        shell.close()
        scope.cancel()
    }

    private fun systemPrompt(s: AppSettings): String {
        val now = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-AR")),
        )
        return """
            Sos ${s.agentName}, un asistente de voz que vive en una computadora con Windows. El usuario te
            habla en español rioplatense y vos respondés para ser escuchado en voz alta: frases cortas,
            naturales, sin markdown, listas ni emojis.

            Fecha y hora actual: $now.

            Cuando el usuario pida algo de la computadora (abrir o cerrar programas, abrir carpetas, páginas web, volumen,
            música, temporizadores, recordatorios, bloquear, apagar), usá la herramienta correspondiente.
            Para preguntas generales respondé vos mismo en una o dos frases. No inventes acciones que no
            tenés; si algo no se puede, decilo.

            Para manejar la pantalla con el mouse y el teclado: primero usá look_at_screen, ubicá el
            elemento en la captura y usá esas coordenadas en mouse_click; después de cada paso importante
            volvé a mirar la pantalla para comprobar. Si podés resolverlo con un atajo de teclado o con una
            herramienta directa (open_app, open_url), preferí eso. Nunca borres archivos ni envíes mensajes
            o compras sin que el usuario lo haya pedido explícitamente.

            Si una acción fue cancelada o bloqueada por el usuario, no la reintentes por otro camino.
            Al terminar, resumí en una frase lo que hiciste.
        """.trimIndent()
    }

    private companion object {
        const val CONFIRM_TIMEOUT_MS = 20_000L
        const val HIDE_AFTER_MS = 5_000L
        const val SETTINGS_DEBOUNCE_MS = 800L
    }
}

/**
 * Sink del micrófono para la palabra de activación. Sincroniza el reemplazo
 * del motor (al cambiar la configuración) con el procesamiento de cuadros.
 */
private class WakeSink(private val onDetected: () -> Unit) : AudioSink {
    private var engine: WakeWordEngine? = null
    private var lastDetection = 0L

    @Synchronized
    fun replace(next: WakeWordEngine?) {
        engine?.let { runCatching { it.close() } }
        engine = next
    }

    @Synchronized
    override fun onFrame(frame: ShortArray) {
        val e = engine ?: return
        val detected = runCatching { e.process(frame) }.getOrDefault(false)
        val now = System.currentTimeMillis()
        if (detected && now - lastDetection > 1_500) {
            lastDetection = now
            onDetected()
        }
    }
}
