package com.controlxia.windows.voice

import com.controlxia.windows.config.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * Descarga y carga del modelo de Vosk en español (~40 MB). Se baja una sola
 * vez a %APPDATA%\ControlXIA\models y después funciona sin internet.
 */
object VoskModels {

    const val DEFAULT_NAME = "vosk-model-small-es-0.42"
    const val DEFAULT_URL = "https://alphacephei.com/vosk/models/$DEFAULT_NAME.zip"

    val defaultDir: File get() = File(AppPaths.modelsDir, DEFAULT_NAME)

    private var cached: Pair<String, Model>? = null

    fun resolveDir(customPath: String): File =
        if (customPath.isNotBlank()) File(customPath) else defaultDir

    fun isInstalled(dir: File): Boolean = File(dir, "am").isDirectory || File(dir, "conf").isDirectory

    /** Carga (una vez) el modelo de [dir]. */
    @Synchronized
    fun load(dir: File): Model {
        cached?.let { (path, model) -> if (path == dir.absolutePath) return model }
        if (!isInstalled(dir)) throw IllegalStateException("No está el modelo de voz en ${dir.absolutePath}")
        runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
        val model = Model(dir.absolutePath)
        cached?.second?.close()
        cached = dir.absolutePath to model
        return model
    }

    /** Descarga y descomprime el modelo por defecto, informando el progreso 0..1. */
    suspend fun download(onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = AppPaths.modelsDir
        val zip = File(target, "$DEFAULT_NAME.zip.part")
        val connection = URI(DEFAULT_URL).toURL().openConnection()
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: 40_000_000L
        connection.getInputStream().use { input ->
            zip.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
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
        unzip(zip, target)
        zip.delete()
        onProgress(1f)
        defaultDir
    }

    internal fun unzip(zip: File, target: File) {
        val root = target.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(root, entry.name).canonicalFile
                // Protección contra rutas que salen de la carpeta ("zip slip").
                require(out.toPath().startsWith(root.toPath())) { "Entrada inválida en el zip: ${entry.name}" }
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }
}
