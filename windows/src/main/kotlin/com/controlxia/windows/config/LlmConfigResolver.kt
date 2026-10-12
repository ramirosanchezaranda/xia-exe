package com.controlxia.windows.config

import com.controlxia.brain.llm.LlmConfig
import com.controlxia.brain.llm.LlmProviderType

/** Arma la configuración lista para usar combinando preferencias y claves guardadas. */
class LlmConfigResolver(private val settings: SettingsRepository, private val secrets: SecretStore) {

    /** Principal y, si hay, respaldo. Vacío = falta configurar. */
    fun activeChain(): List<LlmConfig> {
        val s = settings.current
        return listOfNotNull(s.llm.active, s.llm.fallback).distinct().mapNotNull { resolve(it) }
    }

    /** Config de [type], o null si faltan datos (modelo, clave o URL). */
    fun resolve(type: LlmProviderType): LlmConfig? {
        val p = settings.current.llm.forType(type)
        val key = secrets.get(SecretStore.llmKey(type))
        if (p.model.isBlank()) return null
        if (type.needsApiKey && key.isBlank()) return null
        val baseUrl = p.baseUrl.ifBlank { type.defaultBaseUrl.orEmpty() }
        if (type.needsBaseUrl && baseUrl.isBlank()) return null
        return LlmConfig(type, key, p.model, baseUrl.ifBlank { null }, p.effort.ifBlank { null })
    }

    /** Qué falta para poder usar [type], para mostrarlo en la UI. */
    fun missing(type: LlmProviderType): String? {
        val p = settings.current.llm.forType(type)
        return when {
            p.model.isBlank() -> "Falta el modelo"
            type.needsApiKey && secrets.get(SecretStore.llmKey(type)).isBlank() -> "Falta la API key"
            type.needsBaseUrl && p.baseUrl.ifBlank { type.defaultBaseUrl.orEmpty() }.isBlank() -> "Falta la URL"
            else -> null
        }
    }
}
