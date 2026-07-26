package com.stzb.server.game.battle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

object BattleReportCodec {
    private val mapper = jacksonObjectMapper()

    fun toJson(result: BattleResult): String =
        mapper.writeValueAsString(
            mapOf(
                "outcome" to result.outcome.name,
                "attacker" to result.attacker.heroes.map { it.toReportMap() },
                "defender" to result.defender.heroes.map { it.toReportMap() },
                "events" to result.events.map { it.toReportMap() },
            ),
        )

    fun toCompressedClientReport(result: BattleResult): String {
        val bytes = ClientReportTextEncoder.encode(result).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip -> gzip.write(bytes) }
        return "zzz" + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun BattleHero.toReportMap(): Map<String, Any> =
        mapOf(
            "heroId" to id.value,
            "position" to position,
            "troops" to troops,
            "maxTroops" to maxTroops,
            "skills" to skillIds,
        )

    private fun BattleHeroRef.toReportMap(): Map<String, Any> =
        mapOf(
            "side" to side.name,
            "position" to position,
            "heroId" to heroId.value,
        )

    private fun BattleEvent.toReportMap(): Map<String, Any> =
        when (this) {
            BattleEvent.BattleStart -> mapOf("type" to "BattleStart")
            is BattleEvent.SkillTriggered -> mapOf(
                "type" to "SkillTriggered",
                "round" to round,
                "source" to source.toReportMap(),
                "rootSkillId" to rootSkillId,
                "skillId" to skillId,
                "trigger" to trigger.name,
            )
            is BattleEvent.TriggerPoint -> mapOf(
                "type" to "TriggerPoint",
                "round" to round,
                "source" to source.toReportMap(),
                "trigger" to trigger.name,
            )
            is BattleEvent.SkillPreparationCompleted -> mapOf(
                "type" to "SkillPreparationCompleted",
                "round" to round,
                "source" to source.toReportMap(),
                "rootSkillId" to rootSkillId,
                "skillId" to skillId,
                "startedRound" to startedRound,
                "readyRound" to readyRound,
                "trigger" to trigger.name,
            )
            is BattleEvent.SkillPreparationCancelled -> mapOf(
                "type" to "SkillPreparationCancelled",
                "round" to round,
                "source" to source.toReportMap(),
                "rootSkillId" to rootSkillId,
                "skillId" to skillId,
                "reason" to reason,
            )
            is BattleEvent.RoundStart -> mapOf("type" to "RoundStart", "round" to round)
            is BattleEvent.HeroActionStart -> mapOf(
                "type" to "HeroActionStart",
                "round" to round,
                "source" to source.toReportMap(),
            )
            is BattleEvent.NormalAttack -> mapOf(
                "type" to "NormalAttack",
                "round" to round,
                "source" to source.toReportMap(),
                "target" to target.toReportMap(),
                "damage" to damage,
                "targetTroopsAfter" to targetTroopsAfter,
            )
            is BattleEvent.SkillDamage -> mapOf(
                "type" to "SkillDamage",
                "round" to round,
                "skillId" to skillId,
                "effectId" to effectId,
                "source" to source.toReportMap(),
                "target" to target.toReportMap(),
                "damage" to damage,
                "targetTroopsAfter" to targetTroopsAfter,
            )
            is BattleEvent.SkillPreparationStarted -> mapOf(
                "type" to "SkillPreparationStarted",
                "round" to round,
                "source" to source.toReportMap(),
                "skillId" to skillId,
                "readyRound" to readyRound,
            )
            is BattleEvent.Recovery -> mapOf(
                "type" to "Recovery",
                "round" to round,
                "source" to source.toReportMap(),
                "target" to target.toReportMap(),
                "amount" to amount,
                "targetTroopsAfter" to targetTroopsAfter,
                "skillId" to skillId,
            )
            is BattleEvent.StatusApplied -> mapOf(
                "type" to "StatusApplied",
                "round" to round,
                "source" to source.toReportMap(),
                "target" to target.toReportMap(),
                "status" to status.name,
                "durationRounds" to durationRounds,
                "skillId" to skillId,
            )
            is BattleEvent.OngoingDamage -> mapOf(
                "type" to "OngoingDamage",
                "round" to round,
                "source" to source.toReportMap(),
                "target" to target.toReportMap(),
                "status" to status.name,
                "damage" to damage,
                "targetTroopsAfter" to targetTroopsAfter,
                "skillId" to skillId,
            )
            is BattleEvent.UnsupportedSkillEffect -> mapOf(
                "type" to "UnsupportedSkillEffect",
                "round" to round,
                "skillId" to skillId,
                "effectId" to effectId,
                "source" to source.toReportMap(),
                "rawDescription" to rawDescription,
            )
            is BattleEvent.UnsupportedEquipmentEffect -> mapOf(
                "type" to "UnsupportedEquipmentEffect",
                "round" to round,
                "equipmentId" to equipmentId,
                "source" to source.toReportMap(),
                "rawDescription" to rawDescription,
            )
            is BattleEvent.HeroActionEnd -> mapOf(
                "type" to "HeroActionEnd",
                "round" to round,
                "source" to source.toReportMap(),
            )
            is BattleEvent.RoundEnd -> mapOf("type" to "RoundEnd", "round" to round)
            is BattleEvent.BattleEnd -> mapOf("type" to "BattleEnd", "outcome" to outcome.name)
            is BattleEvent.Evaded -> mapOf(
                "type" to "Evaded",
                "round" to round,
                "source" to source.toReportMap(),
                "target" to target.toReportMap(),
            )
            is BattleEvent.StatChanged -> mapOf(
                "type" to "StatChanged",
                "round" to round,
                "source" to source.toReportMap(),
                "target" to target.toReportMap(),
                "stat" to stat.name,
                "delta" to delta,
                "durationRounds" to durationRounds,
                "skillId" to skillId,
            )
        }
}
