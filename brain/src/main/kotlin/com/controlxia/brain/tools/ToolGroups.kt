package com.controlxia.brain.tools

import com.controlxia.brain.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import java.text.Normalizer

/**
 * Un grupo de herramientas que se ofrece al modelo solo cuando hace falta.
 * Mandar todas en cada pedido gasta muchos tokens (el plan gratis de Groq
 * permite 8.000 por minuto) y confunde a los modelos chicos.
 */
data class ToolGroup(
    val id: String,
    /** Para qué sirve, en una frase: el modelo lo lee para decidir si cargarlo. */
    val description: String,
    /** Palabras que, si aparecen en el pedido, activan el grupo (sin tildes, minúsculas). */
    val keywords: List<String>,
    val tools: List<Tool>,
    /** Siempre disponible (lo básico: hora, abrir programas, volumen…). */
    val alwaysOn: Boolean = false,
)

/** Elige qué grupos van en cada pedido. */
object ToolRouter {

    const val LOAD_TOOLS = "load_tools"

    /** Grupos que corresponden a [text] por sus palabras clave. */
    fun matching(text: String, groups: List<ToolGroup>): Set<String> {
        val t = " " + normalizeText(text) + " "
        return groups.filter { g ->
            g.keywords.any { k -> k.isNotBlank() && Regex("(?<![a-z0-9])" + Regex.escape(normalizeText(k))).containsMatchIn(t) }
        }.map { it.id }.toSet()
    }

    /**
     * La herramienta para pedir más grupos. Solo se ofrece si quedaron grupos
     * afuera, y su descripción los lista para que el modelo sepa qué hay.
     */
    fun loadTool(inactive: List<ToolGroup>, onLoad: (String) -> ToolGroup?): Tool {
        val listing = inactive.joinToString("; ") { "${it.id}: ${it.description}" }
        return object : Tool {
            override val spec = ToolSpec(
                name = LOAD_TOOLS,
                description = "Carga más herramientas cuando las que tenés no alcanzan. Grupos: $listing",
                parameters = schema { string("group", "Grupo a cargar", required = true, enum = inactive.map { it.id }) },
            )
            override val category = ActionCategory.INFO
            override fun progressText(args: JsonObject) = "Buscando cómo hacerlo…"
            override suspend fun run(args: JsonObject): ToolResult {
                val group = onLoad(args.str("group")) ?: return ToolResult("Ese grupo no existe", isError = true)
                return ToolResult("Listo, ya podés usar: " + group.tools.joinToString(", ") { it.spec.name })
            }
        }
    }
}

/** Minúsculas y sin tildes, para comparar lo que dijo el usuario. */
fun normalizeText(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9ñ+ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
