package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleEvent
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleTeam
import com.stzb.server.game.battle.FixedBattleRandom
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

class ChijieOngoingDamageIntegrationTest {
    @Test
    fun `chijie stacks strategy before scheduled ongoing damage is registered`() {
        val ownerHero = hero(100989, 100, listOf(200989), 2)
            .copy(skillLevels = listOf(10))
        val sourceHero = hero(100020, 90, listOf(200020), 1)
            .copy(skillLevels = listOf(10))
        val request = BattleRequest(
            attacker = BattleTeam(listOf(ownerHero, sourceHero)),
            defender = BattleTeam(
                listOf(
                    hero(200001, 30, position = 2),
                    hero(200002, 20, position = 1),
                    hero(200003, 10, position = 0),
                ),
            ),
            maxRounds = 2,
        )
        val config = BattleConfigRepository.loadDefault()
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == ownerHero.id }
        val source = engine.state.view.heroes().single { it.heroId == sourceHero.id }
        val events = engine.prepareBattle(
            SkillBattleContext(
                request = request,
                runtime = engine.state.runtime,
                random = FixedBattleRandom(0),
                round = 0,
                source = owner,
                rootSkillId = 0,
                currentSkillId = 0,
                trigger = BattleTrigger.BATTLE_COMMAND,
                battleView = engine.state.view,
            ),
        )

        val effects = engine.state.effectStore.effectsFor(source)
            .filter { it.detailId == 21498901 }
        assertEquals(1, effects.size)
        assertEquals(4, effects.single().stacks)
        assertEquals(
            4,
            events.filterIsInstance<BattleEvent.StatChanged>().count {
                it.round == 0 && it.target == source && it.skillId == 214989
            },
        )
    }

    @Test
    fun `emergency recovery uses its registration snapshot after chijie expires`() {
        val ownerHero = hero(100989, 100, listOf(200989), 2)
            .copy(skillLevels = listOf(10))
        val sourceHero = hero(100020, 90, listOf(200020, 200016), 1)
            .copy(skillLevels = listOf(10, 10))
        val allyHero = hero(100017, 80, position = 0)
        val request = BattleRequest(
            attacker = BattleTeam(listOf(ownerHero, sourceHero, allyHero)),
            defender = BattleTeam(
                listOf(
                    hero(200001, 30, position = 2),
                    hero(200002, 20, position = 1),
                    hero(200003, 10, position = 0),
                ),
            ),
            maxRounds = 2,
        )
        val config = BattleConfigRepository.loadDefault()
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == ownerHero.id }
        val source = engine.state.view.heroes().single { it.heroId == sourceHero.id }
        val ally = engine.state.view.heroes().single { it.heroId == allyHero.id }
        val enemy = engine.state.view.heroes().single {
            it.heroId == BattleHeroId(200001)
        }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        engine.prepareBattle(context)
        val registeredRate = engine.state.effectStore.effectsFor(ally)
            .single { it.source == source && it.skillId == 200016 }
            .effectiveStrength
        val troopBase = (
            sourceHero.troops * 300.0 / (3_500 + sourceHero.troops)
            ).roundToInt()
        engine.finishRound(1)

        assertEquals(
            0,
            engine.state.effectStore.effectsFor(source)
                .count { it.detailId == 21498901 },
        )
        val events = engine.applyNormalDamage(
            round = 2,
            source = enemy,
            target = ally,
            amount = 1_000,
            context = context.copy(
                round = 2,
                source = enemy,
                trigger = BattleTrigger.DAMAGE_BEFORE,
            ),
        )
        assertEquals(
            troopBase * registeredRate / 100,
            events.filterIsInstance<BattleEvent.Recovery>()
                .single { it.source == source && it.target == ally }
                .amount,
        )
    }

    private fun hero(
        id: Int,
        speed: Int,
        skills: List<Int> = emptyList(),
        position: Int = 2,
    ) = BattleHero(
        id = BattleHeroId(id),
        position = position,
        stats = BattleStats(100, 100, 100, speed, 0, 5),
        troops = 10_000,
        maxTroops = 10_000,
        skillIds = skills,
    )
}
