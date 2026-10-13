package com.controlxia.windows

import com.controlxia.brain.llm.LlmProviderType
import com.controlxia.windows.config.FileSecretStore
import com.controlxia.windows.config.LlmConfigResolver
import com.controlxia.windows.config.ProviderSettings
import com.controlxia.windows.config.SecretStore
import com.controlxia.windows.config.SettingsRepository
import com.controlxia.windows.voice.CloudWhisper
import com.controlxia.windows.voice.WhisperModels
import com.controlxia.windows.voice.isWhisperHallucination
import com.controlxia.windows.voice.wav
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptionTest {

    private val tmp: File = Files.createTempDirectory("cxia-stt").toFile()

    @Test
    fun `whisper en la nube manda el wav con modelo, idioma y pista`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"text":" podrías abrir la carpeta de documentos "}"""))
        server.start()
        val text = CloudWhisper(server.url("/openai/v1").toString(), "gsk_test", "whisper-large-v3-turbo")
            .transcribe(ShortArray(16_000), hint = "Xia")
        assertEquals("podrías abrir la carpeta de documentos", text)
        val request = server.takeRequest()
        assertEquals("/openai/v1/audio/transcriptions", request.path)
        assertEquals("Bearer gsk_test", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("whisper-large-v3-turbo"))
        assertTrue(body.contains("name=\"language\"") && body.contains("\r\n\r\nes\r\n"))
        assertTrue(body.contains("RIFF") && body.contains("WAVE"))
        assertTrue(body.contains("name=\"prompt\""))
        server.shutdown()
    }

    @Test
    fun `wav con encabezado correcto`() {
        val bytes = wav(ShortArray(100))
        assertEquals(44 + 200, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("data", String(bytes, 36, 4))
    }

    @Test
    fun `descarta frases inventadas por whisper`() {
        assertTrue(isWhisperHallucination("Subtítulos realizados por la comunidad de Amara.org"))
        assertTrue(isWhisperHallucination("¡Gracias por ver el video!"))
        assertTrue(isWhisperHallucination("   "))
        assertFalse(isWhisperHallucination("abrí Spotify"))
    }

    @Test
    fun `reusa la key de groq del cerebro para whisper`() {
        val repo = SettingsRepository(File(tmp, "s.json"))
        val secrets = FileSecretStore(File(tmp, "k.properties"))
        val resolver = LlmConfigResolver(repo, secrets)
        assertEquals("", resolver.sttCloudKey())
        repo.update {
            it.copy(llm = it.llm.copy(providers = it.llm.providers +
                (LlmProviderType.OPENAI_COMPATIBLE to ProviderSettings(model = "openai/gpt-oss-20b", baseUrl = "https://api.groq.com/openai/v1"))))
        }
        secrets.set(SecretStore.llmKey(LlmProviderType.OPENAI_COMPATIBLE), "gsk_groq")
        assertEquals("gsk_groq", resolver.sttCloudKey())
        secrets.set(SecretStore.STT_CLOUD_KEY, "propia")
        assertEquals("propia", resolver.sttCloudKey())
    }

    @Test
    fun `reconoce archivos de modelo ggml validos`() {
        val good = File(tmp, "ok.bin").apply { writeBytes(byteArrayOf(0x6c, 0x6d, 0x67, 0x67, 1, 2)) }
        val bad = File(tmp, "bad.bin").apply { writeText("<html>404</html>") }
        assertTrue(WhisperModels.hasGgmlMagic(good))
        assertFalse(WhisperModels.hasGgmlMagic(bad))
    }
}
