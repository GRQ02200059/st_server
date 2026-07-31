package com.stzb.server.game

data class ArmyFacadeCardSeed(
    val cardId: Int,
    val facadeId: Int,
)

/**
 * The normal army facade list belongs to the client's current shop table.
 * Special facades use a different client ownership path and are listed here
 * explicitly so every server-side validation shares one boundary.
 */
object ArmyFacadeCatalog {
    const val COPIES_PER_STANDARD_FACADE = 5
    const val YUXI_FACADE_ID = 101073

    private const val RESOURCE = "tb_cfg_army_facade_shop.bin"
    private const val SPECIAL_CARD_UID_BASE = Int.MAX_VALUE - 10

    private val standardIds: List<Int> by lazy(::loadStandardFacadeIds)
    private val specialIds = listOf(101073, 101515, 101618, 101680)
    private val specialIdSet = specialIds.toSet()

    fun standardFacadeIds(): List<Int> = standardIds

    fun specialFacadeIds(): Set<Int> = specialIdSet

    fun isStandardFacade(facadeId: Int): Boolean = facadeId in standardIds

    fun isSpecialFacade(facadeId: Int): Boolean = facadeId in specialIdSet

    fun cardCount(): Int = standardIds.size * COPIES_PER_STANDARD_FACADE

    fun defaultCards(): List<ArmyFacadeCardSeed> =
        standardIds.flatMap { facadeId ->
            (1..COPIES_PER_STANDARD_FACADE).map { copyIndex ->
                ArmyFacadeCardSeed(cardId = cardId(facadeId, copyIndex), facadeId = facadeId)
            }
        }

    fun cardId(facadeId: Int, copyIndex: Int): Int {
        require(isStandardFacade(facadeId)) { "unsupported standard army facade: $facadeId" }
        require(copyIndex in 1..COPIES_PER_STANDARD_FACADE) {
            "invalid army facade copy index: $copyIndex"
        }
        return facadeId * 100 + copyIndex
    }

    fun specialCardUid(facadeId: Int): Int {
        val index = specialIds.indexOf(facadeId)
        require(index >= 0) { "unsupported special army facade: $facadeId" }
        return SPECIAL_CARD_UID_BASE + index
    }

    private fun loadStandardFacadeIds(): List<Int> {
        val bytes = ArmyFacadeCatalog::class.java
            .getResourceAsStream("/client-config/$RESOURCE")
            ?.use { it.readBytes() }
            ?: error("missing client configuration: /client-config/$RESOURCE")
        val table = MemoryPackTable.open(bytes, RESOURCE)
        return table.keys.map { key ->
            require(table.reader.byte().toInt() and 0xff == 9) {
                "invalid $RESOURCE row"
            }
            val heroId = table.reader.int()
            repeat(5) { table.reader.int() }
            repeat(3) { table.reader.int() }
            require(heroId == key && heroId > 0) {
                "invalid $RESOURCE key/hero pair: $key/$heroId"
            }
            heroId
        }.distinct().also { facadeIds ->
            require(facadeIds.isNotEmpty()) { "no standard army facades in $RESOURCE" }
        }
    }
}
