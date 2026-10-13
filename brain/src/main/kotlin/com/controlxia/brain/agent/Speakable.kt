package com.controlxia.brain.agent

/**
 * Convierte una respuesta del LLM en texto para decir en voz alta: saca el
 * markdown (negritas, títulos, listas, código, enlaces) que algunos modelos
 * usan aunque se les pida que no, y une los párrafos en frases.
 */
fun toSpeakable(text: String): String = text
    .replace(Regex("```[\\s\\S]*?```"), " ")
    .replace(Regex("`([^`]*)`"), "$1")
    .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
    .replace(Regex("(?<![\\w*])[*_]([^*_\\n]+)[*_](?![\\w*])"), "$1")
    .replace("**", "")
    .lines()
    .map { line ->
        line.trim()
            .replace(Regex("^#{1,6}\\s*"), "")
            .replace(Regex("^[-*•]\\s+"), "")
            .replace(Regex("^\\d+[.)]\\s+"), "")
            .replace(Regex("^>\\s*"), "")
    }
    .filter { it.isNotBlank() && !it.matches(Regex("^[-=_|:\\s]+$")) }
    .let { lines ->
        // Al unir párrafos o ítems se marca la pausa con un punto; la última línea queda como está.
        lines.mapIndexed { i, line -> if (i == lines.lastIndex || line.last() in ".!?:;,…") line else "$line." }
    }
    .joinToString(" ")
    .replace(Regex("\\s+"), " ")
    .trim()
