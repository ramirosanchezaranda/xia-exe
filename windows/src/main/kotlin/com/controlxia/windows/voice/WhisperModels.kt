package com.controlxia.windows.voice

import com.controlxia.windows.config.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/** Modelos de Whisper local (formato ggml de whisper.cpp). */
enum class WhisperModel(val fileName: String, val displayName: String, val approxMb: Int) {
    BASE("ggml-base.bin", "Base · rápido, calidad media", 142),
    SMALL("ggml-small.bin", "Small · recomendado", 466),
    TURBO("ggml-large-v3-turbo-q5_0.bin", "Large v3 turbo · el mejor, necesita una PC potente", 547),
}

object WhisperModels {

    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    fun file(model: WhisperModel): File = File(AppPaths.modelsDir, model.fileName)

    fun isInstalled(model: WhisperModel): Boolean = file(model).let { it.isFile && hasGgmlMagic(it) }

    /** Descarga el modelo informando el progreso 0..1. */
    suspend fun download(model: WhisperModel, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = file(model)
        val part = File(target.parentFile, target.name + ".part")
        val connection = URI(BASE_URL + model.fileName).toURL().openConnection()
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: (model.approxMb * 1_000_000L)
        connection.getInputStream().use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress((done.toFloat() / total).coerceAtMost(0.99f))
                }
            }
        }
        if (!hasGgmlMagic(part)) {
            part.delete()
            throw IllegalStateException("El archivo descargado no es un modelo de Whisper válido")
        }
        target.delete()
        part.renameTo(target)
        onProgress(1f)
        target
    }

    /** Los modelos ggml empiezan con los bytes "lmgg" (0x67676d6c en little endian). */
    internal fun hasGgmlMagic(file: File): Boolean = runCatching {
        file.inputStream().use { it.readNBytes(4) }.contentEquals(byteArrayOf(0x6c, 0x6d, 0x67, 0x67))
    }.getOrDefault(false)
}
