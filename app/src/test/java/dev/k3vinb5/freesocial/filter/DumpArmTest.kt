package dev.k3vinb5.freesocial.filter

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The inspector <-> service one-shot handshake: arm, capture once, expire after 60 s. */
class DumpArmTest {

    @Before
    fun setUp() = DumpArm.cancel()

    @After
    fun tearDown() {
        DumpArm.useClock()
        DumpArm.cancel()
    }

    @Test
    fun `arm then capture consumes the one shot`() {
        DumpArm.arm("IG · home feed", now = 1_000)
        assertTrue(DumpArm.isArmed(1_000))
        assertEquals("IG · home feed", DumpArm.armedScreen(1_000))

        assertEquals("IG · home feed", DumpArm.consume(2_000))
        // Auto-cleared after the single capture: a second event must not write again.
        assertFalse(DumpArm.isArmed(2_000))
        assertNull(DumpArm.consume(2_001))
    }

    @Test
    fun `arm expires at sixty seconds`() {
        DumpArm.arm("YT · shorts player", now = 10_000)
        assertTrue(DumpArm.isArmed(10_000 + DumpArm.EXPIRY_MS - 1))
        assertFalse(DumpArm.isArmed(10_000 + DumpArm.EXPIRY_MS))
        assertNull(DumpArm.consume(10_000 + DumpArm.EXPIRY_MS + 1))
    }

    @Test
    fun `capture while unarmed is ignored`() {
        assertFalse(DumpArm.isArmed(0))
        assertNull(DumpArm.consume(0))
        assertNull(DumpArm.armedScreen(0))
    }

    @Test
    fun `rearming replaces the label and restarts the window`() {
        DumpArm.arm("IG · home feed", now = 1_000)
        DumpArm.arm("TT · playback", now = 30_000)
        assertTrue(DumpArm.isArmed(30_000 + DumpArm.EXPIRY_MS - 1))
        assertEquals("TT · playback", DumpArm.consume(30_000))
    }

    @Test
    fun `cancel disarms before any capture`() {
        DumpArm.arm("IG · reels viewer", now = 5_000)
        DumpArm.cancel()
        assertFalse(DumpArm.isArmed(5_000))
    }

    @Test
    fun `a launch transition keeps the arm armed until the target window is active`() {
        // The event says YouTube, but the active root is still Free Social: capturing would write
        // that app's decor tree labelled "YT · shorts player" (Wave A retry R4).
        assertFalse(DumpArm.readyFor(YT, "dev.k3vinb5.freesocial"))
        assertTrue(DumpArm.readyFor(YT, YT))
        // An unreadable root must not block: the dump reports the ghost tree it actually saw.
        assertTrue(DumpArm.readyFor(YT, null))
    }

    @Test
    fun `a decor-only capture is recognised as a ghost and retried`() {
        // Wave A A5 / retry R4: 4 nodes, 1 view id, no grafts, labelled as the target app.
        assertTrue(DumpArm.looksGhost(nodeCount = 4, grafts = 0))
        // The scan path on the same window a moment later: real content.
        assertFalse(DumpArm.looksGhost(nodeCount = 44, grafts = 40))
        // A graft small enough to stay under the limit is real content, not a ghost.
        assertFalse(DumpArm.looksGhost(nodeCount = 4, grafts = 1))
    }

    @Test
    fun `a guided arm covers detection and recording and the service never consumes it`() {
        DumpArm.armGuided("shorts_player", now = 1_000)

        // 30 s detection + 15 s recording fit, and past the old 60 s window filtering stays paused.
        assertTrue(DumpArm.isArmed(1_000 + 61_000))
        assertTrue(DumpArm.isGuided(1_000 + 61_000))
        // The service must not capture on its own: the guided session verifies the surface first.
        assertFalse(DumpArm.readyFor(YT, YT))
        assertFalse(DumpArm.readyFor(YT, null))

        // Phase change refreshes the window.
        DumpArm.refreshGuided(now = 70_000)
        assertTrue(DumpArm.isArmed(70_000 + DumpArm.GUIDED_EXPIRY_MS - 1))
        assertFalse(DumpArm.isArmed(70_000 + DumpArm.GUIDED_EXPIRY_MS))
    }

