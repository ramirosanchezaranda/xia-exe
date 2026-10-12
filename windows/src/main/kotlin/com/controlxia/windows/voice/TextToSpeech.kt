package com.controlxia.windows.voice

import com.controlxia.windows.config.VoicePreferences
import com.controlxia.windows.platform.PowerShellHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.Base64

data class VoiceInfo(val name: String, val gender: String, val language: String)

interface TextToSpeech {
    /** Habla y vuelve cuando termina (o cuando se llama a [stop]). */
    suspend fun speak(text: String, prefs: VoicePreferences)

    /** Corta lo que se esté diciendo (RF-08). */
    fun stop()

    suspend fun voices(): List<VoiceInfo>
}

/**
 * Voz de Windows (D4) por SAPI. Cada frase la dice un proceso de PowerShell
 * ya "precalentado" con el objeto SAPI.SpVoice creado, así no se paga el
 * arranque; cortar al asistente es matar ese proceso. Voz, tono, velocidad y
 * volumen se aplican con marcas XML de SAPI en cada frase, de modo que los
 * cambios en la configuración valen desde la frase siguiente.
 */
class SapiTextToSpeech(private val shell: PowerShellHost) : TextToSpeech {

    @Volatile private var warm: Process? = null
    @Volatile private var speaking: Process? = null

    override suspend fun speak(text: String, prefs: VoicePreferences) {
        if (text.isBlank()) return
        val process = withContext(Dispatchers.IO) { takeWarmProcess() }
        speaking = process
        try {
            runInterruptible(Dispatchers.IO) {
                val xml = buildXml(text, prefs)
                process.outputStream.bufferedWriter(Charsets.US_ASCII).apply {
                    write(Base64.getEncoder().encodeToString(xml.toByteArray(Charsets.UTF_8)))
                    newLine()
                    flush()
                }
                process.waitFor()
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            speaking = null
            // Se prepara el siguiente para que la próxima frase arranque ya.
            withContext(Dispatchers.IO) { prewarm() }
        }
    }

    override fun stop() {
        speaking?.destroyForcibly()
    }

    override suspend fun voices(): List<VoiceInfo> {
        val out = shell.run(
            """
            ${'$'}v = New-Object -ComObject SAPI.SpVoice
            foreach (${'$'}t in ${'$'}v.GetVoices()) {
              ${'$'}t.GetAttribute('Name') + "`t" + ${'$'}t.GetAttribute('Gender') + "`t" + ${'$'}t.GetAttribute('Language')
            }
            """.trimIndent(),
        )
        return out.lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3 || parts[0].isBlank()) null
            else VoiceInfo(parts[0].trim(), genderName(parts[1].trim()), languageName(parts[2].trim()))
        }
    }

    @Synchronized
    fun prewarm() {
        if (warm?.isAlive == true) return
        val encoded = Base64.getEncoder().encodeToString(SPEAK_ONCE.toByteArray(Charsets.UTF_16LE))
        warm = ProcessBuilder(
            "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand", encoded,
        ).redirectErrorStream(true).start()
    }

    @Synchronized
    private fun takeWarmProcess(): Process {
        prewarm()
        val p = warm!!
        warm = null
        return p
    }

    fun close() {
        warm?.destroyForcibly()
        speaking?.destroyForcibly()
    }

    companion object {
        private val SPEAK_ONCE = """
            ${'$'}v = New-Object -ComObject SAPI.SpVoice
            ${'$'}line = [Console]::In.ReadLine()
            if (${'$'}line) {
              ${'$'}xml = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(${'$'}line))
              [void]${'$'}v.Speak(${'$'}xml, 8)
            }
        """.trimIndent()

        /** Arma el texto con marcas XML de SAPI 5 (SVSFIsXML). */
        fun buildXml(text: String, prefs: VoicePreferences): String {
            val escaped = text
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;")
            val voice = prefs.voiceName.replace("\"", "").replace(";", "")
            val body = "<volume level=\"${prefs.volume.coerceIn(0, 100)}\">" +
                "<rate absspeed=\"${prefs.rate.coerceIn(-10, 10)}\">" +
                "<pitch absmiddle=\"${prefs.pitch.coerceIn(-10, 10)}\">" +
                escaped +
                "</pitch></rate></volume>"
            return if (voice.isBlank()) body else "<voice optional=\"Name=$voice\">$body</voice>"
        }

        private fun genderName(raw: String) = when (raw.lowercase()) {
            "female" -> "Femenina"
            "male" -> "Masculina"
            else -> raw
        }

        // SAPI devuelve el código de idioma en hexadecimal (ej. "C0A" = es-ES).
        private fun languageName(raw: String): String {
            val lcid = raw.split(';').first().toIntOrNull(16) ?: return raw
            return runCatching {
                java.util.Locale.forLanguageTag(LCID_TAGS[lcid] ?: return raw).displayName
            }.getOrDefault(raw)
        }

        private val LCID_TAGS = mapOf(
            0x0C0A to "es-ES", 0x040A to "es-ES", 0x080A to "es-MX", 0x2C0A to "es-AR",
            0x0409 to "en-US", 0x0809 to "en-GB", 0x0416 to "pt-BR",
        )
    }
}
