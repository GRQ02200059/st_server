package com.stzb.server.game.battle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientBattleTextReplayProtocolTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `reference cmd 11 contains every text replay action family`() {
        val report = ZipFile(Path.of("assent/cfg/paper.zip").toFile()).use { zip ->
            val entry = zip.getEntry("0000000b/cap_20260311222842345_0000000b_zlib.json")!!
            zip.getInputStream(entry).bufferedReader().use { reader ->
                mapper.readTree(reader)[1]["report"].asText()
            }
        }
        val ids = report.split("#").filter(String::isNotBlank).map { record ->
            record.take(2).toInt(36)
        }

        assertEquals(1, ids.count { it == ClientBattleTextReplayProtocol.PREPARE })
        assertEquals(8, ids.count { it == ClientBattleTextReplayProtocol.ROUND })
        assertTrue(
            setOf(
                ClientBattleTextReplayProtocol.HERO_NAME,
                ClientBattleTextReplayProtocol.NORMAL_DAMAGE,
                ClientBattleTextReplayProtocol.SKILL_CAST,
                ClientBattleTextReplayProtocol.SKILL_DAMAGE,
                ClientBattleTextReplayProtocol.ONGOING_DAMAGE,
                ClientBattleTextReplayProtocol.RECOVERY,
                ClientBattleTextReplayProtocol.STATUS,
                ClientBattleTextReplayProtocol.END,
                ClientBattleTextReplayProtocol.FINAL_TROOPS,
            ).all(ids::contains),
        )
    }
}
