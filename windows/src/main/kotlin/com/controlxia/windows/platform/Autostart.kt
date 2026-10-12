package com.controlxia.windows.platform

import com.controlxia.windows.config.AppPaths
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * Arranque con Windows (RF-01) mediante la clave Run del usuario actual: no
 * requiere permisos de administrador.
 */
object Autostart {

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE = "ControlXIA"

    /**
     * Comando de arranque: el .exe instalado por jpackage o, en la versión
     * portable, el lanzador .vbs. Null al correr desde Gradle.
     */
    val launchCommand: String?
        get() = System.getProperty("jpackage.app-path")?.let { "\"$it\" --tray" }
            ?: System.getProperty("controlxia.launcher")?.let { "wscript.exe \"$it\" --tray" }

    /** Aplica la preferencia. Devuelve un mensaje si no se pudo. */
    fun apply(enabled: Boolean): String? {
        if (!AppPaths.isWindows) return "Solo disponible en Windows"
        return runCatching {
            if (enabled) {
                val command = launchCommand ?: return "El arranque automático funciona con la app instalada"
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE, command)
            } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
            }
            null
        }.getOrElse { "No se pudo cambiar el arranque automático: ${it.message}" }
    }
}
