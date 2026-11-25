package com.controlxia.windows

import com.controlxia.windows.assistant.DirectCommands
import com.controlxia.windows.assistant.DirectCommands.AppCommand
import com.controlxia.windows.assistant.DirectCommands.Media
import com.controlxia.windows.assistant.DirectCommands.Numbers
import com.controlxia.windows.assistant.DirectCommands.Open
import com.controlxia.windows.assistant.DirectCommands.SwitchTo
import com.controlxia.windows.assistant.DirectCommands.TapLabel
import com.controlxia.windows.assistant.DirectCommands.TapNumber
import com.controlxia.windows.assistant.DirectCommands.Timer
import com.controlxia.windows.assistant.DirectCommands.Type
import com.controlxia.windows.assistant.DirectCommands.Volume
import com.controlxia.windows.assistant.DirectCommands.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectCommandsTest {

    private fun p(text: String, numbers: Boolean = false) = DirectCommands.parse(text, "Xia", numbers)

    @Test
    fun volumen_y_musica() {
        assertEquals(Volume("up"), p("Subí el volumen"))
        assertEquals(Volume("set", 30), p("Xia, bajá el volumen al 30 por ciento"))
        assertEquals(Volume("set", 50), p("poné el volumen al 50"))
        assertEquals(Volume("mute"), p("silenciá"))
        assertEquals(Media("play_pause"), p("pausá la música"))
        assertEquals(Media("next"), p("siguiente canción"))
        assertEquals(Media("previous"), p("tema anterior"))
    }

    @Test
    fun ventanas() {
        assertEquals(Window("minimize", ""), p("minimizá"))
        assertEquals(Window("minimize", "chrome"), p("minimizá Chrome"))
        assertEquals(Window("maximize", ""), p("maximizá la ventana"))
        assertEquals(Window("close", ""), p("cerrá esta ventana"))
        assertEquals(Window("snap_left"), p("ponela a la izquierda"))
        assertEquals(DirectCommands.ShowDesktop, p("mostrame el escritorio"))
        assertEquals(SwitchTo("word"), p("pasá a Word"))
    }

    @Test
    fun atajos_de_programa() {
        assertEquals(AppCommand("new_tab"), p("nueva pestaña"))
        assertEquals(AppCommand("next_tab"), p("pasá a la siguiente pestaña"))
        assertEquals(AppCommand("close_tab"), p("cerrá la pestaña"))
        assertEquals(AppCommand("reload"), p("actualizá la página"))
        assertEquals(AppCommand("save"), p("guardalo"))
        assertEquals(AppCommand("bold"), p("ponelo en negrita"))
        assertEquals(AppCommand("undo"), p("deshacé"))
        assertEquals(AppCommand("page_top"), p("andá al principio"))
    }

    @Test
    fun numeros_y_toques() {
        assertEquals(Numbers(true), p("mostrá los números"))
        assertEquals(Numbers(false), p("ocultá los números"))
        assertEquals(TapNumber(5), p("tocá el 5"))
        assertEquals(TapNumber(12), p("clic en 12"))
        assertEquals(TapNumber(7), p("7", numbers = true))
        assertNull(p("7"))
        assertEquals(TapLabel("aceptar"), p("tocá el botón aceptar"))
    }

    @Test
    fun escribir_texto_literal() {
        assertEquals(Type("Hola Mundo, ¿cómo va?"), p("escribí Hola Mundo, ¿cómo va?"))
        assertNull(p("escribile a Juan que llego tarde"))
        assertNull(p("escribí un mail a mi jefe"))
        assertNull(p("escribí hola en el bloc de notas"))
    }

    @Test
    fun abrir_hora_y_temporizador() {
        assertEquals(Open("carpeta de documentos"), p("abrí la carpeta de documentos"))
        assertEquals(Open("spotify"), p("Xia abrí Spotify por favor"))
        assertNull(p("abrí chrome y buscá recetas"))
        assertEquals(DirectCommands.Time, p("¿qué hora es?"))
        assertEquals(Timer(600), p("poné un temporizador de 10 minutos"))
        assertEquals(Timer(300), p("poneme un temporizador de cinco minutos"))
        assertEquals(Timer(1800), p("temporizador de media hora"))
        assertEquals(Timer(90), p("avisame en un minuto y medio"))
        assertEquals(DirectCommands.Lock, p("bloqueá la compu"))
    }

    @Test
    fun lo_demas_va_al_modelo() {
        assertNull(p("contame un chiste"))
        assertNull(p("cuál es la capital de Francia"))
        assertNull(p("mandale un mensaje a Juan"))
    }
}
