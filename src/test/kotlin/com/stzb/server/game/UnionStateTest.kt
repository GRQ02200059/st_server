package com.stzb.server.game

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UnionStateTest {
    @Test
    fun `created union survives repository reconfiguration`() {
        val root = createTempDirectory("stzb-union")
        try {
            UnionStateRepository.configure(root)
            val leader = PlayerState(userId = 10_001, cityWid = 15_061_506, roleName = "盟主")

            val unionId = UnionStateRepository.create(leader, "洛阳同盟", nowSec = 1_700_000_000)

            UnionStateRepository.configure(root)
            val restored = assertNotNull(UnionStateRepository.find(unionId))
            assertEquals("洛阳同盟", restored.name)
            assertEquals(leader.userId, restored.leaderUserId)
            assertEquals(setOf(leader.userId), restored.memberUserIds)
            assertEquals(unionId, UnionStateRepository.forUser(leader.userId)?.unionId)
        } finally {
            UnionStateRepository.reset()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `union name is unique and a member create request is idempotent`() {
        val root = createTempDirectory("stzb-union-unique")
        try {
            UnionStateRepository.configure(root)
            val first = PlayerState(userId = 10_001, cityWid = 1, roleName = "甲")
            val second = PlayerState(userId = 10_002, cityWid = 2, roleName = "乙")

            val unionId = UnionStateRepository.create(first, "唯一名称", nowSec = 1)

            assertEquals(unionId, UnionStateRepository.create(first, "另一名称", nowSec = 2))
            assertEquals(0, UnionStateRepository.create(second, "唯一名称", nowSec = 3))
        } finally {
            UnionStateRepository.reset()
            root.toFile().deleteRecursively()
        }
    }
}
