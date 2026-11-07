package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escape-flash rate limit (2026-11-05, episode-scoped by D12 on 2026-11-07).
 *
 * The contract the phone proved (four 0-budget Reels entries produced two flashes): a flash is
 * shown once per escape **episode**; the 3 s window only spaces repeats *inside* one episode, so a
 * genuine re-entry is explained again immediately. Every action the gate can choose as an escape
 * flashes; the retired/passive actions do not.
 */
class FlashPolicyTest {

    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val tt = "com.zhiliaoapp.musically"

    @Test
    fun `first escape of an episode flashes`() {
        val policy = FlashPolicy()
        assertTrue(policy.shouldFlash(ig, "reels_tab", 1_000L))
        assertEquals("reels_tab", policy.episodeOf(ig))
    }

    @Test
    fun `a repeat inside the same episode is throttled within three seconds`() {
        val policy = FlashPolicy()
        assertTrue(policy.shouldFlash(ig, "clips_viewer", 1_000L))
        assertFalse(policy.shouldFlash(ig, "clips_viewer", 2_000L))
        assertFalse("still inside the window at the edge", policy.shouldFlash(ig, "clips_viewer", 3_999L))
        assertTrue("outside the window the same episode may flash again", policy.shouldFlash(ig, "clips_viewer", 4_000L))
    }

    @Test
    fun `suppressed calls do not refresh the window`() {
        val policy = FlashPolicy()
        assertTrue(policy.shouldFlash(yt, "shorts_player", 0L))
        assertFalse(policy.shouldFlash(yt, "shorts_player", 2_900L))
        assertTrue(policy.shouldFlash(yt, "shorts_player", 3_000L))
    }

    @Test
    fun `a different surface inside the window is a different episode and flashes`() {
        // The D12 regression: the old package-global window muted this second, distinct escape even
        // though it left a different surface (Reels tab -> Reels viewer) 0.5 s later.
        val policy = FlashPolicy()
        assertTrue(policy.shouldFlash(ig, "reels_tab", 1_000L))
        assertTrue("a distinct surface is a new episode", policy.shouldFlash(ig, "clips_viewer", 1_500L))
        assertEquals("clips_viewer", policy.episodeOf(ig))
    }

    @Test
    fun `the surface leaving the capture ends the episode so the re-entry flashes at once`() {
        val policy = FlashPolicy()
        assertTrue(policy.shouldFlash(ig, "reels_tab", 1_000L))
        // The escape landed on the feed: the next capture no longer shows the Reels tab surface.
        policy.observe(ig, visibleSurfaces = setOf("feed_reel_cell"))
        assertNull(policy.episodeOf(ig))
        assertTrue(policy.shouldFlash(ig, "reels_tab", 1_200L))
    }

    @Test
    fun `the episode survives while the surface is still on screen`() {
        val policy = FlashPolicy()
        assertTrue(policy.shouldFlash(tt, "playback", 1_000L))
        policy.observe(tt, visibleSurfaces = setOf("playback"))
        assertFalse("same episode, no new flash inside 3 s", policy.shouldFlash(tt, "playback", 1_500L))
    }

    @Test
    fun `one episode per package and package-independent throttling`() {
        val policy = FlashPolicy()
        assertTrue(policy.shouldFlash(ig, "reels_tab", 1_000L))
        policy.observe(ig, setOf("reels_tab"))
        assertTrue(policy.shouldFlash(tt, "playback", 1_000L))
        assertTrue(policy.shouldFlash(yt, "shorts_player", 1_000L))
        assertFalse(policy.shouldFlash(ig, "reels_tab", 1_500L))
        assertFalse(policy.shouldFlash(tt, "playback", 1_500L))
        // The other app's episode is untouched by IG's observe.
        assertEquals("playback", policy.episodeOf(tt))
    }

    @Test
    fun `every action the gate can escape with flashes`() {
        val escapeActions = Action.entries.filter { RuleGate.isEscapeAction(it) }
        assertEquals(
            "an actionable escape must never be omitted (D12: CLICK_VIEW_ID was)",
            setOf(Action.BACK, Action.TAP_HOME_TAB, Action.CLICK_VIEW_ID, Action.REDIRECT_HOME),
            escapeActions.toSet(),
        )
        // For each escape action: one fresh episode -> exactly one flash, and a same-episode repeat
        // inside 3 s is the only thing the throttle ever drops.
        for ((index, action) in escapeActions.withIndex()) {
            val policy = FlashPolicy()
            val packageName = "$ig#$index"
            val surface = "surface_$action"
            assertTrue("$action must flash on its first episode", policy.shouldFlash(packageName, surface, 1_000L))
            assertFalse("$action repeat inside the episode is throttled", policy.shouldFlash(packageName, surface, 2_000L))
        }
        assertFalse("SKIP is a shield, never an escape", RuleGate.isEscapeAction(Action.SKIP))
        assertFalse("the retired SCROLL_PAST action never escapes", RuleGate.isEscapeAction(Action.SCROLL_PAST))
    }
}
