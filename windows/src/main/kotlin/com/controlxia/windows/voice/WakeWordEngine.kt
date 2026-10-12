package com.controlxia.windows.voice

import ai.picovoice.porcupine.Porcupine
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Motor de palabra de activación (equivalente desktop del `WakeWordEngine`
 * de Android). En la PC el audio llega empujado por [com.controlxia.windows.audio.Microphone],
 * así que el motor solo procesa cuadros y avisa si detectó la palabra.
 */
interface WakeWordEngine : AutoCloseable {
    /** Procesa un cuadro de 512 muestras. Devuelve true al detectar la palabra. */
    fun process(frame: ShortArray): Boolean
}

/** Error de inicialización, con un mensaje para mostrar en la UI. */
class WakeWordException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Porcupine (D2). Con palabra de fábrica (en inglés: Jarvis, Computer…) o con
 * un modelo propio `.ppn` entrenado para Windows (ej. "Xia") más el modelo de
 * idioma español. Corre sin internet; el AccessKey se valida al iniciar.
 */
class PorcupineWakeWord(
    accessKey: String,
    builtInKeyword: String,
    customKeywordPath: String?,
    modelPath: String?,
    sensitivity: Float,
) : WakeWordEngine {

    private val porcupine: Porcupine

    init {
        if (accessKey.isBlank()) throw WakeWordException("Falta el AccessKey de Picovoice")
        try {
            val builder = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setSensitivity(sensitivity.coerceIn(0f, 1f))
            if (!customKeywordPath.isNullOrBlank()) {
                builder.setKeywordPath(customKeywordPath)
                if (!modelPath.isNullOrBlank()) builder.setModelPath(modelPath)
            } else {
                val keyword = runCatching { Porcupine.BuiltInKeyword.valueOf(builtInKeyword) }
                    .getOrDefault(Porcupine.BuiltInKeyword.JARVIS)
                builder.setBuiltInKeyword(keyword)
            }
            porcupine = builder.build()
        } catch (e: Exception) {
            throw WakeWordException("Porcupine no pudo iniciar: ${e.message}", e)
        }
        require(porcupine.frameLength == 512) { "Porcupine espera cuadros de ${porcupine.frameLength}" }
    }

    override fun process(frame: ShortArray): Boolean = porcupine.process(frame) >= 0

    override fun close() = porcupine.delete()

    companion object {
        val builtInKeywords: List<String> = Porcupine.BuiltInKeyword.values().map { it.name }
    }
}

/**
 * Plan B sin cuenta (D2): Vosk con una gramática restringida a la frase de
 * activación. Funciona con cualquier nombre ("Xia", "Lola"…) sin entrenar un
 * modelo, a cambio de algo más de CPU que Porcupine.
 */
class VoskWakeWord(model: Model, phrase: String) : WakeWordEngine {

    private val target = phrase.normalizeSpeech()
    private val recognizer = Recognizer(
        model,
        16_000f,
        "[\"${target.replace("\"", "")}\", \"[unk]\"]",
    )

    override fun process(frame: ShortArray): Boolean {
        val final = recognizer.acceptWaveForm(frame, frame.size)
        val json = if (final) recognizer.result else recognizer.partialResult
        val heard = extractText(json)
        if (heard.isNotEmpty() && heard.split(' ').joinToString(" ").contains(target)) {
            recognizer.reset()
            return true
        }
        return false
    }

    override fun close() = recognizer.close()
}
