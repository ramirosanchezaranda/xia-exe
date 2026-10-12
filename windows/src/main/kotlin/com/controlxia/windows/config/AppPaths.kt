package com.controlxia.windows.config

import java.io.File

/** Carpetas de datos de la app: %APPDATA%\ControlXIA en Windows. */
object AppPaths {

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    val dataDir: File by lazy {
        val base = System.getenv("APPDATA")?.takeIf { isWindows }
            ?: (System.getProperty("user.home") + File.separator + ".config")
        File(base, "ControlXIA").apply { mkdirs() }
    }

    val settingsFile: File get() = File(dataDir, "settings.json")
    val historyFile: File get() = File(dataDir, "history.jsonl")
    val timersFile: File get() = File(dataDir, "timers.json")
    val modelsDir: File get() = File(dataDir, "models").apply { mkdirs() }
    val logFile: File get() = File(dataDir, "controlxia.log")
}
