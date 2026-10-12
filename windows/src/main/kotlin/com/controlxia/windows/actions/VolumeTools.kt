package com.controlxia.windows.actions

import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.intOrNull
import com.controlxia.brain.tools.schema
import com.controlxia.brain.tools.str
import com.controlxia.brain.tools.tool
import com.controlxia.windows.platform.PowerShellHost
import com.controlxia.windows.platform.Win32
import kotlin.math.roundToInt

/**
 * RF-13: volumen del sistema. Usa la API de audio de Windows (Core Audio) a
 * través del PowerShell persistente: el tipo se compila una sola vez.
 */
class VolumeTools(private val shell: PowerShellHost) {

    fun all(): List<Tool> = listOf(
        tool(
            "volume",
            "Cambia el volumen general de la PC: subir, bajar, fijar un nivel, silenciar o quitar el silencio.",
            ActionCategory.VOLUME,
            schema {
                string("action", "Qué hacer", required = true, enum = listOf("up", "down", "set", "mute", "unmute", "get"))
                integer("level", "Nivel 0-100 para 'set', o cuánto subir/bajar (por defecto 10)")
            },
        ) { args -> volume(args.str("action"), args.intOrNull("level")) },
        tool(
            "media_control",
            "Controla la música o el video que se está reproduciendo (Spotify, YouTube…).",
            ActionCategory.VOLUME,
            schema { string("action", "Qué hacer", required = true, enum = listOf("play_pause", "next", "previous")) },
        ) { args ->
            when (args.str("action")) {
                "next" -> { Win32.tapVirtualKey(Win32.VK_MEDIA_NEXT_TRACK); ToolResult("Siguiente") }
                "previous" -> { Win32.tapVirtualKey(Win32.VK_MEDIA_PREV_TRACK); ToolResult("Anterior") }
                else -> { Win32.tapVirtualKey(Win32.VK_MEDIA_PLAY_PAUSE); ToolResult("Listo") }
            }
        },
    )

    suspend fun volume(action: String, level: Int?): ToolResult {
        val current = run("[XiaAudio]::Volume").replace(',', '.').toFloatOrNull() ?: 0f
        val currentPct = (current * 100).roundToInt()
        return when (action) {
            "get" -> ToolResult("El volumen está en $currentPct por ciento")
            "mute" -> { run("[XiaAudio]::Mute = \$true"); ToolResult("Silenciado") }
            "unmute" -> { run("[XiaAudio]::Mute = \$false"); ToolResult("Volví a activar el sonido") }
            else -> {
                val target = when (action) {
                    "set" -> level ?: return ToolResult("Falta el nivel", isError = true)
                    "up" -> currentPct + (level ?: 10)
                    "down" -> currentPct - (level ?: 10)
                    else -> return ToolResult("Acción de volumen desconocida: $action", isError = true)
                }.coerceIn(0, 100)
                run("[XiaAudio]::Mute = \$false; [XiaAudio]::Volume = ${target / 100f}")
                ToolResult("Volumen al $target por ciento")
            }
        }
    }

    private suspend fun run(expression: String): String = shell.run(DEFINE_TYPE + "\n" + expression)

    private companion object {
        // Interfaz mínima de IAudioEndpointVolume (Core Audio). Los métodos
        // f, g, h… ocupan los lugares de la vtable que no se usan.
        val DEFINE_TYPE = """
            if (-not ('XiaAudio' -as [type])) {
            Add-Type -TypeDefinition @'
            using System.Runtime.InteropServices;
            [Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IAudioEndpointVolume {
              int f(); int g(); int h(); int i();
              int SetMasterVolumeLevelScalar(float fLevel, System.Guid pguidEventContext);
              int j();
              int GetMasterVolumeLevelScalar(out float pfLevel);
              int k(); int l(); int m(); int n();
              int SetMute([MarshalAs(UnmanagedType.Bool)] bool bMute, System.Guid pguidEventContext);
              int GetMute(out bool pbMute);
            }
            [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IMMDevice { int Activate(ref System.Guid id, int clsCtx, int activationParams, out IAudioEndpointVolume aev); }
            [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IMMDeviceEnumerator { int f(); int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint); }
            [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorComObject { }
            public class XiaAudio {
              static IAudioEndpointVolume Vol() {
                var enumerator = new MMDeviceEnumeratorComObject() as IMMDeviceEnumerator;
                IMMDevice dev = null;
                Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out dev));
                IAudioEndpointVolume epv = null;
                var epvid = typeof(IAudioEndpointVolume).GUID;
                Marshal.ThrowExceptionForHR(dev.Activate(ref epvid, 23, 0, out epv));
                return epv;
              }
              public static float Volume {
                get { float v = -1; Marshal.ThrowExceptionForHR(Vol().GetMasterVolumeLevelScalar(out v)); return v; }
                set { Marshal.ThrowExceptionForHR(Vol().SetMasterVolumeLevelScalar(value, System.Guid.Empty)); }
              }
              public static bool Mute {
                get { bool mute; Marshal.ThrowExceptionForHR(Vol().GetMute(out mute)); return mute; }
                set { Marshal.ThrowExceptionForHR(Vol().SetMute(value, System.Guid.Empty)); }
              }
            }
            '@
            }
        """.trimIndent()
    }
}
