package com.controlxia.windows.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.Base64

/**
 * Proceso de PowerShell persistente: se arranca una vez y recibe scripts por
 * stdin, así cada acción (volumen, listar ventanas, suspender…) no paga el
 * arranque de PowerShell. Los scripts y las salidas viajan en base64 para no
 * pelear con comillas ni acentos.
 */
class PowerShellHost {

    private val mutex = Mutex()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** Ejecuta [script] y devuelve su salida como texto. Lanza si el script falla. */
    suspend fun run(script: String, timeoutMs: Long = 20_000): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                withTimeout(timeoutMs) { exchange(script) }
            } catch (e: Exception) {
                // Si se colgó o murió, se reinicia en el próximo pedido.
                if (e !is PowerShellException) restart()
                throw e
            }
        }
    }

    private fun exchange(script: String): String {
        ensureStarted()
        val w = writer!!
        val r = reader!!
        w.write(Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8)))
        w.newLine()
        w.flush()
        val payload = r.readLine() ?: throw IllegalStateException("PowerShell terminó inesperadamente")
        val status = r.readLine()
        val text = String(Base64.getDecoder().decode(payload.trim()), Charsets.UTF_8).trim()
        if (status != "OK") throw PowerShellException(text)
        return text
    }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        val encoded = Base64.getEncoder().encodeToString(LOOP.toByteArray(Charsets.UTF_16LE))
        val p = ProcessBuilder(
            "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand", encoded,
        ).redirectErrorStream(false).start()
        process = p
        writer = p.outputStream.bufferedWriter(Charsets.US_ASCII)
        reader = p.inputStream.bufferedReader(Charsets.US_ASCII)
        // stderr se descarta para que no se llene el buffer y bloquee.
        Thread { runCatching { p.errorStream.copyTo(java.io.OutputStream.nullOutputStream()) } }
            .apply { isDaemon = true }.start()
    }

    @Synchronized
    fun restart() {
        process?.destroyForcibly()
        process = null
    }

    fun close() = restart()

    private companion object {
        // Bucle de lectura: un script en base64 por línea; responde la salida
        // en base64 y una línea OK/ERR.
        val LOOP = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}ProgressPreference = 'SilentlyContinue'
            while (${'$'}true) {
              ${'$'}line = [Console]::In.ReadLine()
              if (${'$'}line -eq ${'$'}null) { break }
              try {
                ${'$'}script = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(${'$'}line))
                ${'$'}out = (Invoke-Expression ${'$'}script | Out-String)
                ${'$'}status = 'OK'
              } catch {
                ${'$'}out = ${'$'}_.Exception.Message
                ${'$'}status = 'ERR'
              }
              [Console]::Out.WriteLine([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]${'$'}out)))
              [Console]::Out.WriteLine(${'$'}status)
              [Console]::Out.Flush()
            }
        """.trimIndent()
    }
}

class PowerShellException(message: String) : Exception(message)

/** Escapa un texto para meterlo entre comillas simples en PowerShell. */
fun psQuote(text: String): String = "'" + text.replace("'", "''") + "'"
