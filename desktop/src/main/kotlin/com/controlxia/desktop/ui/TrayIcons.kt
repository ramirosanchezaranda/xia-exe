package com.controlxia.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import com.controlxia.windows.assistant.Phase

/**
 * Ícono de la bandeja dibujado en código: un anillo con un punto central
 * cuyo color indica el estado (RF-01): azul escuchando, ámbar pensando,
 * verde hablando, gris en pausa.
 */
class StatusIconPainter(private val ring: Color, private val dot: Color, private val filled: Boolean) : Painter() {
    override val intrinsicSize: Size = Size(32f, 32f)

    override fun DrawScope.onDraw() {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        if (filled) drawCircle(ring, radius = r * 0.92f, center = c)
        else drawCircle(ring, radius = r * 0.80f, center = c, style = Stroke(width = r * 0.22f))
        drawCircle(dot, radius = r * 0.36f, center = c)
    }
}

object TrayIcons {
    private val cache = mutableMapOf<Phase, Painter>()

    fun forPhase(phase: Phase): Painter = cache.getOrPut(phase) {
        when (phase) {
            Phase.LISTENING -> StatusIconPainter(Accent, Color.White, filled = true)
            Phase.THINKING -> StatusIconPainter(Amber, Color.White, filled = true)
            Phase.SPEAKING -> StatusIconPainter(Green, Color.White, filled = true)
            Phase.CONFIRMING -> StatusIconPainter(Warn, Color.White, filled = true)
            Phase.PAUSED -> StatusIconPainter(Muted, Muted, filled = false)
            Phase.IDLE, Phase.STARTING -> StatusIconPainter(Ink, Accent, filled = false)
        }
    }
}
