package com.stzb.server.session

import com.stzb.server.game.FilePlayerRepository
import com.stzb.server.game.GameResponses
import com.stzb.server.game.PlayerStateRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SessionTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `same account receives stable user id across sessions`() {
        try {
            PlayerStateRepository.configure(
                FilePlayerRepository(createTempDirectory("stzb-session")),
            )

            val first = Session.create("account-a")
            val second = Session.create("account-a")
            val other = Session.create("account-b")

            assertEquals(first.accountKey, second.accountKey)
            assertEquals(first.userId, second.userId)
            assertNotEquals(first.userId, other.userId)
            assertNotEquals(first.sid.toList(), second.sid.toList())
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `login snapshot uses the persistent account state`() {
        val root = createTempDirectory("stzb-login-snapshot")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val state = PlayerStateRepository.getOrCreate("account-login", 100001, "已保存角色")
            val hero = state.addHero(100017, 1_700_000_000)
            hero.troops = 432
            PlayerStateRepository.save(state)

            val response = mapper.readTree(
                GameResponses.loginSuccess(
                    userId = state.userId,
                    cityWid = state.cityWid,
                    roleName = state.roleName,
                    serverTimeSec = 1_700_000_001,
                    serverOpenTime = 1_600_000_000,
                    cfgDataIndex = 2001,
                    accountKey = state.accountKey,
                ),
            )
            val tables = response[4][0]
            val entries = tables.drop(1)
            val heroTable = entries.first { it[0].asText() == "Tb_hero" }

            assertEquals("已保存角色", entries.first { it[0].asText() == "Tb_user" }[1][0][6].asText())
            assertEquals(hero.heroUid, heroTable[1][0][0].asInt())
            assertEquals(432, heroTable[1][0][11].asInt())
        } finally {
            PlayerStateRepository.reset()
        }
    }
}
