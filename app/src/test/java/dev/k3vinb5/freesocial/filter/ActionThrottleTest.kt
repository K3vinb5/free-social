package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionThrottleTest {

    private val throttle = ActionThrottle()

    @Test
    fun `first action passes and the same surface is debounced for 1200 ms`() {
        assertTrue(throttle.tryAcquire("clips_viewer", IG, 0))
        assertFalse(throttle.tryAcquire("clips_viewer", IG, 1_199))
        assertTrue(throttle.tryAcquire("clips_viewer", IG, 1_200))
    }

    @Test
    fun `different surfaces are debounced independently`() {
        assertTrue(throttle.tryAcquire("clips_viewer", IG, 0))
        assertTrue(throttle.tryAcquire("reels_tab", IG, 400))
        assertEquals(2, throttle.actionsInWindow(IG))
    }

    @Test
    fun `a blocked action does not consume quota`() {
        assertTrue(throttle.tryAcquire("clips_viewer", IG, 0))
        assertFalse(throttle.tryAcquire("clips_viewer", IG, 500))
        assertEquals(1, throttle.actionsInWindow(IG))
    }

    @Test
    fun `per-app quota caps a burst and slides with the window`() {
        val capped = ActionThrottle(debounceMs = 0, quotaMax = 3, quotaWindowMs = 60_000)
        repeat(3) { index -> assertTrue(capped.tryAcquire("surface_$index", IG, index * 10L)) }
        assertFalse(capped.tryAcquire("surface_9", IG, 100))
        // Another app has its own quota.
        assertTrue(capped.tryAcquire("surface_9", YT, 100))
        // Once the window has slid past the first actions, the app may act again.
        assertTrue(capped.tryAcquire("surface_0", IG, 60_100))
    }

    @Test
    fun `reset clears debounce and quota history`() {
        assertTrue(throttle.tryAcquire("clips_viewer", IG, 0))
        assertFalse(throttle.tryAcquire("clips_viewer", IG, 10))
        throttle.reset()
        assertTrue(throttle.tryAcquire("clips_viewer", IG, 20))
        assertEquals(1, throttle.actionsInWindow(IG))
    }
}
