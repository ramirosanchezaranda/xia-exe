package com.controlxia.windows.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryEntry(
    val timestamp: Long,
    val command: String,
    val response: String,
    /** Acciones ejecutadas, ej. "open_app: Abriendo Spotify (DONE)". */
    val actions: List<String> = emptyList(),
)

/**
 * Historial local de comandos y respuestas (RF-19). Se guarda solo en la PC,
 * un JSON por línea, y el usuario lo puede borrar desde la configuración.
 */
class HistoryStore(private val file: File = AppPaths.historyFile, private val maxEntries: Int = 500) {

    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    @Synchronized
    fun add(entry: HistoryEntry) {
        val next = (_entries.value + entry).takeLast(maxEntries)
        _entries.value = next
        if (next.size < maxEntries) {
            file.appendText(json.encodeToString(HistoryEntry.serializer(), entry) + "\n")
        } else {
            rewrite(next)
        }
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        file.delete()
    }

    private fun rewrite(list: List<HistoryEntry>) {
        file.writeText(list.joinToString("") { json.encodeToString(HistoryEntry.serializer(), it) + "\n" })
    }

    private fun load(): List<HistoryEntry> = runCatching {
        if (!file.exists()) emptyList()
        else file.readLines().filter { it.isNotBlank() }.mapNotNull {
            runCatching { json.decodeFromString(HistoryEntry.serializer(), it) }.getOrNull()
        }.takeLast(maxEntries)
    }.getOrElse { emptyList() }
}