    @Test
    fun `cancel ends a guided arm and restores normal readiness`() {
        DumpArm.armGuided("home", now = 0)
        DumpArm.cancel()

        assertFalse(DumpArm.isGuided(0))
        assertTrue(DumpArm.readyFor(YT, YT))
    }

    @Test
    fun `captureActive covers a manual arm and hands control back on expiry`() {
        assertFalse("nothing armed", DumpArm.captureActive(0))

        DumpArm.arm("TT · playback", now = 1_000)
        assertTrue(DumpArm.captureActive(1_000))
        assertTrue(DumpArm.captureActive(1_000 + DumpArm.EXPIRY_MS - 1))
        assertFalse("the 60 s arm is dead", DumpArm.captureActive(1_000 + DumpArm.EXPIRY_MS))

        // Ordered like BlockService sees it: pause while armed, resume once the capture consumed it.
        DumpArm.arm("TT · playback", now = 5_000)
        assertTrue(DumpArm.captureActive(6_000))
        DumpArm.consume(6_000)
        assertFalse(DumpArm.captureActive(6_001))
    }

    @Test
    fun `captureActive covers the longer guided window and ends with the session`() {
        DumpArm.armGuided("shorts_player", now = 1_000)
        // Past the manual window, still inside detection + recording.
        assertTrue(DumpArm.captureActive(1_000 + 61_000))
        DumpArm.refreshGuided(now = 100_000)
        assertTrue(DumpArm.captureActive(100_000 + DumpArm.GUIDED_EXPIRY_MS - 1))
        assertFalse(DumpArm.captureActive(100_000 + DumpArm.GUIDED_EXPIRY_MS))

        DumpArm.armGuided("home", now = 200_000)
        DumpArm.cancel()
        assertFalse(DumpArm.captureActive(200_000))
    }

    /**
     * Regression (review PR #3): the guided arm was stamped by `armGuided()`'s wall-clock default
     * and then refreshed with `SystemClock.elapsedRealtime()` at the detection -> recording edge.
     * The stored deadline jumped to the elapsed scale while `isArmed` compared against wall-clock
     * millis, so the arm read as expired and blocking resumed in the middle of the recording.
     *
     * This drives the production call shape: `armGuided()` / `captureActive()` with no argument
     * (what GuidedCapture and BlockService actually call) and `refreshGuided(now)` fed an
     * elapsedRealtime-scale value, all on one injected clock.
     */
    @Test
    fun `the guided arm survives the detection to recording handover and expires after the window`() {
        val boot = 5_000_000L // elapsedRealtime scale; a wall clock is ~1.7e12
        val detectedAt = boot + 30_000
        try {
            DumpArm.useClock { boot }
            DumpArm.armGuided("shorts_player") // GuidedCapture.start(): no clock argument
            assertTrue(DumpArm.captureActive())

            DumpArm.useClock { detectedAt }
            DumpArm.refreshGuided(now = detectedAt) // GuidedCapture.tick(): elapsedRealtime

            // The handover no longer disarms: recording keeps filtering AND blocking paused.
            assertTrue(DumpArm.captureActive())
            assertTrue(DumpArm.captureActive(detectedAt + DumpArm.GUIDED_EXPIRY_MS - 1))
            assertFalse(DumpArm.captureActive(detectedAt + DumpArm.GUIDED_EXPIRY_MS))
        } finally {
            DumpArm.useClock()
            DumpArm.cancel()
        }
    }

    /**
     * The same handover on the manual arm: `arm()` and `captureActive()` must agree without either
     * side passing a timestamp, so a caller that forgets `now` cannot land in another domain.
     */
    @Test
    fun `manual arm and capture check agree on the injected clock`() {
        try {
            DumpArm.useClock { 7_000_000L }
            DumpArm.arm("IG · home feed")
            assertTrue(DumpArm.captureActive())
            assertEquals("IG · home feed", DumpArm.armedScreen())
            assertFalse(DumpArm.captureActive(7_000_000L + DumpArm.EXPIRY_MS))
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
