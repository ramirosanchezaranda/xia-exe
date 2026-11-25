package com.controlxia.windows

import com.controlxia.windows.actions.ScreenControl
import com.controlxia.windows.actions.ScreenSnapshot
import com.controlxia.windows.actions.Shortcuts
import com.controlxia.windows.config.FileSecretStore
import com.controlxia.windows.config.SettingsRepository
import com.controlxia.windows.mcp.McpCatalog
import com.controlxia.windows.mcp.McpImport
import com.controlxia.windows.mcp.McpManager
import com.controlxia.windows.mcp.McpRuntimes
import com.controlxia.windows.mcp.McpServerConfig
import com.controlxia.windows.mcp.McpSource
import com.controlxia.windows.mcp.McpStatus
import com.controlxia.windows.phone.PhoneAnswer
import com.controlxia.windows.phone.PhoneLink
import com.controlxia.windows.phone.PhoneProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.event.KeyEvent
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpAndPhoneTest {

    private val tmp: File = Files.createTempDirectory("cxia-mcp").toFile()

    @Test
    fun `el catalogo es coherente`() {
        val ids = McpCatalog.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        McpCatalog.entries.forEach { e ->
            assertTrue(e.command.isNotEmpty() || e.url.isNotBlank(), e.id)
            assertTrue(e.keywords.isNotEmpty(), e.id)
            if (e.command.isNotEmpty()) assertEquals(e.runtime.executable, e.command.first(), e.id)
        }
    }

    @Test
    fun `importa servidores de Claude Desktop separando los secretos`() {
        val json = """
            {"mcpServers": {
              "Spotify Player": {"command": "npx", "args": ["-y", "spotify-mcp"], "env": {"SPOTIFY_CLIENT_SECRET": "abc", "REGION": "ar"}},
              "figma-desktop": {"url": "http://127.0.0.1:3845/mcp"},
              "apagado": {"command": "x", "disabled": true}
            }}
        """.trimIndent()
        val imported = McpImport.parse(json, "Claude Desktop")
        assertEquals(2, imported.size)
        val spotify = imported.first { it.config.name == "Spotify Player" }
        assertEquals("imp_spotify_player", spotify.config.id)
        assertEquals(listOf("npx", "-y", "spotify-mcp"), spotify.config.command)
        assertEquals(mapOf("REGION" to "ar"), spotify.config.env)
        assertEquals(listOf("SPOTIFY_CLIENT_SECRET"), spotify.config.secretEnv)
        assertEquals(mapOf("SPOTIFY_CLIENT_SECRET" to "abc"), spotify.secrets)
        assertTrue("spotify" in spotify.config.keywords)
        assertEquals(McpSource.IMPORTED, spotify.config.source)
        assertTrue(imported.first { it.config.name == "figma-desktop" }.config.isHttp)
    }

    @Test
    fun `arma la linea de comando`() {
        assertEquals(listOf("npx", "-y", "paquete con espacio", "--flag"), McpServerConfig.splitCommandLine("npx -y \"paquete con espacio\" --flag"))
        val home = System.getProperty("user.home")
        assertEquals(listOf("x", File(home, "Documents").absolutePath), McpRuntimes.expandArgs(listOf("x", "{DOCUMENTS}")))
        McpRuntimes.find("python3")?.let { python ->
            assertEquals(python.absolutePath, McpRuntimes.commandLine(listOf("python3", "-V")).first())
        }
    }

    @Test
    fun `el administrador conecta un servidor, lo ofrece como grupo y llama sus herramientas`() = runBlocking {
        val python = McpRuntimes.find("python3") ?: McpRuntimes.find("python") ?: return@runBlocking
        val script = File(tmp, "server.py").apply {
            writeText(
                """
                import json, sys
                for line in sys.stdin:
                    msg = json.loads(line)
                    if "id" not in msg: continue
                    m = msg["method"]
                    if m == "initialize": r = {"protocolVersion": "2025-06-18", "serverInfo": {"name": "planillas", "version": "1"}}
                    elif m == "tools/list": r = {"tools": [{"name": "write_cell", "description": "Escribe una celda", "inputSchema": {"type": "object", "properties": {"value": {"type": "string"}}}}]}
                    elif m == "tools/call": r = {"content": [{"type": "text", "text": "escrito " + msg["params"]["arguments"]["value"]}]}
                    else: r = {}
                    print(json.dumps({"jsonrpc": "2.0", "id": msg["id"], "result": r}), flush=True)
                """.trimIndent(),
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = McpManager(SettingsRepository(File(tmp, "s.json")), FileSecretStore(File(tmp, "k.properties")), scope)
        try {
            val config = McpServerConfig("excel", "Excel", command = listOf(python.absolutePath, "-I", script.absolutePath), keywords = listOf("excel", "planilla"))
            assertEquals(1, manager.connect(config))
            assertTrue(manager.statuses.value["excel"] is McpStatus.Ready)
            val group = manager.toolGroups().single()
            assertEquals("mcp_excel", group.id)
            assertTrue("planilla" in group.keywords)
            val tool = group.tools.single()
            assertEquals("excel__write_cell", tool.spec.name)
            assertEquals("escrito 42", tool.run(buildJsonObject { put("value", "42") }).text)
        } finally {
            manager.stopAll()
            scope.cancel()
        }
    }

    @Test
    fun `falta un token y no arranca`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = McpManager(SettingsRepository(File(tmp, "s2.json")), FileSecretStore(File(tmp, "k2.properties")), scope)
        val error = runCatching { manager.connect(McpServerConfig.fromCatalog(McpCatalog.byId("notion")!!)) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("NOTION_TOKEN"))
        assertTrue(manager.statuses.value["notion"] is McpStatus.Failed)
        scope.cancel()
    }

    @Test
    fun `protocolo del telefono compatible con la app de Android`() {
        val code = "K7P2Q-9XQF3"
        // Mismo cálculo que PcProtocol.sign de la app: HMAC-SHA256 con el código limpio.
        val sig = PhoneProtocol.sign("k7p2q 9xqf3", "volume_up", "3", 1_700_000_000, "n1")
        assertEquals(PhoneProtocol.sign(code, "volume_up", "3", 1_700_000_000, "n1"), sig)
        assertEquals(64, sig.length)
        val req = PhoneProtocol.Request("volume_up", "3", 1_700_000_000, "n1", sig)
        assertNull(PhoneProtocol.reject(req, code, 1_700_000_050, emptyList()))
        assertEquals("codigo", PhoneProtocol.reject(req, "OTRO-CODIGO", 1_700_000_050, emptyList()))
        assertEquals("hora", PhoneProtocol.reject(req, code, 1_700_000_500, emptyList()))
        assertEquals("repetido", PhoneProtocol.reject(req, code, 1_700_000_050, listOf("n1")))
        assertTrue(Regex("[A-Z2-9]{5}-[A-Z2-9]{5}").matches(PhoneProtocol.newCode()))
        assertTrue(PhoneProtocol.announce("PC-LIVING", listOf("AA:BB:CC:DD:EE:FF")).contains("\"app\":\"controlxia-pc\""))
    }

    @Test
    fun `el telefono manda un pedido firmado y lo encuentra por UDP`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        val link = PhoneLink(scope, { "PC-TEST" }, { "ABCDE-23456" }) { cmd, arg ->
            received += "$cmd $arg"
            PhoneAnswer(true, "ok")
        }
        link.start()
        assertTrue(link.isRunning, link.status.value)
        try {
            val ts = System.currentTimeMillis() / 1000
            val line = """{"cmd":"mute","arg":"","ts":$ts,"nonce":"x1","sig":"${PhoneProtocol.sign("ABCDE-23456", "mute", "", ts, "x1")}"}"""
            fun send(): String = Socket("127.0.0.1", PhoneProtocol.TCP_PORT).use { s ->
                s.soTimeout = 5000
                s.getOutputStream().write((line + "\n").toByteArray())
                s.getInputStream().bufferedReader().readLine()
            }
            val reply = send()
            assertTrue(reply.contains("\"ok\":true"), reply)
            assertTrue(reply.contains("\"name\":\"PC-TEST\""))
            assertEquals(listOf("mute "), received.toList())
            assertTrue(send().contains("repetido"), "repetir el mismo pedido no vale")
            DatagramSocket().use { udp ->
                udp.soTimeout = 3000
                val ask = PhoneProtocol.DISCOVER.toByteArray()
                udp.send(DatagramPacket(ask, ask.size, InetAddress.getLoopbackAddress(), PhoneProtocol.UDP_PORT))
                val buf = ByteArray(1024)
                val packet = DatagramPacket(buf, buf.size)
                udp.receive(packet)
                assertTrue(String(packet.data, 0, packet.length).contains("PC-TEST"))
            }
        } finally {
            link.stop()
            scope.cancel()
        }
    }

    @Test
    fun `lista de la ventana y botones delicados`() {
        val e = ScreenControl.parseLine("3\tEnviar\tButton\t100\t200\t80\t30")
        assertNotNull(e)
        assertEquals("Enviar", e.name)
        assertTrue(ScreenControl.format(ScreenSnapshot("chrome", "Gmail", listOf(e))).contains("3. botón «Enviar»"))
        assertTrue(ScreenControl.isSensitiveLabel("Enviar"))
        assertTrue(ScreenControl.isSensitiveLabel("Finalizar compra"))
        assertTrue(ScreenControl.isSensitiveLabel("Eliminar archivo"))
        assertFalse(ScreenControl.isSensitiveLabel("Nueva pestaña"))
    }

    @Test
    fun `atajos segun el programa y el idioma de Office`() {
        assertEquals(listOf(KeyEvent.VK_CONTROL, 'N'.code), Shortcuts.keysFor("bold", "WINWORD", spanishOffice = true))
        assertEquals(listOf(KeyEvent.VK_CONTROL, 'B'.code), Shortcuts.keysFor("bold", "WINWORD", spanishOffice = false))
        assertEquals(listOf(KeyEvent.VK_CONTROL, 'B'.code), Shortcuts.keysFor("bold", "chrome", spanishOffice = true))
        assertEquals(listOf(KeyEvent.VK_SHIFT, KeyEvent.VK_F12), Shortcuts.keysFor("save", "EXCEL"))
        assertEquals(listOf(KeyEvent.VK_CONTROL, 'S'.code), Shortcuts.keysFor("save", "notepad"))
        assertEquals(listOf(KeyEvent.VK_CONTROL, 'T'.code), Shortcuts.keysFor("new_tab", "msedge"))
        assertNull(Shortcuts.keysFor("volar", "chrome"))
    }
}
