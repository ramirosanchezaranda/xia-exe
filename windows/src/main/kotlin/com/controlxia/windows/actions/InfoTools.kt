package com.controlxia.windows.actions

import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.tool
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** RF-10: hora y fecha. Se dicen directo, sin otra vuelta al LLM. */
object InfoTools {

    private val spanish = Locale.forLanguageTag("es-AR")

    fun all(now: () -> LocalDateTime = LocalDateTime::now): List<Tool> = listOf(
        tool(
            "get_time", "Dice la hora actual. Usar cuando el usuario pregunta qué hora es.",
            ActionCategory.INFO, speakDirectly = true,
        ) { ToolResult(timePhrase(now())) },
        tool(
            "get_date", "Dice la fecha de hoy. Usar cuando el usuario pregunta qué día es.",
            ActionCategory.INFO, speakDirectly = true,
        ) { ToolResult(datePhrase(now())) },
    )

    fun timePhrase(t: LocalDateTime): String =
        if (t.hour == 1) "Es la una y ${"%02d".format(t.minute)}".replace(" y 00", " en punto")
        else "Son las ${t.format(DateTimeFormatter.ofPattern("H:mm"))}"

    fun datePhrase(t: LocalDateTime): String =
        "Hoy es " + t.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", spanish))
}
