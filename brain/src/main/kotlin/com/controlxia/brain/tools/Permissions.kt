package com.controlxia.brain.tools

/** Qué hacer ante cada tipo de acción (RF-18). */
enum class PermissionPolicy(val displayName: String) {
    ASK("Preguntar siempre"),
    ALLOW("Permitir"),
    BLOCK("Bloquear"),
}

/**
 * Tipos de acción agrupados por riesgo. La política por defecto es la de la
 * v1: las acciones destructivas o que entregan control de la PC preguntan.
 */
enum class ActionCategory(
    val displayName: String,
    val description: String,
    val defaultPolicy: PermissionPolicy,
) {
    INFO("Información", "Hora, fecha y preguntas generales", PermissionPolicy.ALLOW),
    OPEN_APPS("Abrir programas", "Abrir aplicaciones por nombre", PermissionPolicy.ALLOW),
    CLOSE_APPS("Cerrar programas", "Cerrar aplicaciones abiertas", PermissionPolicy.ASK),
    WEB("Web", "Abrir páginas y buscar en internet", PermissionPolicy.ALLOW),
    VOLUME("Volumen", "Subir, bajar y silenciar", PermissionPolicy.ALLOW),
    TIMERS("Temporizadores", "Temporizadores y recordatorios", PermissionPolicy.ALLOW),
    LOCK_SCREEN("Bloqueo y pantalla", "Bloquear la PC y apagar la pantalla", PermissionPolicy.ALLOW),
    POWER("Energía", "Suspender, reiniciar y apagar", PermissionPolicy.ASK),
    SCREEN_READ("Ver la pantalla", "Sacar una captura y enviarla al LLM", PermissionPolicy.ASK),
    INPUT_CONTROL("Mouse y teclado", "Mover el mouse, hacer clic y escribir", PermissionPolicy.ASK),
}

/** Origen de las políticas guardadas por el usuario. */
fun interface PermissionStore {
    fun policy(category: ActionCategory): PermissionPolicy
}

/**
 * Pide confirmación al usuario (por voz o con un botón). Devuelve true solo
 * si el usuario aceptó explícitamente.
 */
fun interface Confirmer {
    suspend fun confirm(question: String): Boolean
}
