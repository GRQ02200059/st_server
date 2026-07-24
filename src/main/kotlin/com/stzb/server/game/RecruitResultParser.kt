package com.stzb.server.game

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.protocol.Cmd

object RecruitResultParser {
    private val mapper = jacksonObjectMapper()

    fun heroIdsFrom(cmdId: Int, recruitJson: String): List<Int> {
        val root = runCatching { mapper.readTree(recruitJson) }.getOrNull() ?: return emptyList()
        val cards = when (cmdId) {
            Cmd.CARD_RECRUIT -> root.get(1)
            Cmd.CARD_QUICK_RECRUIT -> root.get(7)
            else -> null
        }
        return heroIdsFromCards(cards)
    }

    private fun heroIdsFromCards(cards: JsonNode?): List<Int> =
        cards?.takeIf { it.isArray }
            ?.mapNotNull { card ->
                val heroId = card.get(1)?.asInt() ?: 0
                heroId.takeIf { it > 0 }
            }
            ?: emptyList()
}
