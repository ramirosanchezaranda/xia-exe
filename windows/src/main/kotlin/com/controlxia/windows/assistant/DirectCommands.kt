package com.controlxia.windows.assistant

import com.controlxia.brain.tools.normalizeText

/**
 * Pedidos que la PC resuelve sola, sin el modelo, como en la app del
 * teléfono: "subí el volumen", "minimizá", "nueva pestaña", "mostrá los
 * números", "tocá el 5"… Son al instante, no gastan tokens y andan aunque se
 * termine el cupo del proveedor.
 *
 * Solo entra la frase entera: "abrí Chrome y buscá X" va al modelo.
 */
object DirectCommands {

    sealed interface Command
    data class Volume(val change: String, val percent: Int? = null) : Command
    data class Media(val action: String) : Command
    data class Window(val action: String, val target: String = "") : Command
    data class SwitchTo(val name: String) : Command
    data object ShowDesktop : Command
    data class AppCommand(val action: String) : Command
    data class Numbers(val show: Boolean) : Command
    data class TapNumber(val number: Int) : Command
    data class TapLabel(val label: String) : Command
    data class Type(val text: String) : Command
    data class Open(val name: String) : Command
    data object Time : Command
    data object Date : Command
    data class Timer(val seconds: Int) : Command
    data object Lock : Command
    data object ScreenOff : Command

    fun parse(text: String, agentName: String = "", numbersVisible: Boolean = false): Command? {
        val n = clean(text, agentName)
        if (n.isEmpty()) return null
        for ((regex, build) in RULES) {
            regex.matchEntire(n)?.let { m -> build(m, text)?.let { return it } }
        }
        if (numbersVisible) {
            Regex("(?:el )?(?:numero )?(\\d{1,3})").matchEntire(n)?.let { return TapNumber(it.groupValues[1].toInt()) }
        }
        return null
    }

    /** Sin tildes, sin el nombre del asistente ni muletillas ("che", "por favor", "podrías"). */
    internal fun clean(text: String, agentName: String): String {
        var t = normalizeText(text)
        val name = normalizeText(agentName)
        if (name.isNotEmpty()) t = t.removePrefix("$name ").removeSuffix(" $name")
        repeat(3) {
            t = t.replace(Regex("^(che|dale|bueno|ok|oye|por favor|porfa|podrias|podes|me podrias|me podes|quiero que|necesito que)\\s+"), "")
        }
        return t.replace(Regex("\\s+(por favor|porfa|gracias)$"), "").trim()
    }

    private const val PCT = "(?: (?:al|a|en) (\\d{1,3})(?: por ciento| %)?)?"

    private val APP_COMMANDS = listOf(
        "nueva pestana|abri una pestana nueva|abri una nueva pestana|pestana nueva" to "new_tab",
        "cerra (?:la |esta )?pestana" to "close_tab",
        "reabri (?:la )?pestana|volve a abrir la pestana" to "reopen_tab",
        "(?:pasa a la )?(?:siguiente|proxima) pestana" to "next_tab",
        "(?:pasa a la )?pestana anterior" to "previous_tab",
        "recarga(?:la)?(?: la pagina)?|actualiza(?: la pagina)?|refresca(?: la pagina)?" to "reload",
        "(?:volve )?atras|pagina anterior" to "back",
        "adelante|pagina siguiente" to "forward",
        "copia(?:lo|la)?|copiar" to "copy",
        "pega(?:lo|la)?|pegar" to "paste",
        "corta(?:lo|la)?|cortar" to "cut",
        "deshace(?:lo)?|deshacer|ctrl z" to "undo",
        "rehace(?:lo)?|rehacer" to "redo",
        "selecciona(?:r)? todo" to "select_all",
        "guarda(?:lo|la)?|guardar|guarda (?:el archivo|los cambios)" to "save",
        "guarda(?:r)? como" to "save_as",
        "imprimi(?:lo|la)?|imprimir" to "print",
        "(?:pone(?:lo|la)? en )?negrita" to "bold",
        "(?:pone(?:lo|la)? en )?cursiva" to "italic",
        "subraya(?:lo|la)?|subrayado" to "underline",
        "(?:dale |apreta )?enter|intro" to "enter",
        "escape|esc|cancela eso" to "escape",
        "baja(?: la pagina)?|scroll(?: para)? abajo|mas abajo" to "scroll_down",
        "subi la pagina|scroll(?: para)? arriba|mas arriba" to "scroll_up",
        "(?:anda |ir )?al principio(?: de la pagina)?" to "page_top",
        "(?:anda |ir )?al final(?: de la pagina)?" to "page_bottom",
        "acerca(?:la)?|agranda(?:la)?|zoom|mas zoom" to "zoom_in",
        "aleja(?:la)?|achica(?:la)?|menos zoom" to "zoom_out",
        "barra de direcciones" to "address_bar",
        "alt tab|cambia de (?:programa|ventana)" to "switch_app",
        "(?:hace un )?recorte(?: de pantalla)?|captura de pantalla" to "screenshot",
    )

