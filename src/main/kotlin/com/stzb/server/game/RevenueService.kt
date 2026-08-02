package com.stzb.server.game

object RevenueService {
    const val REVENUE_AMOUNT = 6500
    const val NORMAL_LIMIT = 3
    const val NORMAL_COOLDOWN_SECONDS = 7200
    const val RESET_WINDOW_SECONDS = 86400

    fun collectOrdinary(state: PlayerState, nowSec: Int): Int? =
        synchronized(state) {
            val revenue = state.revenue
            val updated = revenue.deepCopy()
            if (updated.nextRefreshTime == 0) {
                updated.nextRefreshTime = saturatedAdd(nowSec, RESET_WINDOW_SECONDS)
            } else if (nowSec >= updated.nextRefreshTime) {
                updated.collections.clear()
                updated.gifts.clear()
                updated.revenueTime = 0
                updated.forceCount = 0
                updated.nextRefreshTime = saturatedAdd(nowSec, RESET_WINDOW_SECONDS)
            }

            if (updated.collections.size >= NORMAL_LIMIT) return@synchronized null
            if (
                updated.revenueTime != 0 &&
                nowSec.toLong() - updated.revenueTime.toLong() < NORMAL_COOLDOWN_SECONDS
            ) {
                return@synchronized null
            }

            updated.collections += RevenueCollection(nowSec, REVENUE_AMOUNT)
            updated.gifts += RevenueGift(REVENUE_AMOUNT)
            updated.revenueTime = nowSec

            state.resources.money = saturatedAdd(state.resources.money, REVENUE_AMOUNT)
            state.resources.moneyAccumulated =
                saturatedAdd(state.resources.moneyAccumulated, REVENUE_AMOUNT)
            revenue.collections.clear()
            revenue.collections += updated.collections
            revenue.gifts.clear()
            revenue.gifts += updated.gifts
            revenue.revenueTime = updated.revenueTime
            revenue.nextRefreshTime = updated.nextRefreshTime
            revenue.forceCount = updated.forceCount
            REVENUE_AMOUNT
        }

    fun claimDouble(state: PlayerState, giftIndex: Int): Int? =
        synchronized(state) {
            val gift = state.revenue.gifts.getOrNull(giftIndex)
                ?.takeUnless(RevenueGift::claimed)
                ?: return@synchronized null
            state.resources.money = saturatedAdd(state.resources.money, gift.amount)
            state.resources.moneyAccumulated =
                saturatedAdd(state.resources.moneyAccumulated, gift.amount)
            gift.claimed = true
            gift.amount
        }

    fun revenueInfo(revenue: PlayerRevenueState): String =
        revenue.collections.joinToString(separator = "") {
            "${it.collectedAtSec},${it.amount};"
        }

    fun lastRevenueInfo(revenue: PlayerRevenueState): String =
        revenue.gifts.joinToString(separator = "") {
            "${it.amount},${it.extra},${if (it.claimed) 1 else 0};"
        }

    private fun saturatedAdd(value: Int, amount: Int): Int =
        (value.toLong() + amount.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
}
