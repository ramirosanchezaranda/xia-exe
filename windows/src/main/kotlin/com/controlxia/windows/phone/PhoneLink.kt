package com.controlxia.windows.phone

import com.controlxia.brain.llm.HttpJson
import com.controlxia.windows.assistant.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * El protocolo con el que la app ControlXIA del teléfono maneja las compus
 * (el mismo que `pc/ControlXIA-PC.ps1` del repo de Android), sin Windows:
 *
 * - Descubrimiento: "CONTROLXIA?" por UDP 48301 → JSON con nombre, puerto y MAC.
 * - Pedidos: una línea JSON por TCP 48300 firmada con el código de
 *   vinculación (HMAC-SHA256 de "cmd|arg|ts|nonce"), con hora y número
 *   único para que nadie de la red pueda mandar órdenes ni repetir una vieja.
 */
object PhoneProtocol {
    const val TCP_PORT = 48300
    const val UDP_PORT = 48301
    const val DISCOVER = "CONTROLXIA?"
    const val MAX_CLOCK_SKEW_S = 120

    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Código nuevo como el del script: "K7P2Q-9XQF3". */
    fun newCode(random: SecureRandom = SecureRandom()): String {
        val raw = (1..10).map { CODE_CHARS[random.nextInt(CODE_CHARS.length)] }.joinToString("")
        return raw.take(5) + "-" + raw.drop(5)
    }

    fun cleanCode(code: String): String = code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun sign(code: String, cmd: String, arg: String, ts: Long, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(cleanCode(code).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$cmd|$arg|$ts|$nonce".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    data class Request(val cmd: String, val arg: String, val ts: Long, val nonce: String, val sig: String)

    fun parse(line: String): Request? = runCatching {
        val obj = HttpJson.json.parseToJsonElement(line) as JsonObject
        fun s(k: String) = (obj[k] as? JsonPrimitive)?.content.orEmpty()
        Request(s("cmd"), s("arg"), s("ts").toLong(), s("nonce"), s("sig"))
    }.getOrNull()

    /** Por qué se rechaza un pedido ("codigo", "hora", "repetido"), o null si vale. */
    fun reject(request: Request, code: String, nowSeconds: Long, seen: Collection<String>): String? = when {
        !sign(code, request.cmd, request.arg, request.ts, request.nonce).equals(request.sig, ignoreCase = true) -> "codigo"
        kotlin.math.abs(nowSeconds - request.ts) > MAX_CLOCK_SKEW_S -> "hora"
        request.nonce.isBlank() || request.nonce in seen -> "repetido"
        else -> null
    }

    fun reply(ok: Boolean, msg: String, name: String, macs: List<String>): String = buildJsonObject {
        put("ok", ok)
        put("msg", msg)
        put("name", name)
        put("macs", buildJsonArray { macs.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    fun announce(name: String, macs: List<String>): String = buildJsonObject {
        put("app", "controlxia-pc")
        put("v", 1)
        put("name", name)
        put("port", TCP_PORT)
        put("macs", buildJsonArray { macs.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    /** MAC de las placas de red físicas (para prender la PC con Wake-on-LAN). */
    fun macs(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { !it.isLoopback && !it.isVirtual && it.hardwareAddress?.size == 6 }
            .sortedBy { if (it.isUp) 0 else 1 }
            .map { ni -> ni.hardwareAddress.joinToString(":") { "%02X".format(it) } }
            .distinct()
    }.getOrDefault(emptyList())
}

/** Resultado de un pedido del teléfono; [after] se ejecuta después de contestar (apagar, suspender). */
data class PhoneAnswer(val ok: Boolean, val msg: String, val after: (() -> Unit)? = null)

/**
 * Atiende al teléfono en la red de casa. Apagado por defecto: se prende en
 * Configuración › Teléfono, que muestra el código para vincular.
 */
class PhoneLink(
    private val scope: CoroutineScope,
    private val name: () -> String,
    private val code: () -> String,
    private val handle: suspend (cmd: String, arg: String) -> PhoneAnswer,
) {
    private val _status = MutableStateFlow("Apagado")
    val status: StateFlow<String> = _status.asStateFlow()

    @Volatile private var tcp: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null
    private val seen = ArrayDeque<String>()

    val isRunning: Boolean get() = tcp != null

    @Synchronized
    fun start() {
        if (tcp != null) return
        try {
            tcp = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PhoneProtocol.TCP_PORT))
            }
            udp = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(PhoneProtocol.UDP_PORT))
            }
        } catch (e: Exception) {
            stop()
            _status.value = "No se pudo abrir el puerto ${PhoneProtocol.TCP_PORT} (${e.message}). " +
                "¿Quedó instalado ControlXIA-PC.ps1? Desinstalalo y probá de nuevo."
            Log.e("Teléfono", e)
            return
        }
        _status.value = "Esperando al teléfono en el puerto ${PhoneProtocol.TCP_PORT}"
        Thread({ acceptLoop() }, "controlxia-phone-tcp").apply { isDaemon = true; start() }
        Thread({ discoveryLoop() }, "controlxia-phone-udp").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        runCatching { tcp?.close() }
        runCatching { udp?.close() }
        tcp = null
        udp = null
        _status.value = "Apagado"
    }

    private fun acceptLoop() {
        val server = tcp ?: return
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            scope.launch { serve(socket) }
        }
    }

    private suspend fun serve(socket: Socket) {
        var after: (() -> Unit)? = null
        socket.use { s ->
            runCatching {
                s.soTimeout = 3_000
                val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
                val writer = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val line = reader.readLine() ?: return@runCatching
                val request = PhoneProtocol.parse(line)
                val answer = if (request == null) {
                    PhoneAnswer(false, "pedido raro")
                } else {
                    val problem = synchronized(seen) {
                        val reason = PhoneProtocol.reject(request, code(), System.currentTimeMillis() / 1000, seen)
                        if (reason == null) {
                            seen.addLast(request.nonce)
                            while (seen.size > 200) seen.removeFirst()
                        }
                        reason
                    }
                    if (problem == "codigo") Log.e("Teléfono: pedido con código incorrecto desde ${s.inetAddress.hostAddress}")
                    if (problem != null) PhoneAnswer(false, problem) else {
                        Log.i("Teléfono: ${request.cmd} ${request.arg.take(60)}")
                        _status.value = "Último pedido: ${request.cmd}"
                        runCatching { handle(request.cmd, request.arg) }.getOrElse { PhoneAnswer(false, it.message ?: "error") }
                    }
                }
                after = answer.after
                writer.write(PhoneProtocol.reply(answer.ok, answer.msg, name(), PhoneProtocol.macs()))
                writer.newLine()
                writer.flush()
            }.onFailure { Log.e("Teléfono", it) }
        }
        after?.let { runCatching(it) }
    }

    private fun discoveryLoop() {
        val socket = udp ?: return
        val buffer = ByteArray(512)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching { socket.receive(packet) }.getOrNull() ?: break
            if (String(packet.data, 0, packet.length, Charsets.UTF_8).trim() == PhoneProtocol.DISCOVER) {
                val reply = PhoneProtocol.announce(name(), PhoneProtocol.macs()).toByteArray(Charsets.UTF_8)
                runCatching { socket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
            }
        }
    }
}
