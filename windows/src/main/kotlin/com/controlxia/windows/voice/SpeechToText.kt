package com.controlxia.windows.voice

import com.controlxia.windows.audio.AudioSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Transcripción (D3). Equivalente desktop del `SpeechToText` de Android: la
 * sesión es un [AudioSink] que recibe el audio del micrófono mientras dura.
 */
interface SpeechToText {
    /**
     * Abre una sesión de escucha. El que llama conecta la sesión al
     * micrófono y espera [SttSession.await].
     */
    fun newSession(onPartial: (String) -> Unit = {}): SttSession
}

interface SttSession : AudioSink, AutoCloseable {
    /** Audio grabado durante la sesión, para pasarlo a Whisper. */
    fun recordedAudio(): ShortArray

    /**
     * Espera el fin de la frase (silencio tras hablar). Devuelve "" si no se
     * dijo nada antes de [silenceTimeoutMs] o la frase superó [maxMs].
     */
    suspend fun await(silenceTimeoutMs: Long = 6_000, maxMs: Long = 20_000): String
}

/** Vosk offline: el audio no sale de la PC (RNF-02, RNF-04). */
class VoskSpeechToText(private val model: Model) : SpeechToText {
    override fun newSession(onPartial: (String) -> Unit): SttSession = VoskSession(model, onPartial)
}

private const val MAX_RECORDING_BYTES = 16_000 * 2 * 30

private class VoskSession(model: Model, private val onPartial: (String) -> Unit) : SttSession {

    private val recognizer = Recognizer(model, 16_000f)
    private val result = CompletableDeferred<String>()
    @Volatile private var lastPartial = ""
    @Volatile private var heardSomething = false
    private var closed = false
    private val recording = java.io.ByteArrayOutputStream()

    // El micrófono llama desde su hilo; close() puede llegar desde otro:
    // se sincroniza para no usar el reconocedor nativo ya liberado.
    @Synchronized
    override fun onFrame(frame: ShortArray) {
        if (closed || result.isCompleted) return
        // Se guarda el audio (hasta 30 s) para la transcripción final con Whisper.
        if (recording.size() < MAX_RECORDING_BYTES) {
            for (sample in frame) {
                recording.write(sample.toInt() and 0xFF)
                recording.write(sample.toInt() shr 8 and 0xFF)
            }
        }
        if (recognizer.acceptWaveForm(frame, frame.size)) {
            val text = extractText(recognizer.result)
            if (text.isNotEmpty()) result.complete(text)
        } else {
            val partial = extractText(recognizer.partialResult)
            if (partial.isNotEmpty() && partial != lastPartial) {
                lastPartial = partial
                heardSomething = true
                onPartial(partial)
            }
        }
    }

    override suspend fun await(silenceTimeoutMs: Long, maxMs: Long): String {
        // Primero se espera que el usuario empiece a hablar…
        val first = withTimeoutOrNull(silenceTimeoutMs) {
            while (!heardSomething && !result.isCompleted) kotlinx.coroutines.delay(50)
        }
        if (first == null) return ""
        // …y después el final de la frase.
        return withTimeoutOrNull(maxMs) { result.await() } ?: finalText()
    }

    @Synchronized
    override fun recordedAudio(): ShortArray {
        val bytes = recording.toByteArray()
        return ShortArray(bytes.size / 2) { i ->
            ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
    }

    @Synchronized
    private fun finalText(): String =
        if (closed) lastPartial else extractText(recognizer.finalResult).ifEmpty { lastPartial }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        recognizer.close()
    }
}
