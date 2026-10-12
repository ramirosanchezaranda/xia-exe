package com.controlxia.windows.actions

import com.controlxia.brain.llm.ImageData
import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

/**
 * Captura de pantalla para que el LLM "vea" la PC antes de manejar el mouse.
 * La imagen se achica (máx. [MAX_WIDTH] px de ancho) y se recuerda la escala:
 * las coordenadas que devuelve el modelo están en el sistema de la captura y
 * [toScreen] las pasa a coordenadas reales de la pantalla.
 */
class ScreenCapture(private val robot: () -> Robot) {

    /** Escritorio virtual completo (todos los monitores) y la escala de la última captura. */
    @Volatile private var mapping: Mapping? = null

    data class Mapping(val bounds: Rectangle, val scale: Double) {
        val width: Int get() = (bounds.width * scale).roundToInt()
        val height: Int get() = (bounds.height * scale).roundToInt()
    }

    fun tools(): List<Tool> = listOf(
        tool(
            "look_at_screen",
            "Saca una captura de toda la pantalla para ver qué hay. Usala antes de hacer clic, " +
                "y otra vez después para comprobar el resultado. Las coordenadas de la imagen son las que " +
                "hay que usar en mouse_click y mouse_move.",
            ActionCategory.SCREEN_READ,
            confirm = { "¿Miro la pantalla? La captura se envía al LLM." },
            progress = { "Mirando la pantalla…" },
        ) { capture() },
    )

    suspend fun capture(): ToolResult = withContext(Dispatchers.IO) {
        val m = currentMapping()
        val raw = robot().createScreenCapture(m.bounds)
        val scaled = BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(raw, 0, 0, m.width, m.height, null)
            dispose()
        }
        mapping = m
        ToolResult(
            text = "Captura de ${m.width}x${m.height} píxeles. El origen (0,0) es la esquina superior izquierda.",
            image = ImageData(Base64.getEncoder().encodeToString(jpeg(scaled)), "image/jpeg"),
        )
    }

    /** Convierte coordenadas de la captura a coordenadas de pantalla. */
    fun toScreen(x: Int, y: Int): Point {
        val m = mapping ?: currentMapping()
        val sx = (x.coerceIn(0, m.width - 1) / m.scale).roundToInt() + m.bounds.x
        val sy = (y.coerceIn(0, m.height - 1) / m.scale).roundToInt() + m.bounds.y
        return Point(sx, sy)
    }

    private fun currentMapping(): Mapping {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }
            .reduce { acc, r -> acc.union(r) }
        return Mapping(bounds, minOf(1.0, MAX_WIDTH.toDouble() / bounds.width))
    }

    private fun jpeg(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.8f
        }
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(image, null, null), params)
        }
        writer.dispose()
        return out.toByteArray()
    }

    companion object {
        const val MAX_WIDTH = 1366
    }
}
