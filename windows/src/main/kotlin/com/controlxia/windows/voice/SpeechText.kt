package com.controlxia.windows.voice

import java.text.Normalizer

/** Saca el texto de un JSON de Vosk: {"text": "..."} o {"partial": "..."}. */
internal fun extractText(json: String): String {
    val match = Regex("\"(?:text|partial)\"\\s*:\\s*\"([^\"]*)\"").find(json) ?: return ""
    return match.groupValues[1].trim()
}

/** Minúsculas, sin tildes ni signos: para comparar lo que se dijo. */
fun String.normalizeSpeech(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9ñ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/** Interpreta una respuesta hablada a "¿Confirmás?". null = no se entendió. */
fun parseYesNo(text: String): Boolean? {
    val t = " ${text.normalizeSpeech()} "
    val no = listOf(" no ", " cancela", " cancelar", " para ", " negativo", " mejor no", " nada ", " frena")
    val yes = listOf(
        " si ", " dale ", " ok ", " okay ", " confirmo ", " confirmado", " hacelo", " adelante",
        " de una ", " claro ", " afirmativo", " correcto", " por supuesto", " seguro ", " obvio",
    )
    return when {
        no.any { t.contains(it) } -> false
        yes.any { t.contains(it) } -> true
        else -> null
    }
}

/**
 * ¿El usuario está cerrando la conversación? Solo si la frase entera es una
 * despedida: "listo, ahora abrí Spotify" sigue siendo un pedido.
 */
fun isClosingPhrase(text: String): Boolean {
    val t = text.normalizeSpeech()
        .removePrefix("ok ").removePrefix("bueno ").removeSuffix(" gracias").trim()
    return t in CLOSING_PHRASES || text.normalizeSpeech() in CLOSING_PHRASES
}

private val CLOSING_PHRASES = setOf(
    "listo", "gracias", "muchas gracias", "nada", "nada mas", "eso es todo", "eso era todo",
    "chau", "chao", "hasta luego", "ya esta", "esta bien", "perfecto", "no gracias", "nada gracias",
    "listo gracias", "ok", "dale gracias", "basta", "termina", "terminamos",
)
