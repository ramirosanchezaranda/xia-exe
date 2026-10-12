package com.controlxia.windows.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/** Recibe cuadros de audio PCM 16 kHz, 16 bits, mono. */
fun interface AudioSink {
    fun onFrame(frame: ShortArray)
}

/**
 * Captura única del micrófono que reparte los cuadros al consumidor de turno
 * (palabra de activación o transcripción). Así no se abre y cierra el
 * dispositivo en cada cambio y se puede cortar al asistente mientras habla.
 *
 * Con [stop] se libera el micrófono: con la escucha pausada no se procesa
 * audio (criterio de aceptación 5).
 */
class Microphone(private val frameLength: Int = FRAME_LENGTH) {

    @Volatile var sink: AudioSink? = null

    private var line: TargetDataLine? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running) return
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) throw IllegalStateException("No se encontró un micrófono compatible")
        val l = AudioSystem.getLine(info) as TargetDataLine
        l.open(format, frameLength * 2 * 8)
        l.start()
        line = l
        running = true
        thread = Thread({ loop(l) }, "controlxia-mic").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        line?.run { stop(); close() }
        line = null
        thread?.join(500)
        thread = null
    }

    private fun loop(l: TargetDataLine) {
        val bytes = ByteArray(frameLength * 2)
        while (running) {
            var read = 0
            while (read < bytes.size && running) {
                val n = l.read(bytes, read, bytes.size - read)
                if (n <= 0) break
                read += n
            }
            if (read < bytes.size) continue
            val frame = ShortArray(frameLength) { i ->
                ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
            }
            runCatching { sink?.onFrame(frame) }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** Coincide con el cuadro de Porcupine (512 muestras = 32 ms). */
        const val FRAME_LENGTH = 512
    }
}
