package com.stzb.server.game

/**
 * Main-city topology shared by the login snapshot and the 5026 world-scene
 * packet. A wid encodes coordinates as x * 10_000 + y.
 */
object HomeCity {
    fun suburbWids(cityWid: Int): List<Int> {
        val x = cityWid / WID_X_MULTIPLIER
        val y = cityWid % WID_X_MULTIPLIER
        return buildList(8) {
            for (xOffset in -1..1) {
                for (yOffset in -1..1) {
                    if (xOffset != 0 || yOffset != 0) {
                        add((x + xOffset) * WID_X_MULTIPLIER + y + yOffset)
                    }
                }
            }
        }
    }

    /**
     * Tb_user_build only needs a table-local stable key. The historical
     * cityWid * 1000 formula overflows near Luoyang; build IDs stay below 100,
     * so a factor of 100 is sufficient for the selected map coordinate.
     */
    fun userBuildId(cityWid: Int, buildId: Int): Int {
        require(buildId in 0 until BUILD_ID_FACTOR) { "不支持的建筑 ID: $buildId" }
        return Math.addExact(Math.multiplyExact(cityWid, BUILD_ID_FACTOR), buildId)
    }

    private const val WID_X_MULTIPLIER = 10_000
    private const val BUILD_ID_FACTOR = 100
}
