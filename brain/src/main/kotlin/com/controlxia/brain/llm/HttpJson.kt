package com.controlxia.brain.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Cliente HTTP y JSON compartidos por todos los proveedores. */
object HttpJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /**
     * POST con body JSON. Devuelve el body parseado; ante un status de error
     * lanza [LlmException] con el mensaje real de la API (clave inválida,
     * modelo inexistente, etc.), que la UI muestra tal cual.
     */
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: JsonObject,
    ): JsonObject = execute(
        Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build(),
    )

    /** GET que devuelve JSON (listar modelos, probar conexión). */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): JsonObject = execute(
        Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build(),
    )

    /** Espera máxima para reintentar un 429; más que eso conviene pasar al respaldo. */
    private const val MAX_RETRY_WAIT_MS = 4_000L

    private suspend fun execute(request: Request, retried: Boolean = false): JsonObject {
        try {
            return executeOnce(request)
        } catch (e: LlmException) {
            val wait = e.retryAfterMs
            if (!retried && e.statusCode == 429 && wait != null && wait <= MAX_RETRY_WAIT_MS) {
                delay(wait + 250)
                return execute(request, retried = true)
            }
            throw e
        }
    }

    private suspend fun executeOnce(request: Request): JsonObject = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LlmException("HTTP ${response.code}: ${text.take(500)}", statusCode = response.code).apply {
                        retryAfterMs = retryAfter(response.header("retry-after"), text)
                    }
                }
                json.parseToJsonElement(text) as? JsonObject
                    ?: throw LlmException("Respuesta inesperada: ${text.take(200)}")
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Error de red: ${e.message}", e)
        }
    }
}

/**
 * Cuánto pide esperar el proveedor: header Retry-After (segundos) o el texto
 * "try again in 12.4s" que usan Groq y OpenAI.
 */
internal fun retryAfter(header: String?, body: String): Long? {
    header?.trim()?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
    val match = Regex("try again in ([0-9.]+)(ms|s)", RegexOption.IGNORE_CASE).find(body) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    return if (match.groupValues[2].lowercase() == "ms") value.toLong() else (value * 1000).toLong()
}

/** Contenido de un primitivo JSON, tratando `null` como ausente. */
internal val JsonElement?.stringOrNull: String?
    get() = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal val JsonElement?.arrayOrEmpty: List<JsonElement>
    get() = (this as? JsonArray) ?: emptyList()
