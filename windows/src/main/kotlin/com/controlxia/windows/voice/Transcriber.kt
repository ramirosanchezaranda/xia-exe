package com.controlxia.windows.voice

import com.controlxia.brain.llm.HttpJson
import io.github.givimad.whisperjni.WhisperContext
import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperSamplingStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Transcripción final de una frase ya grabada. Vosk sigue haciendo la
 * palabra de activación, el texto en vivo y la detección de fin de frase; el
 * audio de la frase se pasa por acá para obtener un texto mucho más preciso.
 */
interface Transcriber {
    val label: String

    /** [pcm]: 16 kHz, 16 bits, mono. [hint]: palabras que ayudan (ej. el nombre del asistente). */
    suspend fun transcribe(pcm: ShortArray, hint: String? = null): String
}

class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Whisper en la nube con la API de transcripción compatible con OpenAI:
 * Groq (whisper-large-v3-turbo, por defecto) u OpenAI (whisper-1,
 * gpt-4o-mini-transcribe). Solo se envía el audio de la frase dicha.
 */
class CloudWhisper(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val language: String = "es",
) : Transcriber {

    private val url = baseUrl.trim().trimEnd('/') + "/audio/transcriptions"
    override val label = "Whisper en la nube ($model)"

    override suspend fun transcribe(pcm: ShortArray, hint: String?): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "frase.wav", wav(pcm).toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("language", language)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .apply { if (!hint.isNullOrBlank()) addFormDataPart("prompt", hint) }
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw TranscriptionException("HTTP ${response.code}: ${text.take(300)}")
                val json = HttpJson.json.parseToJsonElement(text) as? JsonObject
                    ?: throw TranscriptionException("Respuesta inesperada: ${text.take(200)}")
                (json["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
            }
        } catch (e: TranscriptionException) {
            throw e
        } catch (e: Exception) {
            throw TranscriptionException("Error de red: ${e.message}", e)
        }
    }

    private companion object {
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Whisper local (whisper.cpp vía JNI): nada sale de la PC. El modelo se carga
 * una vez y queda en memoria.
 */
class LocalWhisper(private val modelFile: File, private val language: String = "es") : Transcriber {

    override val label = "Whisper local (${modelFile.name})"
    private var whisper: WhisperJNI? = null
    private var context: WhisperContext? = null

    override suspend fun transcribe(pcm: ShortArray, hint: String?): String = withContext(Dispatchers.Default) {
        synchronized(this@LocalWhisper) {
            val (jni, ctx) = ensureLoaded()
            val params = WhisperFullParams(WhisperSamplingStrategy.GREEDY).apply {
                language = this@LocalWhisper.language
                translate = false
                noTimestamps = true
                singleSegment = false
                printProgress = false
                printRealtime = false
                printTimestamps = false
                suppressBlank = true
                nThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
                if (!hint.isNullOrBlank()) initialPrompt = hint
            }
            val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
            val result = jni.full(ctx, params, samples, samples.size)
            if (result != 0) throw TranscriptionException("Whisper local devolvió el código $result")
            (0 until jni.fullNSegments(ctx)).joinToString(" ") { jni.fullGetSegmentText(ctx, it).trim() }.trim()
        }
    }

    private fun ensureLoaded(): Pair<WhisperJNI, WhisperContext> {
        val ctx = context
        val jni = whisper
        if (ctx != null && jni != null) return jni to ctx
        if (!modelFile.isFile) throw TranscriptionException("Falta el modelo de Whisper: ${modelFile.absolutePath}")
        try {
            WhisperJNI.loadLibrary()
            val newJni = WhisperJNI()
            val newCtx = newJni.init(modelFile.toPath())
                ?: throw TranscriptionException("No se pudo cargar ${modelFile.name}")
            whisper = newJni
            context = newCtx
            return newJni to newCtx
        } catch (e: TranscriptionException) {
            throw e
        } catch (e: Throwable) {
            throw TranscriptionException("No se pudo iniciar Whisper local: ${e.message}", e)
        }
    }

    @Synchronized
    fun close() {
        context?.let { ctx -> whisper?.free(ctx) }
        context = null
    }
}

/** Arma un WAV PCM 16 kHz mono en memoria. */
internal fun wav(pcm: ShortArray, sampleRate: Int = 16_000): ByteArray {
    val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    pcm.forEach { data.putShort(it) }
    val out = ByteArrayOutputStream()
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size * 2); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
        putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.size * 2)
    }
    out.write(header.array())
    out.write(data.array())
    return out.toByteArray()
}

/**
 * Whisper a veces "alucina" frases típicas de subtítulos cuando el audio es
 * casi silencio. Si sale una de esas, se prefiere el texto de Vosk.
 */
fun isWhisperHallucination(text: String): Boolean {
    val t = text.normalizeSpeech()
    return t.isEmpty() || HALLUCINATIONS.any { t.contains(it) }
}

private val HALLUCINATIONS = listOf(
    "subtitulos realizados por", "subtitulado por", "amara org", "gracias por ver el video",
    "suscribete al canal", "no olvides suscribirte", "www ", "musica de fondo",
)
