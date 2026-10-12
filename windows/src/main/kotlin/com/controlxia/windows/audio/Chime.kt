package com.controlxia.windows.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

/** Sonidos cortos generados en el momento: no hace falta empaquetar archivos. */
object Chime {

    /** "Te escucho": dos notas cortas ascendentes. */
    fun listening() = play(listOf(660.0 to 70, 990.0 to 90))

    /** Aviso de temporizador: tres pitidos. */
    fun alarm() = play(List(3) { listOf(1046.0 to 180, 0.0 to 120) }.flatten() + (1318.0 to 400))

    private fun play(notes: List<Pair<Double, Int>>) {
        Thread {
            runCatching {
                val rate = 22_050f
                val format = AudioFormat(rate, 16, 1, true, false)
                val line = AudioSystem.getSourceDataLine(format)
                line.open(format)
                line.start()
                for ((freq, ms) in notes) line.write(tone(freq, ms, rate), 0, (rate * ms / 1000).toInt() * 2)
                line.drain()
                line.close()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun tone(freq: Double, ms: Int, rate: Float): ByteArray {
        val samples = (rate * ms / 1000).toInt()
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            // Envolvente corta para que no "chasquee" al empezar y terminar.
            val env = minOf(1.0, i / 200.0, (samples - i) / 200.0)
            val v = if (freq == 0.0) 0.0 else sin(2 * PI * freq * i / rate) * env * 0.35
            val s = (v * Short.MAX_VALUE).toInt()
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = (s shr 8 and 0xFF).toByte()
        }
        return out
    }
}
