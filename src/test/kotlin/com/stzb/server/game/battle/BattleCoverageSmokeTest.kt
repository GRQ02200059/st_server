package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertTrue

class BattleCoverageSmokeTest {
    private val configRepo = BattleConfigRepository.loadDefault()
    private val equipmentRepo = BattleEquipmentRepository.loadDefault()

    @Test
    fun `all equipment configs parse into modifiers or fallback`() {
        equipmentRepo.allEquipmentIds().forEach { equipmentId ->
            val equipment = equipmentRepo.equipment(equipmentId) ?: error("missing equipment $equipmentId")
            val modifiers = BattleModifierParser.parseEquipment(equipment, equipmentRepo.features(equipment.featureGroup))
            assertTrue(modifiers.isNotEmpty(), "equipment $equipmentId should produce modifiers or fallback")
        }
    }

    @Test
    fun `sampled skills can enter runtime without throwing`() {
        val runtime = BattleSkillRuntime(configRepo)
        val targets = BattleTeam(listOf(hero(1, 0), hero(2, 1), hero(3, 2)))
        val allies = BattleTeam(listOf(hero(100, 0)))

        configRepo.allSkillIds().take(100).forEach { skillId ->
            runtime.tryAct(
                round = 1,
                sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100000 + skillId)),
                source = hero(100000 + skillId, 0).copy(skillIds = listOf(skillId)),
                targets = targets,
                allies = allies,
                random = FixedBattleRandom(0),
                state = SkillRuntimeState(),
            )
        }
    }

    private fun hero(heroId: Int, position: Int): BattleHero =
        BattleHero(
            id = BattleHeroId(heroId),
            position = position,
            stats = BattleStats(attack = 100, defense = 80, strategy = 90, speed = 70, siege = 0, hitRange = 3),
            troops = 1000,
        )
}
