package com.controlxia.windows

import com.controlxia.brain.llm.LlmProviderType
import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.PermissionPolicy
import com.controlxia.windows.actions.AppEntry
import com.controlxia.windows.actions.AppTools
import com.controlxia.windows.actions.InfoTools
import com.controlxia.windows.actions.InputTools
import com.controlxia.windows.actions.TimerService
import com.controlxia.windows.actions.WebTools
import com.controlxia.windows.config.AppSettings
import com.controlxia.windows.config.FileSecretStore
import com.controlxia.windows.config.HistoryEntry
import com.controlxia.windows.config.HistoryStore
import com.controlxia.windows.config.groupConversations
import com.controlxia.windows.config.LlmConfigResolver
import com.controlxia.windows.config.ProviderSettings
import com.controlxia.windows.config.SecretStore
import com.controlxia.windows.config.SettingsRepository
import com.controlxia.windows.config.VoicePreferences
import com.controlxia.windows.platform.GlobalHotkey
import com.controlxia.windows.voice.SapiTextToSpeech
import com.controlxia.windows.voice.VoskModels
import com.controlxia.windows.voice.extractText
import com.controlxia.windows.voice.normalizeSpeech
import com.controlxia.windows.voice.parseYesNo
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WindowsLogicTest {

    private val tmp: File = Files.createTempDirectory("cxia").toFile()

    @Test
    fun `busca programas tolerando acentos y errores de transcripcion`() {
        val apps = listOf(
            AppEntry("Spotify", "SpotifyAB.SpotifyMusic!Spotify"),
            AppEntry("Bloc de notas", "notepad"),
            AppEntry("Google Chrome", "Chrome"),
            AppEntry("Calculadora", "calc"),
        )
        assertEquals("Spotify", AppTools.bestMatch("spotify", apps) { it.name }?.name)
        assertEquals("Spotify", AppTools.bestMatch("espotify", apps) { it.name }?.name)
        assertEquals("Bloc de notas", AppTools.bestMatch("el bloc de notas", apps) { it.name }?.name)
        assertEquals("Google Chrome", AppTools.bestMatch("chrome", apps) { it.name }?.name)
        assertEquals("Calculadora", AppTools.bestMatch("calculadora", apps) { it.name }?.name)
        assertNull(AppTools.bestMatch("photoshop", apps) { it.name })
    }

    @Test
    fun `parsea la salida de ConvertTo-Json con uno o varios objetos`() {
        assertEquals(1, AppTools.parseObjects("""{"Name":"A","AppID":"a"}""").size)
        assertEquals(2, AppTools.parseObjects("""[{"Name":"A"},{"Name":"B"}]""").size)
        assertEquals(0, AppTools.parseObjects("").size)
    }

    @Test
    fun `entiende si y no hablados`() {
        assertEquals(true, parseYesNo("Sí, dale"))
        assertEquals(true, parseYesNo("si"))
        assertEquals(true, parseYesNo("ok hacelo"))
        assertEquals(false, parseYesNo("no"))
        assertEquals(false, parseYesNo("mejor no, cancelá"))
        assertNull(parseYesNo("qué hora es"))
    }

    @Test
    fun `normaliza el habla`() {
        assertEquals("abri spotify", "¡Abrí Spotify!".normalizeSpeech())
        assertEquals("hola que tal", extractText("""{"text" : "hola que tal"}"""))
        assertEquals("ho", extractText("""{"partial" : "ho"}"""))
    }

    @Test
    fun `parsea atajos de teclado`() {
        assertEquals(0x0002 or 0x0001 to 'X'.code, GlobalHotkey.parse("Ctrl+Alt+X"))
        assertEquals(0x0008 to 0x70, GlobalHotkey.parse("win+F1"))
        assertNull(GlobalHotkey.parse("X"))
        assertNull(GlobalHotkey.parse("Ctrl+Foo"))
        assertEquals(listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_S), InputTools.parseKeys("ctrl+s"))
        assertEquals(listOf(KeyEvent.VK_WINDOWS, KeyEvent.VK_D), InputTools.parseKeys("Win + D"))
        assertNull(InputTools.parseKeys("ctrl+banana"))
    }

    @Test
    fun `arma urls seguras`() {
        assertEquals("https://youtube.com", WebTools.normalizeUrl("youtube.com"))
        assertNull(WebTools.normalizeUrl("file:///C:/Windows"))
        assertNull(WebTools.normalizeUrl("javascript:alert(1)"))
        assertTrue(WebTools.searchUrl("mate cocido", "youtube").contains("mate+cocido"))
    }

    @Test
    fun `xml de SAPI escapa el texto y aplica la voz`() {
        val xml = SapiTextToSpeech.buildXml("<b>Hola & chau</b>", VoicePreferences("Microsoft Sabina", rate = 3, pitch = -2, volume = 80))
        assertTrue(xml.startsWith("<voice optional=\"Name=Microsoft Sabina\">"))
        assertTrue(xml.contains("&lt;b&gt;Hola &amp; chau&lt;/b&gt;"))
        assertTrue(xml.contains("absspeed=\"3\"") && xml.contains("absmiddle=\"-2\"") && xml.contains("level=\"80\""))
    }

    @Test
    fun `frases de hora y fecha en castellano`() {
        val t = LocalDateTime.of(2026, 10, 12, 14, 5)
        assertEquals("Son las 14:05", InfoTools.timePhrase(t))
        assertEquals("Es la una y 30", InfoTools.timePhrase(t.withHour(1).withMinute(30)))
        assertEquals("Hoy es lunes 12 de octubre de 2026", InfoTools.datePhrase(t))
    }

    @Test
    fun `temporizadores vencen, se persisten y se cancelan`() {
        val scope = TestScope(StandardTestDispatcher())
        val file = File(tmp, "timers.json")
        val fired = mutableListOf<String>()
        val timers = TimerService(scope, file) { scope.testScheduler.currentTime }
        timers.onFire = { fired += it.label }
        timers.schedule("fideos", 60_000, isReminder = false)
        timers.schedule("ropa", 120_000, isReminder = true)
        assertTrue(file.readText().contains("fideos"))
        scope.advanceTimeBy(60_001); scope.runCurrent()
        assertEquals(listOf("fideos"), fired)
        assertEquals(1, timers.cancel("ropa"))
        scope.advanceTimeBy(120_000); scope.runCurrent()
        assertEquals(listOf("fideos"), fired)
        assertEquals("10 minutos", TimerService.durationPhrase(600))
        assertEquals("1 hora y 30 segundos", TimerService.durationPhrase(3630))
    }

    @Test
    fun `configuracion y claves se guardan por separado`() {
        val repo = SettingsRepository(File(tmp, "settings.json"))
        repo.update { it.copy(agentName = "Lola", permissions = mapOf(ActionCategory.POWER to PermissionPolicy.BLOCK)) }
        val reloaded = SettingsRepository(File(tmp, "settings.json")).current
        assertEquals("Lola", reloaded.agentName)
        assertEquals(PermissionPolicy.BLOCK, reloaded.policy(ActionCategory.POWER))
        assertEquals(PermissionPolicy.ASK, reloaded.policy(ActionCategory.CLOSE_APPS))

        val secrets = FileSecretStore(File(tmp, "secrets.properties"))
        val resolver = LlmConfigResolver(repo, secrets)
        assertEquals("Falta la API key", resolver.missing(LlmProviderType.ANTHROPIC))
        secrets.set(SecretStore.llmKey(LlmProviderType.ANTHROPIC), "sk-test")
        assertEquals("claude-opus-5-5", resolver.activeChain().single().model)
        assertTrue(!File(tmp, "settings.json").readText().contains("sk-test"))

        // Ollama no necesita clave y trae URL por defecto.
        repo.update { it.copy(llm = it.llm.copy(fallback = LlmProviderType.OLLAMA, providers = it.llm.providers + (LlmProviderType.OLLAMA to ProviderSettings(model = "llama3.2")))) }
        assertEquals(listOf("claude-opus-5-5", "llama3.2"), resolver.activeChain().map { it.model })
        assertEquals("http://localhost:11434", resolver.activeChain()[1].baseUrl)
    }

    @Test
    fun `historial local se puede borrar`() {
        val file = File(tmp, "history.jsonl")
        val store = HistoryStore(file)
        store.add(HistoryEntry(1, "qué hora es", "Son las 10"))
        assertEquals(1, HistoryStore(file).entries.value.size)
        store.clear()
        assertEquals(0, HistoryStore(file).entries.value.size)
    }

    @Test
    fun `descomprimir rechaza rutas que salen de la carpeta`() {
        val zip = File(tmp, "evil.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("../escape.txt")); it.write(1); it.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> { VoskModels.unzip(zip, File(tmp, "out").apply { mkdirs() }) }
    }

    @Test
    fun `valores por defecto de la v1`() {
        val s = AppSettings()
        assertEquals("Ctrl+Alt+X", s.hotkey)
        assertEquals(PermissionPolicy.ASK, s.policy(ActionCategory.INPUT_CONTROL))
        assertEquals(PermissionPolicy.ASK, s.policy(ActionCategory.POWER))
        assertEquals(PermissionPolicy.ALLOW, s.policy(ActionCategory.OPEN_APPS))
    }
}

