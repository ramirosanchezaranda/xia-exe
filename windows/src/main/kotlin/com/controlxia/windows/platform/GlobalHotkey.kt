package com.controlxia.windows.platform

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser

/**
 * Atajo de teclado global (RF-04), registrado con RegisterHotKey en un hilo
 * propio que atiende la cola de mensajes de Windows.
 */
class GlobalHotkey(private val onPressed: () -> Unit) {

    private var thread: Thread? = null
    @Volatile private var threadId = 0

    /**
     * Registra [combo] (ej. "Ctrl+Alt+X"). Devuelve un error legible si el
     * texto es inválido o si otra app ya usa esa combinación.
     */
    fun register(combo: String): String? {
        unregister()
        val parsed = parse(combo) ?: return "Atajo inválido: \"$combo\". Ejemplo: Ctrl+Alt+X"
        var error: String? = null
        val ready = java.util.concurrent.CountDownLatch(1)
        thread = Thread({
            threadId = Win32.currentThreadId()
            val ok = User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID, parsed.first or MOD_NOREPEAT, parsed.second)
            if (!ok) error = "No se pudo registrar $combo: otra aplicación ya lo usa."
            ready.countDown()
            if (!ok) return@Thread
            val msg = WinUser.MSG()
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WinUser.WM_HOTKEY && msg.wParam.toInt() == HOTKEY_ID) {
                    runCatching { onPressed() }
                }
            }
            User32.INSTANCE.UnregisterHotKey(null, HOTKEY_ID)
        }, "controlxia-hotkey").apply {
            isDaemon = true
            start()
        }
        ready.await()
        return error
    }

    fun unregister() {
        val id = threadId
        if (thread != null && id != 0) {
            User32.INSTANCE.PostThreadMessage(id, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
            thread?.join(1000)
        }
        thread = null
        threadId = 0
    }

    companion object {
        private const val HOTKEY_ID = 0x5841 // "XA"
        private const val MOD_ALT = 0x0001
        private const val MOD_CONTROL = 0x0002
        private const val MOD_SHIFT = 0x0004
        private const val MOD_WIN = 0x0008
        private const val MOD_NOREPEAT = 0x4000

        /** "Ctrl+Alt+X" → (modificadores, código de tecla virtual). */
        fun parse(combo: String): Pair<Int, Int>? {
            val parts = combo.split('+').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var mods = 0
            for (p in parts.dropLast(1)) {
                mods = mods or when (p) {
                    "ctrl", "control" -> MOD_CONTROL
                    "alt" -> MOD_ALT
                    "shift", "mayús", "mayus" -> MOD_SHIFT
                    "win", "windows" -> MOD_WIN
                    else -> return null
                }
            }
            if (mods == 0) return null
            val key = parts.last()
            val vk = when {
                key.length == 1 && key[0] in 'a'..'z' -> key[0].uppercaseChar().code
                key.length == 1 && key[0] in '0'..'9' -> key[0].code
                key.matches(Regex("f([1-9]|1[0-2])")) -> 0x70 + key.drop(1).toInt() - 1
                key == "space" || key == "espacio" -> 0x20
                else -> return null
            }
            return mods to vk
        }
    }
}
