package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Action
import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.settings.AppTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleGateTest {

    private val layerOne = rule("ig_feed_reel_cell", surface = "feed_reel_cell", layer = 1)
    private val layerOneHit = hit(layerOne)
    private val layerTwo = rule("ig_clips_viewer", surface = "clips_viewer", layer = 2)
    private val layerTwoHit = hit(layerTwo)
    private val skip = rule("tiktok_dm_carve_out", appPackage = TT, surface = "dm_carve_out", action = Action.SKIP)
    private val skipHit = hit(skip)

    @Test
    fun `nothing matched means nothing happens`() {
        assertEquals(GateOutcome.NONE, RuleGate.decide(emptyList(), budgetExhausted = true, scheduleLocked = true))
    }

    @Test
    fun `layer one neutralises with budget to spare`() {
        assertEquals(
            GateOutcome.NEUTRALISE,
            RuleGate.decide(listOf(layerOneHit), budgetExhausted = false, scheduleLocked = false),
        )
    }

    @Test
    fun `layer two plays while the budget lasts and the schedule is open`() {
        assertEquals(
            GateOutcome.NONE,
            RuleGate.decide(listOf(layerTwoHit), budgetExhausted = false, scheduleLocked = false),
        )
    }

    @Test
    fun `layer two neutralises when the budget is exhausted`() {
        assertEquals(
            GateOutcome.NEUTRALISE,
            RuleGate.decide(listOf(layerTwoHit), budgetExhausted = true, scheduleLocked = false),
        )
    }

    @Test
    fun `layer two neutralises while the nightly window is locked`() {
        assertEquals(
            GateOutcome.NEUTRALISE,
            RuleGate.decide(listOf(layerTwoHit), budgetExhausted = false, scheduleLocked = true),
        )
    }

    @Test
    fun `skip wins over every neutralisation`() {
        val hits = listOf(layerOneHit, layerTwoHit, skipHit)
        assertEquals(
            GateOutcome.SKIP,
            RuleGate.decide(hits, budgetExhausted = true, scheduleLocked = true),
        )
    }

    @Test
    fun `layer one outranks layer two when both matched`() {
        val hits = listOf(layerTwoHit, layerOneHit)
        assertEquals(GateOutcome.NEUTRALISE, RuleGate.decide(hits, budgetExhausted = false, scheduleLocked = false))
        assertEquals(listOf(layerOneHit), RuleGate.eligibleHits(hits, budgetExhausted = false, scheduleLocked = false))
    }

    @Test
    fun `one action per scan, most escape-like wins`() {
        // Added weakest → strongest; each addition must become the single chosen hit.
        val ascending = listOf(
            Action.REDIRECT_HOME,
            Action.CLICK_VIEW_ID,
            Action.SCROLL_PAST,
            Action.BACK,
            Action.TAP_HOME_TAB,
        )
        val hits = mutableListOf<RuleHit>()
        for ((i, action) in ascending.withIndex()) {
            val next = hit(rule("r$i", surface = "s$i", layer = 1, action = action))
            hits += next
            assertEquals(next, RuleGate.chosen(hits, budgetExhausted = false, scheduleLocked = false))
        }
        // Out-of-order input still resolves to the strongest, not the asset-order first.
        assertEquals(Action.TAP_HOME_TAB, RuleGate.chosen(hits.reversed(), false, false)?.rule?.action)
        assertEquals(GateOutcome.NEUTRALISE, RuleGate.decide(hits, false, false))
    }

    @Test
    fun `skip shields the whole scan and yields no action`() {
        val strongest = hit(rule("tap", surface = "reels_tab", layer = 1, action = Action.TAP_HOME_TAB))
        val hits = listOf(strongest, layerTwoHit, skipHit)
        assertEquals(GateOutcome.SKIP, RuleGate.decide(hits, budgetExhausted = true, scheduleLocked = true))
        assertNull(RuleGate.chosen(hits, budgetExhausted = true, scheduleLocked = true))
    }

    @Test
    fun `layer two only competes once the gate opens`() {
        val swipe = hit(rule("shelf", surface = "shorts_shelf", layer = 1, action = Action.SCROLL_PAST))
        val back = hit(rule("player", surface = "shorts_player", layer = 2, action = Action.BACK))
        // While the budget lasts the layer-2 BACK is not even a candidate.
        assertEquals(swipe, RuleGate.chosen(listOf(back, swipe), budgetExhausted = false, scheduleLocked = false))
        // Exhausted: BACK outranks SCROLL_PAST and the viewer exits.
        assertEquals(back, RuleGate.chosen(listOf(swipe, back), budgetExhausted = true, scheduleLocked = false))
        assertEquals(back, RuleGate.chosen(listOf(swipe, back), budgetExhausted = false, scheduleLocked = true))
    }

    @Test
    fun `reels tab rule only fires while the tab is selected`() {
        val reels = rule(
            id = "ig_reels_tab",
            surface = "reels_tab",
            layer = 1,
            match = MatchSpec(viewId = "$IG:id/clips_tab", selected = true),
            action = Action.TAP_HOME_TAB,
        )
        val matcher = RuleSetMatcher(listOf(reels))
        val tabBar = node(
            children = listOf(
                node(viewId = "$IG:id/feed_tab", description = "Home", clickable = true, selected = false),
                node(viewId = "$IG:id/clips_tab", description = "Reels", selected = false),
            ),
        )
        assertTrue(matcher.hits(AppTarget.INSTAGRAM, IG, tabBar).isEmpty())

        val onReels = node(
            children = listOf(node(viewId = "$IG:id/clips_tab", description = "Reels", selected = true)),
        )
        assertEquals(1, matcher.hits(AppTarget.INSTAGRAM, IG, onReels).size)
    }

    @Test
    fun `switched-off surfaces are dropped before matching`() {
        val toggles = SurfaceToggleReader { app, key ->
            !(app == AppTarget.INSTAGRAM && key == "hide_feed_reels")
        }
        val matcher = RuleSetMatcher(listOf(layerOne), toggles)
        val tree = node(viewId = "$IG:id/target")
        assertTrue(matcher.hits(AppTarget.INSTAGRAM, IG, tree).isEmpty())

        val enabled = RuleSetMatcher(listOf(layerOne), SurfaceToggleReader { _, _ -> true })
        assertEquals(1, enabled.hits(AppTarget.INSTAGRAM, IG, tree).size)
    }

    @Test
    fun `only the three planned surfaces count against the budget`() {
        val toggles = SurfaceToggleReader { _, _ -> true }
        assertTrue(SurfacePolicy.countsAgainstBudget(AppTarget.INSTAGRAM, IG, "clips_viewer", toggles))
        assertTrue(SurfacePolicy.countsAgainstBudget(AppTarget.YOUTUBE, YT, "shorts_player", toggles))
        assertTrue(SurfacePolicy.countsAgainstBudget(AppTarget.TIKTOK, TT, "playback", toggles))
        assertTrue(SurfacePolicy.countsAgainstBudget(AppTarget.TIKTOK, TT_GLOBAL, "playback", toggles))
        assertFalse(SurfacePolicy.countsAgainstBudget(AppTarget.INSTAGRAM, IG, "feed_reel_cell", toggles))
        assertFalse(SurfacePolicy.countsAgainstBudget(AppTarget.YOUTUBE, YT, "shorts_shelf", toggles))
    }

    @Test
    fun `switching counting off stops the stopwatch for that surface`() {
        val countingOff = SurfaceToggleReader { app, key ->
            !(app == AppTarget.YOUTUBE && key == "shorts_count_budget")
        }
        assertFalse(SurfacePolicy.countsAgainstBudget(AppTarget.YOUTUBE, YT, "shorts_player", countingOff))
        assertTrue(SurfacePolicy.countsAgainstBudget(AppTarget.INSTAGRAM, IG, "clips_viewer", countingOff))
    }
}
