package dev.k3vinb5.freesocial.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Monday 2026-11-02. Bedtime 22:30, wake 07:00, wind-down 30, morning-lock 30.
        assertFalse(defaults.isLocked(at(2026, 11, 2, 12, 0)))
        assertTrue(defaults.isLocked(at(2026, 11, 2, 23, 0)))
        assertTrue(defaults.isLocked(at(2026, 11, 3, 2, 0)))
        assertTrue(defaults.isLocked(at(2026, 11, 3, 7, 29)))
        assertFalse(defaults.isLocked(at(2026, 11, 3, 8, 0)))
    }

    @Test
    fun `window edges are exact`() {
        assertFalse(defaults.isLocked(at(2026, 11, 2, 21, 59, 59)))
        assertTrue(defaults.isLocked(at(2026, 11, 2, 22, 0, 0)))
        assertTrue(defaults.isLocked(at(2026, 11, 3, 7, 29, 59)))
        assertFalse(defaults.isLocked(at(2026, 11, 3, 7, 30, 0)))
    }

    @Test
    fun `wind-down and morning-lock expand the window`() {
        val tight = ScheduleState(windDownMinutes = 0, morningLockMinutes = 0)
        assertFalse(tight.isLocked(at(2026, 11, 2, 22, 29)))
        assertTrue(tight.isLocked(at(2026, 11, 2, 22, 30)))
        assertTrue(tight.isLocked(at(2026, 11, 3, 6, 59)))
        assertFalse(tight.isLocked(at(2026, 11, 3, 7, 0)))

        val wide = ScheduleState(windDownMinutes = 60, morningLockMinutes = 90)
        assertTrue(wide.isLocked(at(2026, 11, 2, 21, 30)))
        assertTrue(wide.isLocked(at(2026, 11, 3, 8, 29)))
        assertFalse(wide.isLocked(at(2026, 11, 3, 8, 30)))
    }

    @Test
    fun `next boundary is the next flip after now`() {
        // Monday afternoon -> tonight's start.
        assertEquals(at(2026, 11, 2, 22, 0), defaults.nextBoundary(at(2026, 11, 2, 12, 0)))
        // Already locked -> the morning end.
        assertEquals(at(2026, 11, 3, 7, 30), defaults.nextBoundary(at(2026, 11, 2, 23, 0)))
        // Exactly at the end -> next start, never the same instant.
        assertEquals(at(2026, 11, 3, 22, 0), defaults.nextBoundary(at(2026, 11, 3, 7, 30)))
        // Exactly at the start -> the end.
        assertEquals(at(2026, 11, 3, 7, 30), defaults.nextBoundary(at(2026, 11, 2, 22, 0)))
        assertTrue(defaults.nextBoundary(at(2026, 11, 2, 12, 0)).isAfter(at(2026, 11, 2, 12, 0)))
    }

    @Test
    fun `per-day override only affects that weekday`() {
        // Friday later than the rest of the week: 23:30 -> 09:00.
        val schedule = ScheduleState(days = mapOf(5 to DaySchedule(23 * 60 + 30, 9 * 60)))
        // Wednesday 2026-11-04 uses the defaults.
        assertTrue(schedule.isLocked(at(2026, 11, 4, 22, 45)))
        // Friday 2026-11-06 starts at 23:00 (23:30 minus 30 min wind-down).
        assertFalse(schedule.isLocked(at(2026, 11, 6, 22, 45)))
        assertTrue(schedule.isLocked(at(2026, 11, 6, 23, 30)))
        // ... and ends at 09:30 on Saturday.
        assertTrue(schedule.isLocked(at(2026, 11, 7, 9, 29)))
        assertFalse(schedule.isLocked(at(2026, 11, 7, 9, 30)))
        assertEquals(at(2026, 11, 7, 9, 30), schedule.nextBoundary(at(2026, 11, 6, 23, 30)))
    }

    @Test
    fun `spring forward night keeps a sane window`() {
        // Europe/Lisbon 2026-03-29: 01:00 -> 02:00 never happens. The window opened
        // Saturday 22:00 (WET, +00:00) and closes Sunday 07:30 (WEST, +01:00).
        val start = at(2026, 3, 28, 22, 0)
        val end = at(2026, 3, 29, 7, 30)
        assertTrue(defaults.isLocked(at(2026, 3, 29, 0, 59)))
        assertTrue(defaults.isLocked(at(2026, 3, 29, 2, 30)))
        assertTrue(defaults.isLocked(at(2026, 3, 29, 5, 0)))
        assertFalse(defaults.isLocked(end))
        assertEquals(end, defaults.nextBoundary(start))
        // 9.5 wall-clock hours minus the skipped hour.
        assertEquals(Duration.ofMinutes(8 * 60 + 30), Duration.between(start, end))
    }

    @Test
    fun `bedtime inside the spring forward gap resolves instead of throwing`() {
        // Bedtime 01:30 does not exist on 2026-03-29; Java moves it to 02:30.
        val schedule = ScheduleState(
            days = (1..7).associateWith { DaySchedule(90, 7 * 60) },
            windDownMinutes = 0,
            morningLockMinutes = 0,
        )
        assertFalse(schedule.isLocked(at(2026, 3, 29, 2, 29, 59)))
        assertTrue(schedule.isLocked(at(2026, 3, 29, 2, 30, 0)))
        assertFalse(schedule.isLocked(at(2026, 3, 29, 7, 0, 0)))
    }
}