    private val RULES: List<Pair<Regex, (MatchResult, String) -> Command?>> = buildList {
        // Volumen
        add(Regex("(?:subi|subir|subile|subime|sube|aumenta|mas)(?: el)? (?:volumen|sonido)(?: un poco)?$PCT") to { m, _ -> Volume(if (m.groupValues[1].isEmpty()) "up" else "set", m.groupValues[1].toIntOrNull()) })
        add(Regex("(?:baja|bajar|bajale|bajame|disminui|reduci|menos)(?: el)? (?:volumen|sonido)(?: un poco)?$PCT") to { m, _ -> Volume(if (m.groupValues[1].isEmpty()) "down" else "set", m.groupValues[1].toIntOrNull()) })
        add(Regex("(?:pone|poner|deja)?(?: el)? volumen (?:al|a|en) (\\d{1,3})(?: por ciento| %)?") to { m, _ -> Volume("set", m.groupValues[1].toInt()) })
        add(Regex("silencia(?:r|lo|la)?(?: la pc| la compu| todo| el sonido)?|mutea(?:lo)?|sin sonido|silencio") to { _, _ -> Volume("mute") })
        add(Regex("(?:activa|volve a poner|saca el silencio|desmutea)(?: el sonido)?") to { _, _ -> Volume("unmute") })
        // Música y videos
        add(Regex("(?:pausa|pausar|pone pausa|pausa la musica|pausa el video|para la musica|reanuda|segui|play|pone play|reproduci|continua)(?: la musica| el video| el tema)?") to { _, _ -> Media("play_pause") })
        add(Regex("(?:siguiente|proxima|proximo|pasa(?: de)?|cambia(?: de)?)(?: cancion| tema| video)|siguiente") to { _, _ -> Media("next") })
        add(Regex("(?:tema|cancion|video) anterior|anterior|volve al tema anterior") to { _, _ -> Media("previous") })
        // Ventanas
        add(Regex("(?:minimiza|minimizar|minimizame)(?: la| esta)?(?: ventana)?(?: de)? ?(.*)") to { m, _ -> Window("minimize", m.groupValues[1].trim()) })
        add(Regex("(?:maximiza|maximizar|agranda la ventana)(?: la| esta)?(?: ventana)?(?: de)? ?(.*)") to { m, _ -> Window("maximize", m.groupValues[1].trim()) })
        add(Regex("(?:restaura|restaurar)(?: la| esta)?(?: ventana)?(?: de)? ?(.*)") to { m, _ -> Window("restore", m.groupValues[1].trim()) })
        add(Regex("(?:cerra|cerrar|cerrame) (?:la|esta) ventana(?: de (.+))?") to { m, _ -> Window("close", m.groupValues[1].trim()) })
        add(Regex("(?:pone(?:la|lo)?|acomoda(?:la|lo)?|manda(?:la|lo)?)(?: la ventana)? a la (izquierda|derecha)") to { m, _ -> Window(if (m.groupValues[1] == "izquierda") "snap_left" else "snap_right") })
        add(Regex("(?:pone(?:la|lo)? en )?pantalla completa") to { _, _ -> Window("fullscreen") })
        add(Regex("(?:mostra(?:me)?|anda|ir|ver|volve)(?: al| a| el)? escritorio|escritorio|minimiza todo") to { _, _ -> ShowDesktop })
        // Números en pantalla
        add(Regex("(?:mostra|mostrar|mostrame|ver|pone|poneme) (?:los )?numeros|numeros") to { _, _ -> Numbers(true) })
        add(Regex("(?:oculta|ocultar|saca|sacar|esconde|esconder) (?:los )?numeros") to { _, _ -> Numbers(false) })
        add(Regex("(?:toca|tocar|toque|presiona|apreta|clic en|click en|clickea|cliquea|selecciona) (?:el )?(?:numero )?(\\d{1,3})") to { m, _ -> TapNumber(m.groupValues[1].toInt()) })
        add(Regex("(?:toca|tocar|toque|presiona|apreta|clic en|click en|clickea|cliquea|dale a|dale click a) (?:el |la |en )?(?:boton )?(.+)") to { m, _ ->
            m.groupValues[1].takeIf { !it.contains(" y ") }?.let { TapLabel(it) }
        })
        // Escribir (texto literal; "escribile a Juan…" o "escribí un mail…" van al modelo)
        add(Regex("(?:escribi|escribir|escribe|tipea|tipear|dicta) (.+)") to { m, original ->
            val rest = m.groupValues[1]
            if (Regex("^(le |les |a [a-z]+ |un mail|un correo|un mensaje|una carta|un documento|una nota|un texto sobre|algo)").containsMatchIn(rest) || rest.contains(" en ")) null
            else Type(literalAfterVerb(original))
        })
        // Atajos de programa
        APP_COMMANDS.forEach { (pattern, action) -> add(Regex(pattern) to { _, _ -> AppCommand(action) }) }
        // Cambiar de ventana (al final: "pasá a la siguiente pestaña" es un atajo, no una ventana)
        add(Regex("(?:cambia|pasa|anda|volve|ir|ve) (?:a|al) (.+)") to { m, _ -> SwitchTo(m.groupValues[1].trim()) })
        // Abrir (si no hay un programa o carpeta con ese nombre, va al modelo)
        add(Regex("(?:abri|abrir|abrime|abre|ejecuta|inicia|lanza|arranca)(?: el| la| los| las| mi| mis)? (.+)") to { m, _ ->
            m.groupValues[1].takeIf { !it.contains(" y ") && !it.contains(" en ") }?.let { Open(it) }
        })
        // Hora, fecha, temporizador
        add(Regex("(?:que hora es|que hora tenes|decime la hora|hora)") to { _, _ -> Time })
        add(Regex("(?:que dia es(?: hoy)?|que fecha es(?: hoy)?|decime la fecha|a que estamos hoy)") to { _, _ -> Date })
        add(Regex("(?:pone(?:me)?|programa|arranca)?(?: un)? ?temporizador (?:de |por )?(.+)|avisame en (.+)") to { m, _ ->
            durationSeconds(m.groupValues[1].ifEmpty { m.groupValues[2] })?.let { Timer(it) }
        })
        // Energía (lo que apaga o reinicia va al modelo, que pide confirmación)
        add(Regex("bloquea(?:r)?(?: la)? (?:pc|compu|computadora|pantalla|sesion)") to { _, _ -> Lock })
        add(Regex("apaga(?:r)? (?:la )?pantalla") to { _, _ -> ScreenOff })
    }

