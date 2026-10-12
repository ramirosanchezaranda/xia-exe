package com.controlxia.brain.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// Helpers para leer argumentos y armar JSON Schemas sin ruido.

fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()

fun JsonObject.strOrNull(key: String): String? = str(key).ifBlank { null }

fun JsonObject.int(key: String, default: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.let { it.doubleOrNull?.toInt() ?: it.content.toIntOrNull() } ?: default

fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.doubleOrNull?.toInt() ?: it.content.toIntOrNull() }

fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

/** Construye un JSON Schema de objeto: `schema { string("x", "desc", required = true) }`. */
fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

class SchemaBuilder {
    private val props = linkedMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        prop(name, required) {
            put("type", "string")
            put("description", description)
            if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
        }

    fun integer(name: String, description: String, required: Boolean = false) =
        prop(name, required) {
            put("type", "integer")
            put("description", description)
        }

    fun boolean(name: String, description: String, required: Boolean = false) =
        prop(name, required) {
            put("type", "boolean")
            put("description", description)
        }

    private fun prop(name: String, isRequired: Boolean, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        props[name] = buildJsonObject(block)
        if (isRequired) required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}
