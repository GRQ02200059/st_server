package com.stzb.server.game.battle

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleIntegrationTest {
    @Test
    fun `loads real config builds teams resolves battle and emits compressed report`() {
        val repo = BattleConfigRepository.loadDefault()
        val builder = BattleTeamBuilder(repo)
        val attacker = builder.build(
            listOf(
                BattleHeroSpec(heroId = 100479, position = 0, troops = 1200),
                BattleHeroSpec(heroId = 100017, position = 1, troops = 1000),
                BattleHeroSpec(heroId = 100023, position = 2, troops = 900),
            ),
        )
        val defender = builder.build(
            listOf(
                BattleHeroSpec(heroId = 100352, position = 0, troops = 900),
                BattleHeroSpec(heroId = 100345, position = 1, troops = 900),
                BattleHeroSpec(heroId = 100344, position = 2, troops = 900),
            ),
        )

        val result = BattleEngine.resolve(
            request = BattleRequest(attacker = attacker, defender = defender),
            config = repo,
            random = FixedBattleRandom(0),
        )
        val report = BattleReportCodec.toCompressedClientReport(result)

        assertTrue(result.events.any { it is BattleEvent.SkillDamage })
        assertTrue(result.events.last() is BattleEvent.BattleEnd)
        assertTrue(report.startsWith("zzz"))
        assertTrue(report.length > 80)
    }

    @Test
    fun `playable battle uses real heroes skills equipment and full round budget`() {
        val repo = BattleConfigRepository.loadDefault()
        val equipmentRepo = BattleEquipmentRepository.loadDefault()
        val builder = BattleTeamBuilder(repo, equipmentRepo)
        val attacker = builder.build(
            listOf(
                BattleHeroSpec(heroId = 100479, position = 0, troops = 1800, level = 20, equipmentIds = listOf(1024)),
                BattleHeroSpec(heroId = 100017, position = 1, troops = 1600, level = 18, extraSkillIds = listOf(200031)),
                BattleHeroSpec(heroId = 100023, position = 2, troops = 1500, level = 15, extraSkillIds = listOf(200002)),
            ),
        )
        val defender = builder.build(
            listOf(
                BattleHeroSpec(heroId = 100352, position = 0, troops = 1400, level = 12, equipmentIds = listOf(1025)),
                BattleHeroSpec(heroId = 100345, position = 1, troops = 1400, level = 12),
                BattleHeroSpec(heroId = 100344, position = 2, troops = 1400, level = 12),
            ),
        )

        val result = BattleEngine.resolve(
            request = BattleRequest(attacker = attacker, defender = defender, maxRounds = 8),
            config = repo,
            random = FixedBattleRandom(0),
        )
        val report = BattleReportCodec.toCompressedClientReport(result)

        assertTrue(attacker.heroes.any { it.equipmentIds.isNotEmpty() })
        assertTrue(result.events.any { it is BattleEvent.SkillDamage || it is BattleEvent.UnsupportedSkillEffect })
        assertTrue(result.events.last() is BattleEvent.BattleEnd)
        assertTrue(report.startsWith("zzz"))
        assertTrue(report.length > 120)

        val text = GZIPInputStream(
            ByteArrayInputStream(Base64.getDecoder().decode(report.removePrefix("zzz"))),
        ).reader(Charsets.UTF_8).readText()
        val records = text.split("#")

        assertEquals(1, records.count { it == "04" })
        assertEquals(
            result.events.filterIsInstance<BattleEvent.RoundStart>().size,
            records.count { it.startsWith("09") },
        )
        assertTrue(records.none { it.startsWith("0u") })
        assertTrue(records.any { it.startsWith("68") })
        assertTrue(records.any { it == "0d" })
    }
}
