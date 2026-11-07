package dev.k3vinb5.freesocial.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleStateTest {

    private val zone = ZoneId.of("Europe/Lisbon")
    private val defaults = ScheduleState()

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone)

    @Test
    fun `default window wraps overnight`() {
        // Monday 2026-11-02. Bedtime 22:30, wake 07:00; the window is exactly that span.
        assertFalse(defaults.isLocked(at(2026, 11, 2, 12, 0)))
        assertTrue(defaults.isLocked(at(2026, 11, 2, 22, 30)))
        assertTrue(defaults.isLocked(at(2026, 11, 2, 23, 0)))
        assertTrue(defaults.isLocked(at(2026, 11, 3, 2, 0)))
        assertTrue(defaults.isLocked(at(2026, 11, 3, 6, 59)))
        assertFalse(defaults.isLocked(at(2026, 11, 3, 7, 0)))
    }

    @Test
    fun `window edges are exact`() {
        assertFalse(defaults.isLocked(at(2026, 11, 2, 22, 29, 59)))
        assertTrue(defaults.isLocked(at(2026, 11, 2, 22, 30, 0)))
        assertTrue(defaults.isLocked(at(2026, 11, 3, 6, 59, 59)))
        assertFalse(defaults.isLocked(at(2026, 11, 3, 7, 0, 0)))
    }

    @Test
    fun `no expansion exists any more - the window is bedtime to wake`() {
        // 22:30 wind-down or 07:00 morning-lock would show up as locked at 22:29 / 07:01; it must not.
        assertFalse(defaults.isLocked(at(2026, 11, 2, 22, 0)))
        assertFalse(defaults.isLocked(at(2026, 11, 3, 7, 1)))
        assertEquals(8 * 60 + 30, defaults.daySchedule(1).windowMinutes)
    }

    @Test
    fun `default window length is eight and a half hours`() {
        val day = defaults.daySchedule(1)
        assertEquals(DaySchedule(22 * 60 + 30, 7 * 60), day)
        assertEquals(510, day.windowMinutes)
        assertTrue(day.meetsMinimum)
        // Every default weekday clears the six-hour floor.
        for (weekday in 1..7) {
            assertTrue(defaults.daySchedule(weekday).meetsMinimum)
        }
    }

    @Test
    fun `six hours is the floor and shorter windows are rejected`() {
        assertEquals(360, DaySchedule(22 * 60, 4 * 60).windowMinutes)
        assertTrue(DaySchedule(22 * 60, 4 * 60).meetsMinimum)
        assertEquals(359, DaySchedule(22 * 60, 3 * 60 + 59).windowMinutes)
        assertFalse(DaySchedule(22 * 60, 3 * 60 + 59).meetsMinimum)

        // A schedule may not even be constructed with a sub-six-hour day.
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleState(days = mapOf(1 to DaySchedule(22 * 60, 2 * 60)))
        }
        // Exactly six hours is accepted.
        val tight = ScheduleState(days = (1..7).associateWith { DaySchedule(22 * 60, 4 * 60) })
        assertTrue(tight.isLocked(at(2026, 11, 2, 22, 0)))
        assertFalse(tight.isLocked(at(2026, 11, 3, 4, 0)))
    }

    @Test
    fun `equal bedtime and wake is a full day window`() {
        assertEquals(24 * 60, DaySchedule(9 * 60, 9 * 60).windowMinutes)
        assertTrue(DaySchedule(9 * 60, 9 * 60).meetsMinimum)
    }

    @Test
    fun `next boundary is the next flip after now`() {
        // Monday afternoon -> tonight's start.
        assertEquals(at(2026, 11, 2, 22, 30), defaults.nextBoundary(at(2026, 11, 2, 12, 0)))
        // Already locked -> the morning end.
        assertEquals(at(2026, 11, 3, 7, 0), defaults.nextBoundary(at(2026, 11, 2, 23, 0)))
        // Exactly at the end -> next start, never the same instant.
        assertEquals(at(2026, 11, 3, 22, 30), defaults.nextBoundary(at(2026, 11, 3, 7, 0)))
        // Exactly at the start -> the end.
        assertEquals(at(2026, 11, 3, 7, 0), defaults.nextBoundary(at(2026, 11, 2, 22, 30)))
        assertTrue(defaults.nextBoundary(at(2026, 11, 2, 12, 0)).isAfter(at(2026, 11, 2, 12, 0)))
    }

    @Test
    fun `per-day override only affects that weekday`() {
        // Friday later than the rest of the week: 23:30 -> 09:00 (9 h 30 m).
        val schedule = ScheduleState(days = mapOf(5 to DaySchedule(23 * 60 + 30, 9 * 60)))
        // Wednesday 2026-11-04 uses the defaults.
        assertTrue(schedule.isLocked(at(2026, 11, 4, 22, 45)))
        // Friday 2026-11-06 starts at 23:30 with no wind-down.
        assertFalse(schedule.isLocked(at(2026, 11, 6, 22, 45)))
        assertTrue(schedule.isLocked(at(2026, 11, 6, 23, 30)))
        // ... and ends at 09:00 on Saturday, with no morning-lock.
        assertTrue(schedule.isLocked(at(2026, 11, 7, 8, 59)))
        assertFalse(schedule.isLocked(at(2026, 11, 7, 9, 0)))
        assertEquals(at(2026, 11, 7, 9, 0), schedule.nextBoundary(at(2026, 11, 6, 23, 30)))
    }

    @Test
    fun `spring forward night keeps a sane window`() {
        // Europe/Lisbon 2026-03-29: 01:00 -> 02:00 never happens. The window opened
        // Saturday 22:30 (WET, +00:00) and closes Sunday 07:00 (WEST, +01:00).
        val start = at(2026, 3, 28, 22, 30)
        val end = at(2026, 3, 29, 7, 0)
        assertTrue(defaults.isLocked(at(2026, 3, 29, 0, 59)))
        assertTrue(defaults.isLocked(at(2026, 3, 29, 2, 30)))
        assertTrue(defaults.isLocked(at(2026, 3, 29, 5, 0)))
        assertFalse(defaults.isLocked(end))
        assertEquals(end, defaults.nextBoundary(start))
        // 8.5 wall-clock hours minus the skipped hour.
        assertEquals(Duration.ofMinutes(7 * 60 + 30), Duration.between(start, end))
    }

    @Test
    fun `bedtime inside the spring forward gap resolves instead of throwing`() {
        // Bedtime 01:30 does not exist on 2026-03-29; Java moves it to 02:30.
        val schedule = ScheduleState(days = (1..7).associateWith { DaySchedule(90, 8 * 60) })
        assertFalse(schedule.isLocked(at(2026, 3, 29, 2, 29, 59)))
        assertTrue(schedule.isLocked(at(2026, 3, 29, 2, 30, 0)))
        assertFalse(schedule.isLocked(at(2026, 3, 29, 8, 0, 0)))
    }
}
