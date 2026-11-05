package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Action
import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.settings.AppTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(listOf(layerOneHit), RuleGate.activeHits(hits))
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
