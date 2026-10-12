package com.controlxia.windows.platform

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser

/** Llamadas sueltas a Win32 que no justifican una clase propia. */
object Win32 {

    /** Bloquea la sesión (como Win+L). */
    fun lockWorkstation(): Boolean = User32.INSTANCE.LockWorkStation().booleanValue()

    /** Apaga los monitores; se prenden al mover el mouse o tocar una tecla. */
    fun monitorsOff() {
        val broadcast = WinDef.HWND(Pointer.createConstant(0xFFFF))
        User32.INSTANCE.PostMessage(
            broadcast, WM_SYSCOMMAND, WinDef.WPARAM(SC_MONITORPOWER.toLong()), WinDef.LPARAM(2),
        )
    }

    /** Escribe texto con eventos Unicode: respeta acentos y no toca el portapapeles. */
    fun typeUnicode(text: String) {
        for (ch in text) {
            when (ch) {
                '\n' -> tapVirtualKey(VK_RETURN)
                '\r' -> Unit
                else -> {
                    sendUnicode(ch, keyUp = false)
                    sendUnicode(ch, keyUp = true)
                }
            }
        }
    }

    private fun sendUnicode(ch: Char, keyUp: Boolean) =
        sendKeyboard(vk = 0, scan = ch.code, flags = KEYEVENTF_UNICODE or if (keyUp) KEYEVENTF_KEYUP else 0)

    /** Presiona y suelta una tecla virtual (teclas multimedia, Enter…). */
    fun tapVirtualKey(vk: Int) {
        sendKeyboard(vk = vk, scan = 0, flags = 0)
        sendKeyboard(vk = vk, scan = 0, flags = KEYEVENTF_KEYUP)
    }

    private fun sendKeyboard(vk: Int, scan: Int, flags: Int) {
        val input = WinUser.INPUT()
        input.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
        input.input.setType("ki")
        input.input.ki.wVk = WinDef.WORD(vk.toLong())
        input.input.ki.wScan = WinDef.WORD(scan.toLong())
        input.input.ki.dwFlags = WinDef.DWORD(flags.toLong())
        input.input.ki.time = WinDef.DWORD(0)
        input.input.ki.dwExtraInfo = BaseTSD.ULONG_PTR(0)
        User32.INSTANCE.SendInput(WinDef.DWORD(1), arrayOf(input), input.size())
    }

    fun currentThreadId(): Int = Kernel32.INSTANCE.GetCurrentThreadId()

    const val WM_SYSCOMMAND = 0x0112
    const val SC_MONITORPOWER = 0xF170
    const val KEYEVENTF_KEYUP = 0x0002
    const val KEYEVENTF_UNICODE = 0x0004
    const val VK_RETURN = 0x0D
    const val VK_VOLUME_MUTE = 0xAD
    const val VK_VOLUME_DOWN = 0xAE
    const val VK_VOLUME_UP = 0xAF
    const val VK_MEDIA_NEXT_TRACK = 0xB0
    const val VK_MEDIA_PREV_TRACK = 0xB1
    const val VK_MEDIA_PLAY_PAUSE = 0xB3
}
