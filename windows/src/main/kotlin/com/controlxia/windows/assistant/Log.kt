package com.controlxia.windows.assistant

import com.controlxia.windows.config.AppPaths
import java.time.LocalDateTime

/** Log mínimo a archivo (%APPDATA%\ControlXIA\controlxia.log) para diagnosticar en la PC del usuario. */
object Log {
    private const val MAX_BYTES = 1_000_000L

    @Synchronized
    fun i(message: String) = write("INFO", message, null)

    @Synchronized
    fun e(message: String, error: Throwable? = null) = write("ERROR", message, error)

    private fun write(level: String, message: String, error: Throwable?) {
        val line = "${LocalDateTime.now()} $level $message" + (error?.let { " — ${it::class.simpleName}: ${it.message}" } ?: "")
        System.err.println(line)
        runCatching {
            val f = AppPaths.logFile
            if (f.length() > MAX_BYTES) f.delete()
            f.appendText(line + "\n")
        }
    }
}
