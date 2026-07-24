package com.stzb.server.game

data class PlayerConscriptResult(
    val armyId: Int,
    val updatedHeroes: List<PlayerHero>,
)

class PlayerConscriptService {
    fun conscript(state: PlayerState, request: ConscriptRequest): PlayerConscriptResult {
        val updated = mutableListOf<PlayerHero>()
        request.allocations.forEach { allocation ->
            val hero = state.hero(allocation.heroUid) ?: return@forEach
            val missing = (MAX_TROOPS - hero.troops).coerceAtLeast(0)
            val count = minOf(allocation.count, missing)
            if (count <= 0) return@forEach

            hero.troops += count
            updated.add(hero)
        }
        return PlayerConscriptResult(
            armyId = state.primaryArmyId(),
            updatedHeroes = updated,
        )
    }

    private companion object {
        private const val MAX_TROOPS = 1_000
    }
}
