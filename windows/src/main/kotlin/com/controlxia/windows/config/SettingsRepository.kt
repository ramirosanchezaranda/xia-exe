package com.controlxia.windows.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Configuración no sensible, guardada como JSON en la carpeta de datos. Los
 * cambios se aplican en vivo: quien necesita un valor lo lee de [state] en el
 * momento, así cambiar de proveedor no requiere reiniciar (criterio 6).
 * Las claves van aparte, en [SecretStore].
 */
class SettingsRepository(private val file: File = AppPaths.settingsFile) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val current: AppSettings get() = _state.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        save(next)
    }

    private fun load(): AppSettings = runCatching {
        if (file.exists()) json.decodeFromString(AppSettings.serializer(), file.readText()) else AppSettings()
    }.getOrElse { AppSettings() }

    private fun save(settings: AppSettings) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(AppSettings.serializer(), settings))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
