package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevenueServiceTest {
    @Test
    fun `first ordinary collection initializes window and grants local revenue`() {
        val state = emptyState()

        val amount = RevenueService.collectOrdinary(state, nowSec = 100)

        assertEquals(RevenueService.REVENUE_AMOUNT, amount)
        assertEquals(RevenueService.REVENUE_AMOUNT, state.resources.money)
        assertEquals(RevenueService.REVENUE_AMOUNT, state.resources.moneyAccumulated)
        assertEquals(listOf(RevenueCollection(100, RevenueService.REVENUE_AMOUNT)), state.revenue.collections)
        assertEquals(listOf(RevenueGift(RevenueService.REVENUE_AMOUNT)), state.revenue.gifts)
        assertEquals(100, state.revenue.revenueTime)
        assertEquals(100 + RevenueService.RESET_WINDOW_SECONDS, state.revenue.nextRefreshTime)
    }

    @Test
    fun `ordinary collection enforces cooldown boundary and three collection limit`() {
        val state = emptyState()
        assertEquals(RevenueService.REVENUE_AMOUNT, RevenueService.collectOrdinary(state, 100))

        assertNull(RevenueService.collectOrdinary(state, 100 + RevenueService.NORMAL_COOLDOWN_SECONDS - 1))
        assertEquals(
            RevenueService.REVENUE_AMOUNT,
            RevenueService.collectOrdinary(state, 100 + RevenueService.NORMAL_COOLDOWN_SECONDS),
        )
        assertEquals(
            RevenueService.REVENUE_AMOUNT,
            RevenueService.collectOrdinary(state, 100 + RevenueService.NORMAL_COOLDOWN_SECONDS * 2),
        )
        assertNull(
            RevenueService.collectOrdinary(state, 100 + RevenueService.NORMAL_COOLDOWN_SECONDS * 3),
        )
        assertEquals(RevenueService.NORMAL_LIMIT, state.revenue.collections.size)
    }

    @Test
    fun `rejected ordinary collection does not retain initial window setup`() {
        val state = emptyState()
        state.revenue.collections += RevenueCollection(100, RevenueService.REVENUE_AMOUNT)
        state.revenue.gifts += RevenueGift(RevenueService.REVENUE_AMOUNT)
        state.revenue.revenueTime = 100
        val before = state.toSnapshot()

        assertNull(RevenueService.collectOrdinary(state, 101))

        assertEquals(before, state.toSnapshot())
    }

    @Test
    fun `ordinary collection initializes a missing window without discarding current state`() {
        val state = emptyState()
        state.revenue.collections += RevenueCollection(10, RevenueService.REVENUE_AMOUNT)
        state.revenue.gifts += RevenueGift(RevenueService.REVENUE_AMOUNT)

        assertEquals(RevenueService.REVENUE_AMOUNT, RevenueService.collectOrdinary(state, 100))

        assertEquals(2, state.revenue.collections.size)
        assertEquals(2, state.revenue.gifts.size)
        assertEquals(100 + RevenueService.RESET_WINDOW_SECONDS, state.revenue.nextRefreshTime)
    }

    @Test
    fun `ordinary collection resets expired window before applying grant`() {
        val state = emptyState()
        state.revenue.collections += RevenueCollection(10, RevenueService.REVENUE_AMOUNT)
        state.revenue.gifts += RevenueGift(RevenueService.REVENUE_AMOUNT)
        state.revenue.revenueTime = 10
        state.revenue.nextRefreshTime = 100
        state.revenue.forceCount = 2

        assertEquals(RevenueService.REVENUE_AMOUNT, RevenueService.collectOrdinary(state, 100))

        assertEquals(listOf(RevenueCollection(100, RevenueService.REVENUE_AMOUNT)), state.revenue.collections)
        assertEquals(listOf(RevenueGift(RevenueService.REVENUE_AMOUNT)), state.revenue.gifts)
        assertEquals(0, state.revenue.forceCount)
        assertEquals(100 + RevenueService.RESET_WINDOW_SECONDS, state.revenue.nextRefreshTime)
    }

    @Test
    fun `ordinary and double grants saturate integer balances`() {
        val state = emptyState()
        state.resources.money = Int.MAX_VALUE - 1
        state.resources.moneyAccumulated = Int.MAX_VALUE - 1

        assertEquals(RevenueService.REVENUE_AMOUNT, RevenueService.collectOrdinary(state, 100))
        assertEquals(Int.MAX_VALUE, state.resources.money)
        assertEquals(Int.MAX_VALUE, state.resources.moneyAccumulated)

        assertEquals(RevenueService.REVENUE_AMOUNT, RevenueService.claimDouble(state, 0))
        assertEquals(Int.MAX_VALUE, state.resources.money)
        assertEquals(Int.MAX_VALUE, state.resources.moneyAccumulated)
    }

    @Test
    fun `double reward accepts one valid gift and projections reflect claim`() {
        val state = emptyState()
        RevenueService.collectOrdinary(state, 100)

        assertEquals("100,6500;", RevenueService.revenueInfo(state.revenue))
        assertEquals("6500,0,0;", RevenueService.lastRevenueInfo(state.revenue))
        assertEquals(RevenueService.REVENUE_AMOUNT, RevenueService.claimDouble(state, 0))
        assertEquals("6500,0,1;", RevenueService.lastRevenueInfo(state.revenue))
        assertTrue(state.revenue.gifts.single().claimed)
        assertNull(RevenueService.claimDouble(state, 0))
        assertNull(RevenueService.claimDouble(state, -1))
        assertNull(RevenueService.claimDouble(state, 1))
        assertEquals(RevenueService.REVENUE_AMOUNT * 2, state.resources.money)
        assertEquals(RevenueService.REVENUE_AMOUNT * 2, state.resources.moneyAccumulated)
    }

    @Test
    fun `invalid double claims do not mutate state`() {
        val state = emptyState()
        RevenueService.collectOrdinary(state, 100)
        val before = state.toSnapshot()

        assertNull(RevenueService.claimDouble(state, 1))
        assertFalse(state.revenue.gifts.single().claimed)
        assertEquals(before, state.toSnapshot())
    }

    private fun emptyState(): PlayerState =
        PlayerState(
            userId = 40,
            cityWid = 10040,
            roleName = "主公",
            accountKey = "revenue-service-test",
        ).also {
            it.resources.money = 0
            it.resources.moneyAccumulated = 0
        }
}
