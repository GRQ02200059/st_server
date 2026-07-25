package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class LearnSkillRequest(
    val heroUid: Int,
    val skillId: Int,
    val slotIndex: Int,
)

data class ForgetSkillRequest(
    val heroUid: Int,
    val skillId: Int,
)

object SkillOperationRequestParser {
    private val mapper = jacksonObjectMapper()

    fun parseLearn(body: String): LearnSkillRequest? =
        parseArray(body)?.let { root ->
            val heroUid = root.takeIf { it.size() > 0 }?.get(0)?.asInt() ?: return@let null
            val skillId = root.takeIf { it.size() > 1 }?.get(1)?.asInt() ?: return@let null
            val slotIndex = root.takeIf { it.size() > 2 }?.get(2)?.asInt() ?: return@let null
            LearnSkillRequest(heroUid, skillId, slotIndex).takeIf {
                it.heroUid > 0 && it.skillId > 0 && it.slotIndex in 2..PlayerHero.SKILL_SLOT_COUNT
            }
        }

    fun parseForget(body: String): ForgetSkillRequest? =
        parseArray(body)?.let { root ->
            val heroUid = root.takeIf { it.size() > 0 }?.get(0)?.asInt() ?: return@let null
            val skillId = root.takeIf { it.size() > 1 }?.get(1)?.asInt() ?: return@let null
            ForgetSkillRequest(heroUid, skillId).takeIf {
                it.heroUid > 0 && it.skillId > 0
            }
        }

    private fun parseArray(body: String) =
        runCatching { mapper.readTree(body) }
            .getOrNull()
            ?.takeIf { it.isArray }
}
