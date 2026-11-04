package dev.k3vinb5.freesocial.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ShortVideoBudgetTest {

    private val zone = ZoneId.of("Europe/Lisbon")
    private val budget = ShortVideoBudget(limitMinutes = 2)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    @Test
    fun `all three apps share one counter`() {
        val now = at(2026, 11, 4, 12, 0)
        budget.record(40, now) // Instagram clips viewer
        budget.record(20, now) // YouTube Shorts player
        budget.record(35, now) // TikTok playback
        assertEquals(95L, budget.usedToday(now))
    }

    @Test
    fun `counter resets at local midnight`() {
        budget.record(100, at(2026, 11, 4, 23, 59))
        assertEquals(100L, budget.usedToday(at(2026, 11, 4, 23, 59)))
        assertEquals(0L, budget.usedToday(at(2026, 11, 5, 0, 0)))
        budget.record(5, at(2026, 11, 5, 0, 1))
        assertEquals(5L, budget.usedToday(at(2026, 11, 5, 12, 0)))
    }

    @Test
    fun `exhausted exactly at the limit`() {
        val now = at(2026, 11, 4, 12, 0)
        budget.record(119, now)
        assertFalse(budget.isExhausted(now, 2))
        assertEquals(1L, budget.remainingSeconds(now, 2))
        budget.record(1, now)
        assertTrue(budget.isExhausted(now, 2))
        assertEquals(0L, budget.remainingSeconds(now, 2))
    }

    @Test
    fun `remaining is clamped and rounds up to minutes`() {
        val now = at(2026, 11, 4, 12, 0)
        budget.record(61, now)
        assertEquals(59L, budget.remainingSeconds(now, 2))
        assertEquals(1, budget.remainingMinutesCeil(now, 2))
        budget.record(500, now)
        assertEquals(0L, budget.remainingSeconds(now, 2))
        assertEquals(0, budget.remainingMinutesCeil(now, 2))
    }

    @Test
    fun `changing the limit applies to existing usage`() {
        val now = at(2026, 11, 4, 12, 0)
        budget.record(120, now)
        // 2 min limit -> nothing left; 10 min limit -> 8 min left, no backfill of unused time.
        assertTrue(budget.isExhausted(now, 2))
        assertEquals(480L, budget.remainingSeconds(now, 10))
        assertFalse(budget.isExhausted(now, 10))
        assertEquals(120L, budget.usedToday(now))
        // Default limit comes from the constructor.
        assertEquals(0L, budget.remainingSeconds(now))
    }

    @Test
    fun `non-positive ticks are ignored`() {
        val now = at(2026, 11, 4, 12, 0)
        budget.record(0, now)
        budget.record(-30, now)
        assertEquals(0L, budget.usedToday(now))
    }
}
