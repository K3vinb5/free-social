package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DIRECT-ENGAGEMENT action arbiter: no post-escape time window.
 *
 * The old 5 s hold allowed an immediate re-entry (the user's "click again → allows"). Now an
 * escape only suppresses repeats while that same surface is still visible (the stale tree after the
 * asynchronous BACK). A capture that no longer shows the surface ends the episode, and the next
 * entry is escaped immediately — N rapid entries produce N identical escapes.
 */
class ActionArbiterTest {

    private val key = "$YT/shorts_player"

    @Test
    fun `a stale tree cannot re-fire the escape, with no time window at all`() {
        val arbiter = ActionArbiter()
        arbiter.observe(setOf(key))
        assertTrue(arbiter.tryAcquire("shorts_player", YT, 0))
        arbiter.confirm("shorts_player", YT, escapeAction = true, acted = true)

        // Same episode: 1.3 s, 5 s, an hour — never a second action and never an allow flip.
        for (now in listOf(1_300L, 5_000L, 3_600_000L)) {
            arbiter.observe(setOf(key))
            assertFalse("same visible episode at $now", arbiter.tryAcquire("shorts_player", YT, now))
        }
    }

    @Test
    fun `rapid re-entries all escape identically and immediately`() {
        val arbiter = ActionArbiter()
        var actions = 0
        repeat(5) { index ->
            val now = index * 100L
            arbiter.observe(setOf(key)) // the user enters the surface
            assertTrue("entry $index must escape", arbiter.tryAcquire("shorts_player", YT, now))
            actions++
            arbiter.confirm("shorts_player", YT, escapeAction = true, acted = true)
            arbiter.observe(emptySet()) // the escape landed; the surface is gone
        }
        assertEquals("five entries, five escapes, no allow window", 5, actions)
    }

    @Test
    fun `a re-entry sooner than the debounce is not delayed`() {
        val arbiter = ActionArbiter()
        arbiter.observe(setOf(key))
        assertTrue(arbiter.tryAcquire("shorts_player", YT, 0))
        arbiter.confirm("shorts_player", YT, escapeAction = true, acted = true)
        arbiter.observe(emptySet())
        // 400 ms later, well inside the 1200 ms debounce: a real entry is still acted on.
        arbiter.observe(setOf(key))
        assertTrue(arbiter.tryAcquire("shorts_player", YT, 400))
    }

    @Test
    fun `a rejected action stays retryable after the debounce`() {
        val arbiter = ActionArbiter()
        arbiter.observe(setOf(key))
        assertTrue(arbiter.tryAcquire("shorts_player", YT, 0))
        arbiter.confirm("shorts_player", YT, escapeAction = true, acted = false)
        arbiter.observe(setOf(key))
        assertFalse("debounce spaces the retry", arbiter.tryAcquire("shorts_player", YT, 1_199))
        assertTrue("a failed escape is retried", arbiter.tryAcquire("shorts_player", YT, 1_200))
    }

    @Test
    fun `episodes are keyed per package and surface`() {
        val arbiter = ActionArbiter()
        arbiter.observe(setOf("$IG/clips_viewer"))
        assertTrue(arbiter.tryAcquire("clips_viewer", IG, 0))
        arbiter.confirm("clips_viewer", IG, escapeAction = true, acted = true)

        arbiter.observe(setOf("$IG/clips_viewer", "$IG/reels_tab", "$YT/shorts_player"))
        assertFalse(arbiter.tryAcquire("clips_viewer", IG, 10))
        assertTrue(arbiter.tryAcquire("reels_tab", IG, 10))
        assertTrue(arbiter.tryAcquire("shorts_player", YT, 10))
    }
}
