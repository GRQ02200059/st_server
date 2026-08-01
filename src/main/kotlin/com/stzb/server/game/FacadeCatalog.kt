package com.stzb.server.game

/**
 * Ownership catalogs extracted from the client's cfg-5-compatible facade
 * tables. These are configuration IDs, not display or item IDs.
 */
object FacadeCatalog {
    /** Official main-mansion facade from a valid 9.2.2 login snapshot. */
    const val DEFAULT_CITY_FACADE_ID = 113305
    const val DEFAULT_CITY_FACADE_POS = 50005
    const val DEFAULT_CITY_WALL_FACADE_ID = 129901
    const val DEFAULT_CITY_WALL_POS = 0

    /**
     * Server-side encoded city layout. The first character is intentionally a
     * double quote because MapCityData.ChangeIndexWithServer treats it as data.
     */
    const val DEFAULT_CITY_MAP_FACADE = "\"4P-e0Go[=)')(',0(*',(,-*)"

    /** Official Tb_world_city.facade3d format for a fully renderable city. */
    const val DEFAULT_CITY_BUILD_DATA =
        "10,8,13,20,20,20,21,20,22,20,23,20,24,20,25,1,30,20," +
            "31,10,32,10,33,10,34,10,35,10,36,20,37,10,40,5,42,5," +
            "43,15,44,3,51,10,52,10,53,10,54,10,61,5,62,6,63,5," +
            "64,5,65,5,66,10,67,3,160,10"

    /** Official USER_WORLD_CITY custom layout consumed by the map city renderer. */
    const val DEFAULT_CITY_CUSTOM_VIEW =
        "1112130,20004,110005;1121120,120004,120003;1122050,100010;" +
            "1122070,100003;1122090,100008;1122140,100009;1133050,50005;" +
            "1212010,10008;1222040,50011;1222060,10002;1233080,50003;" +
            "1299010,0;1322030,30004;1322110,90011;1322130,120005;" +
            "1333070,40008;1333090,80002;1333150,20011;"

    val armyFacadeIds: List<Int>
        get() = ArmyFacadeCatalog.standardFacadeIds()

    val cityFacadeIds: List<Int> = listOf(
        111213, 112112, 112205, 112206, 112207, 112208, 112209, 112210, 112211, 112214,
        113301, 113302, 113303, 113304, 113305, 121201, 122102, 122203, 122204, 122205,
        122206, 122207, 123308, 123309, 129901, 132201, 132202, 132203, 132211, 132213,
        132214, 133305, 133307, 133308, 133309, 133315, 133316, 211205, 211206, 212203,
        212204, 213301, 213302, 222207, 222217, 229908, 232310, 242212, 242213, 242214,
        242215, 242216, 243211, 343301, 343302, 343303, 343304, 343305, 343306, 343307,
        343308, 343309, 343310, 343311, 344401, 412201, 413301, 429901, 432201, 442201,
        442202, 442203, 442204, 442205, 443301, 443302, 443303, 511101, 511401, 512101,
        512201, 512202, 512203, 513201, 513301, 513302, 541101, 541201, 542101, 542102,
        542103, 542201, 542301, 641106, 642510, 643304, 643305, 643307, 643309, 644403,
        645501, 649902, 712201, 712202, 713301, 742201, 742202, 742203, 742204, 742205,
        742206, 742207, 743301, 743302, 813301, 912201, 912203, 912301, 913301, 921101,
        921102, 921301, 923201, 923301, 923302, 931101, 942101, 942202, 942203, 943101,
        1012201, 1012202, 1012203, 1012204, 1022201, 1023301, 1031101, 1041401, 1042201,
        1042202, 1042203, 1042204, 1043301, 1043302, 1043303, 1112202, 1112203, 1123301,
        1132201, 1142203, 1142204, 1142205, 1142206, 1142207, 1142208, 1143301, 1143302,
    )
}

object CityFacadeLayout {
    private val supportedFacadeIds = FacadeCatalog.cityFacadeIds.toSet()

    fun normalize(serialized: String): String? {
        val trimmed = serialized.trim().trimEnd(';')
        if (trimmed.isEmpty()) return null
        val rawPlacements = trimmed.split(';')
        val placements = rawPlacements.mapNotNull(::parsePlacement)
        if (placements.size != rawPlacements.size) return null
        if (placements.map(CityFacadePlacement::position).toSet().size != placements.size) return null
        return "$trimmed;"
    }

    private fun parsePlacement(entry: String): CityFacadePlacement? {
        val values = entry.split(',')
        if (values.size !in 2..3) return null
        val encodedFacade = values[0].toIntOrNull() ?: return null
        val position = values[1].toIntOrNull() ?: return null
        if (values.size == 3 && values[2].toIntOrNull() == null) return null
        if (encodedFacade <= 0 || encodedFacade % 10 != 0 || position < 0) return null
        val facadeId = encodedFacade / 10
        if (facadeId !in supportedFacadeIds) return null
        return CityFacadePlacement(facadeId, position)
    }

    private data class CityFacadePlacement(
        val facadeId: Int,
        val position: Int,
    )
}
