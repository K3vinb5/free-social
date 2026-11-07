package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Action
import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.core.loadRuleSet
import dev.k3vinb5.freesocial.settings.AppTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuleGateTest {

    private val feedPassive = rule(
        "ig_feed_reel_cell",
        surface = "feed_reel_cell",
        layer = 1,
        action = Action.SCROLL_PAST,
    )
    private val feedHit = hit(feedPassive)
    private val reelsTab = rule(
        "ig_reels_tab",
        surface = "reels_tab",
        layer = 1,
        action = Action.TAP_HOME_TAB,
    )
    private val reelsTabHit = hit(reelsTab)
    private val viewer = rule("ig_clips_viewer", surface = "clips_viewer", layer = 2, action = Action.BACK)
    private val viewerHit = hit(viewer)
    private val ytPlayer = rule(
        "yt_shorts_player",
        appPackage = YT,
        surface = "shorts_player",
        layer = 2,
        action = Action.BACK,
    )
    private val ytPlayerHit = hit(ytPlayer, node(viewId = "$YT:id/reel_recycler"))
    private val skip = rule("tiktok_dm_carve_out", appPackage = TT, surface = "dm_carve_out", action = Action.SKIP)
    private val skipHit = hit(skip)

    @Test
    fun `nothing matched means nothing happens`() {
        assertEquals(GateOutcome.NONE, RuleGate.decide(emptyList(), budgetExhausted = true, scheduleLocked = true))
    }

    @Test
    fun `passive surfaces are never touched, even with the budget exhausted`() {
        assertEquals(GateOutcome.NONE, RuleGate.decide(listOf(feedHit), budgetExhausted = false, scheduleLocked = false))
        assertEquals(GateOutcome.NONE, RuleGate.decide(listOf(feedHit), budgetExhausted = true, scheduleLocked = true))
        assertNull(RuleGate.chosen(listOf(feedHit), budgetExhausted = true, scheduleLocked = true))
    }

    @Test
    fun `a direct surface pays while the budget lasts and performs no action`() {
        assertEquals(GateOutcome.PAY, RuleGate.decide(listOf(viewerHit), budgetExhausted = false, scheduleLocked = false))
        assertNull(RuleGate.chosen(listOf(viewerHit), budgetExhausted = false, scheduleLocked = false))
    }

    @Test
    fun `a direct surface escapes exactly once the budget is exhausted`() {
        assertEquals(GateOutcome.ESCAPE, RuleGate.decide(listOf(viewerHit), budgetExhausted = true, scheduleLocked = false))
        assertEquals(viewerHit, RuleGate.chosen(listOf(viewerHit), budgetExhausted = true, scheduleLocked = false))
    }

    @Test
    fun `a direct surface escapes while the nightly window is locked`() {
        assertEquals(GateOutcome.ESCAPE, RuleGate.decide(listOf(ytPlayerHit), budgetExhausted = false, scheduleLocked = true))
    }

    @Test
    fun `the Reels tab is a direct surface, not an always-on layer-1 escape`() {
        // D9 regression: this is the exact rule that escaped an in-budget Reels entry.
        assertEquals(GateOutcome.PAY, RuleGate.decide(listOf(reelsTabHit), budgetExhausted = false, scheduleLocked = false))
        assertEquals(GateOutcome.ESCAPE, RuleGate.decide(listOf(reelsTabHit), budgetExhausted = true, scheduleLocked = false))
        assertEquals(Action.TAP_HOME_TAB, RuleGate.chosen(listOf(reelsTabHit), true, false)?.rule?.action)
    }

    @Test
    fun `skip wins over every escape`() {
        val hits = listOf(feedHit, reelsTabHit, viewerHit, skipHit)
        assertEquals(GateOutcome.SKIP, RuleGate.decide(hits, budgetExhausted = true, scheduleLocked = true))
        assertNull(RuleGate.chosen(hits, budgetExhausted = true, scheduleLocked = true))
    }

    @Test
    fun `only direct, enabled-action hits are eligible`() {
        val unknown = hit(rule("mystery", surface = "mystery", layer = 1, action = Action.BACK))
        val hits = listOf(feedHit, unknown, viewerHit)
        assertEquals(listOf(viewerHit), RuleGate.eligibleHits(hits, budgetExhausted = true, scheduleLocked = false))
        assertTrue("no passive/unknown action in budget", RuleGate.eligibleHits(hits, false, false).isEmpty())
    }

    @Test
    fun `one action per scan, most escape-like wins`() {
        val ascending = listOf(
            Action.REDIRECT_HOME,
            Action.CLICK_VIEW_ID,
            Action.BACK,
            Action.TAP_HOME_TAB,
        )
        val hits = mutableListOf<RuleHit>()
        for ((i, action) in ascending.withIndex()) {
            val next = hit(
                rule(
                    id = "r$i",
                    surface = "reels_tab",
                    layer = 1,
                    action = action,
                    viewIdTarget = if (action == Action.CLICK_VIEW_ID) "$IG:id/tab_home" else null,
                ),
            )
            hits += next
            assertEquals(next, RuleGate.chosen(hits, budgetExhausted = true, scheduleLocked = false))
        }
        assertEquals(Action.TAP_HOME_TAB, RuleGate.chosen(hits.reversed(), true, false)?.rule?.action)
        assertEquals(GateOutcome.ESCAPE, RuleGate.decide(hits, true, false))
    }

    @Test
    fun `an armed inspector capture pauses every action`() {
        assertEquals(
            GateOutcome.NONE,
            RuleGate.decide(listOf(viewerHit), budgetExhausted = true, scheduleLocked = true, dumpArmed = true),
        )
        assertNull(RuleGate.chosen(listOf(viewerHit), true, true, dumpArmed = true))
        assertEquals(
            GateOutcome.ESCAPE,
            RuleGate.decide(listOf(viewerHit), budgetExhausted = true, scheduleLocked = true, dumpArmed = false),
        )
    }

    @Test
    fun `surface classification is exact — a feed rule cannot match a viewer tree`() {
        val feed = rule(
            "ig_feed_reel_cell",
            surface = "feed_reel_cell",
            layer = 1,
            match = MatchSpec(viewId = "$IG:id/video_container"),
            action = Action.SCROLL_PAST,
        )
        val clips = rule(
            "ig_clips_viewer",
            surface = "clips_viewer",
            layer = 2,
            match = MatchSpec(viewId = "$IG:id/clips_viewer_view_pager"),
            action = Action.BACK,
        )
        val matcher = RuleSetMatcher(listOf(feed, clips))
        val bothAnchors = node(
            children = listOf(
                node(viewId = "$IG:id/video_container"),
                node(viewId = "$IG:id/clips_viewer_view_pager"),
            ),
        )
        assertEquals(
            "the viewer identity owns the capture; the feed rule is out of scope",
            listOf("clips_viewer"),
            matcher.hits(AppTarget.INSTAGRAM, IG, bothAnchors).map { it.rule.surface },
        )
        val feedOnly = node(children = listOf(node(viewId = "$IG:id/video_container")))
        assertEquals(
            listOf("feed_reel_cell"),
            matcher.hits(AppTarget.INSTAGRAM, IG, feedOnly).map { it.rule.surface },
        )
    }

    @Test
    fun `retired rules are never compiled`() {
        val disabled = feedPassive.copy(enabled = false)
        val matcher = RuleSetMatcher(listOf(disabled))
        assertTrue(matcher.hits(AppTarget.INSTAGRAM, IG, node(viewId = "$IG:id/target")).isEmpty())
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
            !(app == AppTarget.INSTAGRAM && key == "hide_reels_tab")
        }
        val reels = rule(
            id = "ig_reels_tab",
            surface = "reels_tab",
            layer = 1,
            match = MatchSpec(viewId = "$IG:id/clips_tab", selected = true),
            action = Action.TAP_HOME_TAB,
        )
        val tree = node(viewId = "$IG:id/clips_tab", selected = true)
        assertTrue(RuleSetMatcher(listOf(reels), toggles).hits(AppTarget.INSTAGRAM, IG, tree).isEmpty())
        val enabled = RuleSetMatcher(listOf(reels), SurfaceToggleReader { _, _ -> true })
        assertEquals(1, enabled.hits(AppTarget.INSTAGRAM, IG, tree).size)
    }

    @Test
    fun `direct surfaces count against the budget, passive ones never do`() {
        val toggles = SurfaceToggleReader { _, _ -> true }
        assertTrue(SurfacePolicy.countsAgainstBudget(AppTarget.INSTAGRAM, IG, "clips_viewer", toggles))
        assertTrue(SurfacePolicy.countsAgainstBudget(AppTarget.INSTAGRAM, IG, "reels_tab", toggles))
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

    // --- D12: one escape-action definition ------------------------------------------------

    @Test
    fun `every actionable escape is an escape action and the shield is not`() {
        assertTrue(RuleGate.isEscapeAction(Action.BACK))
        assertTrue(RuleGate.isEscapeAction(Action.TAP_HOME_TAB))
        assertTrue(RuleGate.isEscapeAction(Action.CLICK_VIEW_ID))
        assertTrue(RuleGate.isEscapeAction(Action.REDIRECT_HOME))
        assertFalse(RuleGate.isEscapeAction(Action.SKIP))
        assertFalse(RuleGate.isEscapeAction(Action.SCROLL_PAST))
    }

    @Test
    fun `every action the gate can choose flashes`() {
        // ACTION_PRIORITY is the set `chosen` can return; each of those must flash once confirmed.
        val chosenActions = listOf(
            Action.TAP_HOME_TAB,
            Action.BACK,
            Action.CLICK_VIEW_ID,
            Action.REDIRECT_HOME,
        )
        for (action in chosenActions) {
            assertTrue("$action is choosable and must flash", RuleGate.isEscapeAction(action))
        }
    }

    // --- D13: the shipped IG DM carve-outs, against the real catalog ----------------------

    private val shipped: RuleSetMatcher =
        RuleSetMatcher(loadRuleSet(File("src/main/assets/rules.json").readText()).rules)

    private fun igTree(vararg ids: String): NodeSnapshot =
        node(children = ids.map { node(viewId = "$IG:id/$it", className = classOf(it)) })

    private fun classOf(id: String): String = when (id) {
        "reply_bar_edittext" -> "android.widget.AutoCompleteTextView"
        "row_thread_composer_edittext" -> "android.widget.EditText"
        "clips_viewer_view_pager" -> "androidx.viewpager.widget.ViewPager"
        else -> "android.widget.FrameLayout"
    }

    @Test
    fun `the DM thread is carved out while the budget is spent`() {
        val hits = shipped.hits(AppTarget.INSTAGRAM, IG, igTree("row_thread_composer_edittext"))
        assertEquals(listOf("dm_carve_out"), hits.map { it.rule.surface })
        assertEquals(GateOutcome.SKIP, RuleGate.decide(hits, budgetExhausted = true, scheduleLocked = false))
        assertNull(RuleGate.chosen(hits, budgetExhausted = true, scheduleLocked = false))
    }

    @Test
    fun `a reel opened from a DM thread is carved out even though the thread composer is gone`() {
        // The exact D13 tree: the shared-reel screen has the viewer pager and the DM reply bar,
        // but no row_thread_composer_edittext (12-ig-dm-reel-uia.xml).
        val hits = shipped.hits(
            AppTarget.INSTAGRAM,
            IG,
            igTree("clips_viewer_view_pager", "reply_bar_edittext"),
        )
        assertEquals(
            setOf("clips_viewer", "dm_reel_carve_out"),
            hits.map { it.rule.surface }.toSet(),
        )
        assertEquals("SKIP outranks the escape at 0 budget", GateOutcome.SKIP, RuleGate.decide(hits, true, false))
        assertNull(RuleGate.chosen(hits, true, false))
    }

    @Test
    fun `the plain Reels viewer is not carved out and still escapes at 0 budget`() {
        // NEGATIVE CONTROL: 10-ig-reels-viewer-inbudget.xml — same pager, no reply_bar_* node.
        val hits = shipped.hits(AppTarget.INSTAGRAM, IG, igTree("clips_viewer_view_pager"))
        assertTrue("no SKIP rule may match the ordinary viewer", hits.none { it.rule.action == Action.SKIP })
        assertEquals(GateOutcome.ESCAPE, RuleGate.decide(hits, budgetExhausted = true, scheduleLocked = false))
        assertEquals(Action.BACK, RuleGate.chosen(hits, true, false)?.rule?.action)
    }

    @Test
    fun `the TikTok DM-opened video is carved out`() {
        // 18-tt-dm-video.xml: player_view + udm (Button) in the same DetailActivity tree.
        val tree = node(
            children = listOf(
                node(viewId = "$TT:id/player_view", className = "android.widget.FrameLayout"),
                node(viewId = "$TT:id/udm", className = "android.widget.Button"),
            ),
        )
        val hits = shipped.hits(AppTarget.TIKTOK, TT, tree)
        assertEquals(setOf("playback", "dm_carve_out"), hits.map { it.rule.surface }.toSet())
        assertEquals(GateOutcome.SKIP, RuleGate.decide(hits, budgetExhausted = true, scheduleLocked = false))
    }
}