    /** "Escribí Hola Mundo" → "Hola Mundo" (con mayúsculas y signos como se dijeron). */
    internal fun literalAfterVerb(original: String): String =
        original.trim().replaceFirst(Regex("^(?i)(?:\\S+\\s+)*?(escrib[ií]r?|escribe|tipe[aá]r?|dict[aá]r?)\\s+"), "").trim()

    private val NUMBER_WORDS = mapOf(
        "un" to 1, "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7,
        "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "quince" to 15, "veinte" to 20,
        "veinticinco" to 25, "treinta" to 30, "cuarenta" to 40, "cuarenta y cinco" to 45, "cincuenta" to 50, "sesenta" to 60, "noventa" to 90,
    )

    /** "10 minutos", "media hora", "un minuto y medio", "90 segundos" → segundos. */
    internal fun durationSeconds(text: String): Int? {
        val t = text.trim()
        if (t == "media hora") return 1800
        if (t == "un cuarto de hora") return 900
        val m = Regex("(\\d+|[a-z]+(?: y cinco)?) (segundos?|minutos?|horas?)( y medio| y media)?").matchEntire(t) ?: return null
        val amount = m.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[m.groupValues[1]] ?: return null
        val unit = when {
            m.groupValues[2].startsWith("segundo") -> 1
            m.groupValues[2].startsWith("minuto") -> 60
            else -> 3600
        }
        return amount * unit + if (m.groupValues[3].isNotEmpty()) unit / 2 else 0
    }
}
