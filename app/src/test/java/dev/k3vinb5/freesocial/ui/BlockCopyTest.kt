package dev.k3vinb5.freesocial.ui

import dev.k3vinb5.freesocial.block.BlockReason
import dev.k3vinb5.freesocial.core.DaySchedule
import dev.k3vinb5.freesocial.core.ScheduleState
import dev.k3vinb5.freesocial.settings.SettingsRepository
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D14 — the block-screen body is chosen from the gate reason, not from pre-rendered text. The
 * wake-time body must follow the live schedule (a per-day override), so the clock is a parameter.
 */
class BlockCopyTest {

    private val appBody = "This app is blocked by Free Social."
    private val lisbon = ZoneId.of("Europe/Lisbon")

    @Test
    fun `the sleep window shows the wake-time body`() {
        val wakeBody = "Focus time — come back at 07:00."

        assertEquals(wakeBody, BlockCopy.body(BlockReason.SCHEDULE, wakeBody, appBody))
    }

    @Test
    fun `the app reason keeps the generic body`() {
        assertEquals(appBody, BlockCopy.body(BlockReason.APP_BLOCK, "wake copy", appBody))
    }

    @Test
    fun `a missing or unparsable reason falls back to the generic body`() {
        assertEquals(appBody, BlockCopy.body(null, "wake copy", appBody))
    }

    @Test
    fun `the wake clock follows a per-day override`() {
        // Wednesday (ISO 3) night runs 22:30 -> 08:15 instead of the 07:00 default; the other
        // days keep the shipped default. The window that covers Thursday 02:00 started Wednesday.
        val schedule = ScheduleState(
            mapOf(3 to DaySchedule(bedtimeMinutes = 22 * 60 + 30, wakeMinutes = 8 * 60 + 15)),
        )
        val fakeNow = ZonedDateTime.of(2026, 11, 12, 2, 0, 0, 0, lisbon) // Thursday

        assertTrue("the fake clock is inside the override window", schedule.isLocked(fakeNow))
        val clock = SettingsRepository.formatClock(schedule.nextWakeMinutes(fakeNow), java.util.Locale.UK)

        assertEquals("08:15", clock)
        assertEquals(
            "Focus time — come back at 08:15.",
            BlockCopy.body(BlockReason.SCHEDULE, "Focus time — come back at $clock.", appBody),
        )
    }

    @Test
    fun `an untouched day keeps the default wake time`() {
        val schedule = ScheduleState()
        // Thursday 02:00 is inside Wednesday's default 22:30 -> 07:00 window.
        val fakeNow = ZonedDateTime.of(2026, 11, 12, 2, 0, 0, 0, lisbon)

        assertTrue(schedule.isLocked(fakeNow))
        assertEquals("07:00", SettingsRepository.formatClock(schedule.nextWakeMinutes(fakeNow), java.util.Locale.UK))
    }
}
