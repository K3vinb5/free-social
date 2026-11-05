package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Action
import dev.k3vinb5.freesocial.core.MatchSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3: on the Reno4 the exhausted `shorts_player` was neutralised with three BACKs about 1.3 s
 * apart (`11:42:03.535`, `11:42:04.815`, `11:42:06.147`). The 1200 ms debounce has expired by the
 * second one and the scans still see the pre-navigation tree, so the engine pressed Back again on
 * a screen it had already left.
 *
 * The fix is a per-package+surface post-escape hold that lifts when a capture no longer shows the
 * surface (or after a short fixed window), layered in front of the unchanged 1200 ms debounce.
 */
class EscapeGuardTest {

    private val player = rule(
        id = "yt_shorts_player",
        appPackage = YT,
        surface = "shorts_player",
        layer = 2,
        match = MatchSpec(viewId = "$YT:id/reel_recycler"),
        action = Action.BACK,
    )

    /** A miniature scan: gate → arbiter → one action, exactly like `FilterService.scan`. */
    private class Scans {
        val arbiter = ActionArbiter()
        var actions = 0

        fun scan(hit: RuleHit, now: Long, surfaceStillPresent: Boolean) {
            val hits = listOf(hit)
            assertEquals(GateOutcome.NEUTRALISE, RuleGate.decide(hits, budgetExhausted = true, scheduleLocked = false))
            val chosen = RuleGate.chosen(hits, budgetExhausted = true, scheduleLocked = false)!!
            if (!arbiter.tryAcquire(chosen.rule.surface, YT, now, surfaceStillPresent)) return
            actions++
            arbiter.confirm(chosen.rule.surface, YT, now, chosen.rule.action == Action.BACK)
        }
    }

    @Test
    fun `budget exhaustion fires exactly one BACK across rapid scans on a stale tree`() {
        val scans = Scans()
        val playerHit = hit(player)

        scans.scan(playerHit, now = 0, surfaceStillPresent = true) // 11:42:03.535 — BACK #1
        scans.scan(playerHit, now = 471, surfaceStillPresent = true) // 11:42:04.006 — debounce
        assertEquals(1, scans.actions)

        // 11:42:04.815 / 11:42:06.147: debounce has expired, but the tree still shows the player.
        scans.scan(playerHit, now = 1_280, surfaceStillPresent = true)
        scans.scan(playerHit, now = 2_612, surfaceStillPresent = true)
        assertEquals("the stale tree must not re-fire the escape", 1, scans.actions)

        // Before the 5 s window ends the surface is still unguarded by content changes: stay quiet.
        scans.scan(playerHit, now = 4_999, surfaceStillPresent = true)
        assertEquals(1, scans.actions)

        // Fixed window over and the surface still matches (e.g. the BACK was swallowed): retry once.
        scans.scan(playerHit, now = 5_000, surfaceStillPresent = true)
        assertEquals(2, scans.actions)
    }

    @Test
    fun `a capture that no longer shows the surface releases the hold immediately`() {
        val scans = Scans()
        val playerHit = hit(player)
        scans.scan(playerHit, now = 0, surfaceStillPresent = true)
        assertFalse(scans.arbiter.tryAcquire("shorts_player", YT, 1_300, surfaceStillPresent = true))

        // Home feed: the player is gone, so the next real player visit must not be blocked.
        assertTrue(scans.arbiter.tryAcquire("shorts_player", YT, 1_300, surfaceStillPresent = false))
    }

    @Test
    fun `scroll and click actions keep only the 1200 ms debounce`() {
        val arbiter = ActionArbiter()
        assertTrue(arbiter.tryAcquire("shorts_shelf", YT, 0, surfaceStillPresent = true))
        arbiter.confirm("shorts_shelf", YT, 0, escapeAction = false)

        assertFalse("the 1200 ms debounce still applies", arbiter.tryAcquire("shorts_shelf", YT, 1_199, true))
        assertTrue("no post-escape hold for SCROLL_PAST", arbiter.tryAcquire("shorts_shelf", YT, 1_200, true))
    }

    @Test
    fun `the hold is keyed per package and surface`() {
        val arbiter = ActionArbiter()
        assertTrue(arbiter.tryAcquire("clips_viewer", IG, 0, true))
        arbiter.confirm("clips_viewer", IG, 0, escapeAction = true)

        assertFalse(arbiter.tryAcquire("clips_viewer", IG, 1_300, true))
        // Another surface of the same app is unaffected.
        assertTrue(arbiter.tryAcquire("reels_tab", IG, 1_300, true))
        // Another app's same-named surface is unaffected.
        assertTrue(arbiter.tryAcquire("clips_viewer", YT, 1_300, true))
    }
}
