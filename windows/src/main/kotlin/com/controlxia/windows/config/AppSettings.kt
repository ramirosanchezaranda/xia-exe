package com.controlxia.windows.config

import com.controlxia.brain.llm.LlmProviderType
import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.PermissionPolicy
import kotlinx.serialization.Serializable

/** Motor de palabra de activación. */
@Serializable
enum class WakeEngineType(val displayName: String) {
    PORCUPINE("Porcupine (Picovoice, requiere AccessKey)"),
    VOSK("Vosk (sin cuenta, cualquier nombre)"),
    NONE("Ninguno (solo atajo de teclado)"),
}

@Serializable
data class ProviderSettings(
    val model: String = "",
    val baseUrl: String = "",
    /** Solo Anthropic: "low" | "medium" | "high". Vacío = no se envía. */
    val effort: String = "",
)

@Serializable
data class LlmPreferences(
    val active: LlmProviderType = LlmProviderType.ANTHROPIC,
    /** Proveedor de respaldo si el principal falla (null = sin respaldo). */
    val fallback: LlmProviderType? = null,
    val providers: Map<LlmProviderType, ProviderSettings> = mapOf(
        LlmProviderType.ANTHROPIC to ProviderSettings(model = "claude-opus-5-5", effort = "low"),
    ),
) {
    fun forType(type: LlmProviderType): ProviderSettings =
        providers[type] ?: ProviderSettings(
            model = type.suggestedModels.firstOrNull().orEmpty(),
            baseUrl = type.defaultBaseUrl.orEmpty(),
        )
}

@Serializable
data class VoicePreferences(
    /** Nombre de la voz SAPI, o vacío para la voz por defecto. */
    val voiceName: String = "",
    /** -10 (lento) a 10 (rápido). */
    val rate: Int = 0,
    /** -10 (grave) a 10 (agudo). */
    val pitch: Int = 0,
    /** 0 a 100. */
    val volume: Int = 100,
)

@Serializable
data class WakePreferences(
    val engine: WakeEngineType = WakeEngineType.VOSK,
    /** Palabra de fábrica de Porcupine (nombre del enum BuiltInKeyword). */
    val porcupineKeyword: String = "JARVIS",
    /** Modelo propio .ppn para Windows (ej. "Xia"); vacío = palabra de fábrica. */
    val customKeywordPath: String = "",
    /** Modelo de idioma de Porcupine (ej. porcupine_params_es.pv) para el .ppn propio. */
    val porcupineModelPath: String = "",
    /** 0.0 a 1.0: más alto detecta más, pero con más falsos positivos. */
    val sensitivity: Float = 0.5f,
    /** Frase que busca Vosk (puede ser el nombre del asistente). */
    val voskPhrase: String = "xia",
)

@Serializable
data class AppSettings(
    val agentName: String = "Xia",
    val wake: WakePreferences = WakePreferences(),
    val hotkey: String = "Ctrl+Alt+X",
    val listeningPaused: Boolean = false,
    val autostart: Boolean = true,
    /** Carpeta del modelo de Vosk; vacío = el descargado por la app. */
    val voskModelPath: String = "",
    val llm: LlmPreferences = LlmPreferences(),
    val voice: VoicePreferences = VoicePreferences(),
    val permissions: Map<ActionCategory, PermissionPolicy> = emptyMap(),
    val historyEnabled: Boolean = true,
    /** Después de responder sigue escuchando sin la palabra de activación. */
    val conversationMode: Boolean = true,
    /** Cuántos segundos de silencio cierran la conversación (4 a 15). */
    val followUpSeconds: Int = 8,
    val onboardingDone: Boolean = false,
) {
    fun policy(category: ActionCategory): PermissionPolicy =
        permissions[category] ?: category.defaultPolicy
}
