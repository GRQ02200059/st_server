package com.stzb.server.game

object GameStateStore {
    fun getBuildLevel(cityWid: Int, buildId: Int): Int =
        PlayerStateRepository.getOrCreate(userId = 10001, cityWid = cityWid, roleName = "主公").buildLevel(buildId)

    fun upgradeBuild(cityWid: Int, buildId: Int, targetLevel: Int): Int {
        return PlayerStateRepository.getOrCreate(userId = 10001, cityWid = cityWid, roleName = "主公")
            .upgradeBuild(buildId, targetLevel)
    }

    fun saveTeam(userId: Int, heroes: List<Int>) {
        PlayerStateRepository.getOrCreate(userId = userId, cityWid = 100001, roleName = "主公")
            .saveTeam(heroes)
    }

    fun getTeam(userId: Int): List<Int> =
        PlayerStateRepository.getOrCreate(userId = userId, cityWid = 100001, roleName = "主公").teamHeroes()
}
