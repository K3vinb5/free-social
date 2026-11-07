package dev.k3vinb5.freesocial.filter

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The guided arm shared by the inspector session and the two enforcement services: it covers
 * detection + recording, is refreshed at the phase change, and expires at the guided ceiling.
 *
 * The service-side consumer (`consume`/`readyFor`/`looksGhost`/`isGuided`/`captureActive`) was
 * removed with the manual armed-dump path (D17 follow-up), so these tests only exercise the API
 * production still calls.
 */
class DumpArmTest {

    @Before
    fun setUp() = DumpArm.cancel()

    @After
    fun tearDown() {
        DumpArm.useClock()
        DumpArm.cancel()
    }

    @Test
    fun `capture while unarmed is ignored`() {
        assertFalse(DumpArm.isArmed(0))
        assertNull(DumpArm.remainingMillis(0))
    }

    @Test
    fun `a guided arm covers detection and recording and expires at the ceiling`() {
        DumpArm.armGuided("shorts_player", now = 1_000)

        // 30 s detection + 15 s recording fit well inside the guided window.
        assertTrue(DumpArm.isArmed(1_000 + 61_000))

        // Phase change refreshes the window.
        DumpArm.refreshGuided(now = 70_000)
        assertTrue(DumpArm.isArmed(70_000 + DumpArm.GUIDED_EXPIRY_MS - 1))
        assertFalse(DumpArm.isArmed(70_000 + DumpArm.GUIDED_EXPIRY_MS))
    }

    @Test
    fun `cancel ends a guided arm and disarms the device`() {
        DumpArm.armGuided("home", now = 0)
        DumpArm.cancel()

        assertFalse(DumpArm.isArmed(0))
        assertNull(DumpArm.remainingMillis(0))
    }

    @Test
    fun `the armed window covers detection and recording and ends with the session`() {
        DumpArm.armGuided("shorts_player", now = 1_000)
        assertTrue(DumpArm.isArmed(1_000 + 61_000))
        DumpArm.refreshGuided(now = 100_000)
        assertTrue(DumpArm.isArmed(100_000 + DumpArm.GUIDED_EXPIRY_MS - 1))
        assertFalse(DumpArm.isArmed(100_000 + DumpArm.GUIDED_EXPIRY_MS))

        DumpArm.armGuided("home", now = 200_000)
        DumpArm.cancel()
        assertFalse(DumpArm.isArmed(200_000))
    }

    /**
     * D16: blocking schedules its re-check on this deadline, so it must count down inside the
     * window, never go below one millisecond while armed, and clear itself exactly at the ceiling.
     */
    @Test
    fun `remainingMillis tracks the guided deadline and clears an expired arm`() {
        DumpArm.armGuided("home", now = 1_000)

        assertEquals(DumpArm.GUIDED_EXPIRY_MS, DumpArm.remainingMillis(1_000))
        assertEquals(DumpArm.GUIDED_EXPIRY_MS - 30_000, DumpArm.remainingMillis(31_000))
        assertEquals(1L, DumpArm.remainingMillis(1_000 + DumpArm.GUIDED_EXPIRY_MS - 1))
        assertNull(DumpArm.remainingMillis(1_000 + DumpArm.GUIDED_EXPIRY_MS))
        assertFalse(DumpArm.isArmed(1_000 + DumpArm.GUIDED_EXPIRY_MS))
    }

    @Test
    fun `refreshGuided moves the remaining deadline`() {
        DumpArm.armGuided("shorts_player", now = 1_000)
        DumpArm.refreshGuided(now = 50_000)

        assertEquals(DumpArm.GUIDED_EXPIRY_MS, DumpArm.remainingMillis(50_000))
        assertEquals(1L, DumpArm.remainingMillis(50_000 + DumpArm.GUIDED_EXPIRY_MS - 1))
        assertNull(DumpArm.remainingMillis(50_000 + DumpArm.GUIDED_EXPIRY_MS))
    }

    /**
     * Regression (review PR #3): the guided arm was stamped by `armGuided()`'s wall-clock default
     * and then refreshed with `SystemClock.elapsedRealtime()` at the detection -> recording edge.
     * The stored deadline jumped to the elapsed scale while `isArmed` compared against wall-clock
     * millis, so the arm read as expired and blocking resumed in the middle of the recording.
     *
     * This drives the production call shape: `armGuided()` / `isArmed()` with no argument (what
     * GuidedCapture and FilterService actually call) and `refreshGuided(now)` fed an
     * elapsedRealtime-scale value, all on one injected clock.
     */
    @Test
    fun `the guided arm survives the detection to recording handover and expires after the window`() {
        val boot = 5_000_000L // elapsedRealtime scale; a wall clock is ~1.7e12
        val detectedAt = boot + 30_000
        try {
            DumpArm.useClock { boot }
            DumpArm.armGuided("shorts_player") // GuidedCapture.start(): no clock argument
            assertTrue(DumpArm.isArmed())

            DumpArm.useClock { detectedAt }
            DumpArm.refreshGuided(now = detectedAt) // GuidedCapture.tick(): elapsedRealtime

            // The handover no longer disarms: recording keeps filtering AND blocking paused.
            assertTrue(DumpArm.isArmed())
            assertTrue(DumpArm.isArmed(detectedAt + DumpArm.GUIDED_EXPIRY_MS - 1))
            assertFalse(DumpArm.isArmed(detectedAt + DumpArm.GUIDED_EXPIRY_MS))
        } finally {
            DumpArm.useClock()
            DumpArm.cancel()
        }
    }

    @Test
    fun `the guided session and the arm use one clock source`() {
        // Source-level guard against domain drift: GuidedCapture polls elapsedRealtime and must
        // hand that same value to the arm; DumpArm's own default must be that clock too.
        val guided = File("src/main/java/dev/k3vinb5/freesocial/filter/GuidedCapture.kt").readText()
        assertTrue(guided.contains("DumpArm.refreshGuided(now)"))
        assertTrue(guided.contains("val now = SystemClock.elapsedRealtime()"))

        val arm = File("src/main/java/dev/k3vinb5/freesocial/filter/DumpArm.kt").readText()
        assertTrue(arm.contains("SystemClock.elapsedRealtime()"))
        assertFalse(
            "a wall-clock default creeps back in",
            arm.contains("now: Long = System.currentTimeMillis()") ||
                arm.contains("now = System.currentTimeMillis()"),
        )
    }
}
