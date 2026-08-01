package com.stzb.server.game

object CardBorderCatalog {
    const val DEFAULT_ID = 101260
    const val PANLONG_ID = 110997
    const val PANLONG_ATTACK_ID = 110998
    const val PANLONG_STRATEGY_ID = 110999

    private val supportedIds = setOf(
        0,
        DEFAULT_ID,
        PANLONG_ID,
        PANLONG_ATTACK_ID,
        PANLONG_STRATEGY_ID,
    )

    fun isSupported(cardBorder: Int): Boolean = cardBorder in supportedIds

    fun normalizePersisted(cardBorder: Int): Int =
        cardBorder.takeIf(::isSupported) ?: DEFAULT_ID

    fun normalBorderIds(): List<Int> =
        listOf(PANLONG_ID, PANLONG_ATTACK_ID, PANLONG_STRATEGY_ID)
}
