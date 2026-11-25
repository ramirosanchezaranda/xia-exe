package com.controlxia.windows.mcp

import com.controlxia.windows.config.AppPaths
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Encuentra e instala lo que hace falta para correr servidores MCP: Node.js
 * (npx) y uv (uvx). Se instalan con winget, que viene con Windows 10 y 11.
 */
object McpRuntimes {

    /**
     * PATH actual del sistema (usuario + máquina), leído del registro: si
     * algo se instaló con la app abierta, el PATH del proceso quedó viejo.
     */
    fun freshPath(): String {
        val current = System.getenv("PATH").orEmpty()
        if (!AppPaths.isWindows) return current
        val machine = runCatching {
            Advapi32Util.registryGetExpandableStringValue(
                WinReg.HKEY_LOCAL_MACHINE, "SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment", "Path",
            )
        }.getOrNull().orEmpty()
        val user = runCatching { Advapi32Util.registryGetExpandableStringValue(WinReg.HKEY_CURRENT_USER, "Environment", "Path") }
            .recoverCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, "Environment", "Path") }
            .getOrNull().orEmpty()
        val extra = listOfNotNull(
            System.getenv("LOCALAPPDATA")?.let { "$it\\Microsoft\\WinGet\\Links" },
            System.getenv("USERPROFILE")?.let { "$it\\.local\\bin" },
            System.getenv("ProgramFiles")?.let { "$it\\nodejs" },
            System.getenv("APPDATA")?.let { "$it\\npm" },
        )
        return (listOf(expand(machine), expand(user), current) + extra)
            .flatMap { it.split(File.pathSeparatorChar) }
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString(File.pathSeparator)
    }

    private fun expand(value: String): String =
        Regex("%([^%]+)%").replace(value) { m -> System.getenv(m.groupValues[1]) ?: m.value }

    /** Ruta completa de [command] en [path] (con .exe/.cmd en Windows), o null. */
    fun find(command: String, path: String = freshPath()): File? {
        if (command.isBlank()) return null
        val direct = File(command)
        if (direct.isAbsolute && direct.isFile) return direct
        val exts = if (AppPaths.isWindows) listOf(".exe", ".cmd", ".bat", "") else listOf("")
        for (dir in path.split(File.pathSeparatorChar)) {
            for (ext in exts) {
                val f = File(dir, command + ext)
                if (f.isFile) return f
            }
        }
        return null
    }

    fun isAvailable(runtime: McpRuntime): Boolean = runtime == McpRuntime.NONE || find(runtime.executable) != null

    /** Línea de comando lista para ejecutar. En Windows, `npx` es un .cmd: hay que pasarlo por cmd.exe. */
    fun commandLine(command: List<String>, path: String = freshPath()): List<String> {
        if (command.isEmpty()) return command
        val exe = find(command.first(), path) ?: return command
        val name = exe.name.lowercase()
        return if (AppPaths.isWindows && (name.endsWith(".cmd") || name.endsWith(".bat"))) {
            listOf("cmd.exe", "/d", "/s", "/c", exe.absolutePath) + command.drop(1)
        } else {
            listOf(exe.absolutePath) + command.drop(1)
        }
    }

    /** Reemplaza las carpetas del usuario en los argumentos. */
    fun expandArgs(command: List<String>): List<String> {
        val home = System.getProperty("user.home")
        val map = mapOf(
            "{DOCUMENTS}" to File(home, "Documents").absolutePath,
            "{DESKTOP}" to File(home, "Desktop").absolutePath,
            "{DOWNLOADS}" to File(home, "Downloads").absolutePath,
        )
        return command.map { arg -> map.entries.fold(arg) { acc, (k, v) -> acc.replace(k, v) } }
    }

    /**
     * Instala [runtime] con winget. Puede abrir el aviso de permisos de
     * Windows (Node.js se instala para toda la PC). Devuelve null si quedó
     * instalado, o el problema en palabras.
     */
    suspend fun install(runtime: McpRuntime, onStatus: (String) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        if (runtime == McpRuntime.NONE || isAvailable(runtime)) return@withContext null
        if (!AppPaths.isWindows) return@withContext "Solo se puede instalar automáticamente en Windows"
        val winget = find("winget") ?: return@withContext "No encontré winget. Instalá ${runtime.displayName} desde ${runtime.downloadUrl}"
        onStatus("Instalando ${runtime.displayName}…")
        val process = ProcessBuilder(
            winget.absolutePath, "install", "--id", runtime.wingetId, "--exact", "--silent",
            "--accept-source-agreements", "--accept-package-agreements", "--disable-interactivity",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return@withContext "La instalación de ${runtime.displayName} tardó demasiado"
        }
        if (isAvailable(runtime)) null
        else "No se pudo instalar ${runtime.displayName} (${output.lines().lastOrNull { it.isNotBlank() }?.take(160) ?: "sin detalle"}). " +
            "Instalalo desde ${runtime.downloadUrl} y volvé a intentar."
    }
}
