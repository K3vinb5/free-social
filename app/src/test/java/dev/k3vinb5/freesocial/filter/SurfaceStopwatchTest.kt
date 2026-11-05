package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.ShortVideoBudget
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceStopwatchTest {

    private val now: ZonedDateTime = ZonedDateTime.of(2026, 11, 4, 12, 0, 0, 0, ZoneId.of("Europe/Amsterdam"))

    @Test
    fun `ticks accumulate whole seconds on the surface`() {
        val stopwatch = SurfaceStopwatch()
        assertEquals(0, stopwatch.enter("$IG/clips_viewer", 0))
        assertEquals(1, stopwatch.tick(1_000))
        assertEquals(1, stopwatch.tick(2_000))
        assertEquals(0, stopwatch.tick(2_400))
        assertEquals(1, stopwatch.tick(3_000))
        assertEquals("$IG/clips_viewer", stopwatch.activeSurface)
    }

    @Test
    fun `leaving stops the clock and drops the partial second`() {
        val stopwatch = SurfaceStopwatch()
        stopwatch.enter("$TT/playback", 0)
        assertEquals(1, stopwatch.tick(1_000))
        assertEquals(2, stopwatch.leave(3_500))
        assertNull(stopwatch.activeSurface)
        assertEquals(0, stopwatch.tick(5_000))
        assertEquals(0, stopwatch.leave(6_000))
    }

    @Test
    fun `switching surface flushes the previous one`() {
        val stopwatch = SurfaceStopwatch()
        stopwatch.enter("$IG/clips_viewer", 0)
        assertEquals(1, stopwatch.tick(1_000))
        assertEquals(0, stopwatch.enter("$IG/feed_reel_cell", 1_500))
        assertEquals("$IG/feed_reel_cell", stopwatch.activeSurface)
        assertEquals(1, stopwatch.tick(2_500))
    }

    @Test
    fun `re-entering the same surface does not restart the clock`() {
        val stopwatch = SurfaceStopwatch()
        stopwatch.enter("$YT/shorts_player", 0)
        stopwatch.enter("$YT/shorts_player", 3_000)
        assertEquals(3, stopwatch.leave(3_000))
    }

    @Test
    fun `surface time feeds the shared budget until it is exhausted`() {
        val settings = settings()
        settings.updateBudgetLimit(1) // 1 minute, so the test stays short

        val stopwatch = SurfaceStopwatch()
        stopwatch.enter("$IG/clips_viewer", 0)
        for (second in 1..60) {
            val tick = stopwatch.tick(second * 1_000L)
            if (tick > 0) settings.recordBudgetSeconds(tick, now)
        }

        assertEquals(60L, settings.usedSecondsToday(now))
        assertEquals(0, settings.remainingMinutesCeil(now))
        val budget = ShortVideoBudget(settings.budgetLimitMinutes)
        budget.record(settings.usedSecondsToday(now), now)
        assertTrue(budget.isExhausted(now))

        // Leaving the surface stops the counter: the partial second is dropped, nothing is charged.
        assertEquals(0, stopwatch.leave(60_400))
        assertEquals(60L, settings.usedSecondsToday(now))
    }

    @Test
    fun `screen off mid-surface flushes what was watched`() {
        val settings = settings()
        val stopwatch = SurfaceStopwatch()
        stopwatch.enter("$TT/playback", 0)
        val first = stopwatch.tick(1_000)
        val rest = stopwatch.leave(6_000)
        settings.recordBudgetSeconds(first + rest, now)

        assertEquals(6L, settings.usedSecondsToday(now))
        assertEquals(20, settings.remainingMinutesCeil(now))
    }
}
