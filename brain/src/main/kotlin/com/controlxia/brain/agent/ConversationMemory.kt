package com.controlxia.brain.agent

import com.controlxia.brain.llm.ChatMessage
import com.controlxia.brain.llm.ChatRole

/**
 * Memoria conversacional corta (RF-09). Guarda los últimos [maxTurns] pedidos
 * completos (pedido del usuario + llamadas a tools + respuesta), así nunca se
 * corta un par llamada/resultado al medio. Las capturas de turnos viejos se
 * descartan para no reenviar imágenes pesadas en cada pedido.
 */
class ConversationMemory(private val maxTurns: Int = 8) {

    private val turns = ArrayDeque<MutableList<ChatMessage>>()

    @Synchronized
    fun startTurn(userText: String) {
        turns.addLast(mutableListOf(ChatMessage(ChatRole.USER, userText)))
        while (turns.size > maxTurns) turns.removeFirst()
        // Solo el turno actual conserva imágenes.
        for (i in 0 until turns.size - 1) {
            turns[i].replaceAll { msg ->
                if (msg.images.isEmpty()) msg
                else msg.copy(images = emptyList(), content = msg.content + " [captura omitida]")
            }
        }
    }

    @Synchronized
    fun add(message: ChatMessage) {
        if (turns.isEmpty()) turns.addLast(mutableListOf())
        turns.last().add(message)
    }

    @Synchronized
    fun history(): List<ChatMessage> = turns.flatten()

    @Synchronized
    fun clear() = turns.clear()
}
