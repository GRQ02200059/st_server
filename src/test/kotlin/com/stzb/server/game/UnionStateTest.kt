package com.stzb.server.game

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class UnionStateTest {
    @Test
    fun `all returns an immutable sorted snapshot unaffected by repository reconfiguration`() {
        val firstRoot = createTempDirectory("stzb-union-all-first")
        val secondRoot = createTempDirectory("stzb-union-all-second")
        try {
            FileUnionRepository(firstRoot).save(
                UnionStateSnapshot(
                    nextUnionId = 1_004,
                    unions = listOf(
                        union(unionId = 1_003, name = "丙盟", memberUserIds = linkedSetOf(3, 1)),
                        union(unionId = 1_001, name = "甲盟", memberUserIds = linkedSetOf(2)),
                    ),
                ),
            )
            UnionStateRepository.configure(firstRoot)

            val snapshot = UnionStateRepository.all()

            assertEquals(listOf(1_001, 1_003), snapshot.map(PlayerUnion::unionId))
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (snapshot as MutableList<PlayerUnion>).clear()
            }
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (snapshot.last().memberUserIds as MutableSet<Int>).add(99)
            }

            UnionStateRepository.configure(secondRoot)

            assertEquals(listOf(1_001, 1_003), snapshot.map(PlayerUnion::unionId))
            assertEquals(setOf(1, 3), snapshot.last().memberUserIds)
            assertEquals(emptyList(), UnionStateRepository.all())
        } finally {
            UnionStateRepository.reset()
            firstRoot.toFile().deleteRecursively()
            secondRoot.toFile().deleteRecursively()
        }
    }

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

    private fun union(unionId: Int, name: String, memberUserIds: Set<Int>): PlayerUnion =
        PlayerUnion(
            unionId = unionId,
            name = name,
            leaderUserId = memberUserIds.first(),
            leaderRoleName = name,
            createdAtSec = 0,
            memberUserIds = memberUserIds,
        )
}
