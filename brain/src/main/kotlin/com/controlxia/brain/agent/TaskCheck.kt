package com.controlxia.brain.agent

import com.controlxia.brain.tools.normalizeText

/**
 * Control de que el modelo hizo de verdad lo que dice (como en la app de
 * Android): los modelos chicos a veces responden "listo, lo abrí" sin haber
 * usado ninguna herramienta. Lo decide el código, no el modelo.
 */
object TaskCheck {

    private val CLAIMS = listOf(
        "abri", "abro", "lo abri", "ya esta", "listo", "hecho", "puse", "pongo", "cerre", "cierro", "mande", "envie",
        "subi el", "baje el", "silencie", "guarde", "cree", "escribi", "ejecute", "instale", "active", "desactive",
        "busque", "reproduzco", "pause", "programe", "configure", "agregue", "borre", "movi",
    )
    private val QUESTION_START = Regex("^(que|cual|cuales|como|cuando|donde|quien|quienes|por que|cuanto|cuanta|cuantos|sabes|conoces|explicame|contame)\\b")

    /** true si [reply] afirma haber hecho algo en la PC aunque no se usó ninguna herramienta. */
    fun claimsUndoneAction(command: String, reply: String, toolsUsed: Int): Boolean {
        if (toolsUsed > 0) return false
        val c = normalizeText(command)
        if (command.trim().endsWith("?") || QUESTION_START.containsMatchIn(c)) return false
        val r = " " + normalizeText(reply) + " "
        return CLAIMS.any { r.contains(" $it ") }
    }

    const val NUDGE =
        "(Control automático) Respondiste como si hubieras hecho algo, pero no usaste ninguna herramienta, así que " +
            "no se hizo nada. Si el pedido necesita una acción, hacela ahora con las herramientas. Si no hacía falta " +
            "ninguna acción, respondé solamente OK."
}
