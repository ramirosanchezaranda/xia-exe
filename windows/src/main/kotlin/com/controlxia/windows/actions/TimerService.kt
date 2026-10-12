package com.controlxia.windows.actions

import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.intOrNull
import com.controlxia.brain.tools.schema
import com.controlxia.brain.tools.str
import com.controlxia.brain.tools.tool
import com.controlxia.windows.config.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
data class ScheduledAlert(
    val id: String,
    val label: String,
    val dueAtMillis: Long,
    val isReminder: Boolean,
)

/**
 * RF-14: temporizadores y recordatorios. Se guardan en disco para sobrevivir
 * a un reinicio de la app; los que vencieron con la app cerrada avisan al
 * arrancar. Al vencer, [onFire] hace sonar el aviso, lo dice en voz alta y
 * muestra la notificación de Windows.
 */
class TimerService(
    private val scope: CoroutineScope,
    private val file: File = AppPaths.timersFile,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var onFire: suspend (ScheduledAlert) -> Unit = {}

    private val json = Json { ignoreUnknownKeys = true }
    private val jobs = mutableMapOf<String, Job>()
    private val _alerts = MutableStateFlow<List<ScheduledAlert>>(emptyList())
    val alerts: StateFlow<List<ScheduledAlert>> = _alerts.asStateFlow()

    /** Recupera lo pendiente del disco y lo vuelve a programar. */
    fun restore() {
        val saved = runCatching {
            if (file.exists()) json.decodeFromString(ListSerializer(ScheduledAlert.serializer()), file.readText()) else emptyList()
        }.getOrDefault(emptyList())
        saved.forEach { arm(it) }
    }

    @Synchronized
    fun schedule(label: String, dueAtMillis: Long, isReminder: Boolean): ScheduledAlert {
        val alert = ScheduledAlert(UUID.randomUUID().toString().take(8), label, dueAtMillis, isReminder)
        arm(alert)
        return alert
    }

    /** Cancela por id o por etiqueta; "" cancela todos. Devuelve cuántos. */
    @Synchronized
    fun cancel(query: String): Int {
        val q = query.trim().lowercase()
        val matches = _alerts.value.filter { q.isEmpty() || it.id == q || it.label.lowercase().contains(q) }
        matches.forEach { remove(it.id) }
        return matches.size
    }

    @Synchronized
    private fun arm(alert: ScheduledAlert) {
        _alerts.value = _alerts.value + alert
        persist()
        jobs[alert.id] = scope.launch {
            val wait = alert.dueAtMillis - clock()
            if (wait > 0) delay(wait)
            // Desde el propio job: se saca de la lista sin cancelarse a sí mismo.
            remove(alert.id, cancelJob = false)
            runCatching { onFire(alert) }
        }
    }

    @Synchronized
    private fun remove(id: String, cancelJob: Boolean = true) {
        val job = jobs.remove(id)
        if (cancelJob) job?.cancel()
        _alerts.value = _alerts.value.filterNot { it.id == id }
        persist()
    }

    private fun persist() {
        runCatching { file.writeText(json.encodeToString(ListSerializer(ScheduledAlert.serializer()), _alerts.value)) }
    }

    fun tools(): List<Tool> = listOf(
        tool(
            "set_timer",
            "Inicia un temporizador. Al terminar suena un aviso y aparece una notificación.",
            ActionCategory.TIMERS,
            schema {
                integer("seconds", "Duración total en segundos (ej. 10 minutos = 600)", required = true)
                string("label", "Etiqueta opcional, ej. 'fideos'")
            },
        ) { args ->
            val seconds = args.intOrNull("seconds")?.takeIf { it > 0 }
                ?: return@tool ToolResult("Falta la duración", isError = true)
            val label = args.str("label").ifBlank { "Temporizador" }
            schedule(label, clock() + seconds * 1000L, isReminder = false)
            ToolResult("Listo, temporizador de ${durationPhrase(seconds)}")
        },
        tool(
            "set_reminder",
            "Programa un recordatorio: dentro de N minutos o a una hora del día (HH:mm, 24 h).",
            ActionCategory.TIMERS,
            schema {
                string("text", "Qué hay que recordar", required = true)
                integer("in_minutes", "Dentro de cuántos minutos")
                string("at_time", "Hora del día en formato HH:mm, ej. 18:30")
            },
        ) { args ->
            val text = args.str("text").ifBlank { return@tool ToolResult("Falta qué recordar", isError = true) }
            val due = args.intOrNull("in_minutes")?.let { clock() + it * 60_000L }
                ?: args.str("at_time").takeIf { it.isNotBlank() }?.let { nextOccurrence(it) }
                ?: return@tool ToolResult("Falta cuándo: en minutos o a qué hora", isError = true)
            schedule(text, due, isReminder = true)
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(due), ZoneId.systemDefault())
            ToolResult("Te lo recuerdo a las ${at.format(DateTimeFormatter.ofPattern("H:mm"))}")
        },
        tool("list_timers", "Lista los temporizadores y recordatorios pendientes.", ActionCategory.TIMERS) {
            val list = _alerts.value
            if (list.isEmpty()) ToolResult("No hay temporizadores ni recordatorios pendientes")
            else ToolResult(list.joinToString("\n") {
                val left = ((it.dueAtMillis - clock()) / 1000).toInt().coerceAtLeast(0)
                "${it.id}: ${it.label}, faltan ${durationPhrase(left)}"
            })
        },
        tool(
            "cancel_timer",
            "Cancela temporizadores o recordatorios por id o etiqueta. Vacío cancela todos.",
            ActionCategory.TIMERS,
            schema { string("query", "Id o parte de la etiqueta; vacío = todos") },
        ) { args ->
            val n = cancel(args.str("query"))
            ToolResult(if (n == 0) "No encontré ninguno para cancelar" else "Cancelé $n")
        },
    )

    private fun nextOccurrence(hhmm: String): Long? {
        val time = runCatching { LocalTime.parse(hhmm.trim().padStart(5, '0')) }.getOrNull() ?: return null
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock()), ZoneId.systemDefault())
        var at = now.toLocalDate().atTime(time)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    companion object {
        fun durationPhrase(totalSeconds: Int): String {
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return listOfNotNull(
                h.takeIf { it > 0 }?.let { if (it == 1) "1 hora" else "$it horas" },
                m.takeIf { it > 0 }?.let { if (it == 1) "1 minuto" else "$it minutos" },
                s.takeIf { it > 0 || totalSeconds == 0 }?.let { if (it == 1) "1 segundo" else "$it segundos" },
            ).joinToString(" y ")
        }
    }
}