class FolderAndAppAliasTest {
    @Test
    fun `reconoce carpetas conocidas aunque el modelo invente el formato`() {
        assertEquals("Personal", AppTools.knownFolder("documentos"))
        assertEquals("Personal", AppTools.knownFolder("la carpeta de documentos"))
        assertEquals("Personal", AppTools.knownFolder("explorer shell:MyDocuments"))
        assertEquals("Downloads", AppTools.knownFolder("Descargas"))
        assertEquals("My Pictures", AppTools.knownFolder("imágenes"))
        assertEquals("RecycleBinFolder", AppTools.knownFolder("papelera de reciclaje"))
        assertNull(AppTools.knownFolder("spotify"))
    }

    @Test
    fun `apps del sistema por su nombre en castellano`() {
        assertEquals("Explorador de archivos", AppTools.systemApp("el explorador de archivos")?.first)
        assertEquals("Explorador de archivos", AppTools.systemApp("explorer")?.first)
        assertEquals("Bloc de notas", AppTools.systemApp("Bloc de notas")?.first)
        assertNull(AppTools.systemApp("Spotify"))
    }
}

class ConversationTest {
    @Test
    fun `detecta cuando el usuario cierra la conversacion`() {
        assertTrue(com.controlxia.windows.voice.isClosingPhrase("Listo, gracias"))
        assertTrue(com.controlxia.windows.voice.isClosingPhrase("gracias"))
        assertTrue(com.controlxia.windows.voice.isClosingPhrase("nada más"))
        assertTrue(com.controlxia.windows.voice.isClosingPhrase("chau"))
        assertTrue(!com.controlxia.windows.voice.isClosingPhrase("listo, ahora abrí Spotify"))
        assertTrue(!com.controlxia.windows.voice.isClosingPhrase("qué hora es"))
    }

    @Test
    fun `agrupa el historial por conversacion`() {
        val e = { id: String, cmd: String -> com.controlxia.windows.config.HistoryEntry(0, cmd, "ok", conversationId = id) }
        val list = listOf(e("", "viejo"), e("a", "uno"), e("a", "dos"), e("b", "tres"), e("", "suelto1"), e("", "suelto2"))
        val grouped = list.groupConversations()
        assertEquals(listOf(1, 2, 1, 1, 1), grouped.map { it.size })
    }
}
