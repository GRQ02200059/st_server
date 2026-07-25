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
            val missing = (PlayerHero.MAX_TROOPS - hero.troops).coerceAtLeast(0)
            val count = minOf(allocation.count, missing)
            if (count <= 0) return@forEach

            hero.troops += count
            updated.add(hero)
        }
        val armyId = request.allocations
            .asSequence()
            .mapNotNull { allocation -> state.hero(allocation.heroUid)?.armyId }
            .firstOrNull { it > 0 }
            ?: state.primaryArmyId()
        return PlayerConscriptResult(
            armyId = armyId,
            updatedHeroes = updated,
        )
    }
}
