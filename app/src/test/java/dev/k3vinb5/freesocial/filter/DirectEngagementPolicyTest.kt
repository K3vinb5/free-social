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

/**
 * End-to-end policy proof on the JVM (no Android): DIRECT ENGAGEMENT ONLY.
 *
 * Mirrors `FilterService.scan` — capture → RuleGate verdict → ActionArbiter → one action — plus the
 * stopwatch for in-budget accrual, so each acceptance clause is executable.
 */
class DirectEngagementPolicyTest {

    private val player = RuleHit(
        rule(
            id = "yt_shorts_player",
            appPackage = YT,
            surface = "shorts_player",
            layer = 2,
            match = MatchSpec(viewId = "$YT:id/reel_recycler"),
            action = Action.BACK,
        ),
        node(viewId = "$YT:id/reel_recycler"),
    )

    private class Engine {
        val arbiter = ActionArbiter()
        val actions = mutableListOf<Action>()

        /** One scan; returns the gate verdict and performs at most one action. */
        fun scan(hits: List<RuleHit>, exhausted: Boolean, locked: Boolean, now: Long): GateOutcome {
            val outcome = RuleGate.decide(hits, exhausted, locked)
            arbiter.observe(hits.map { "${it.rule.appPackage}/${it.rule.surface}" }.toSet())
            if (outcome != GateOutcome.ESCAPE) return outcome
            val hit = RuleGate.chosen(hits, exhausted, locked) ?: return outcome
            if (!arbiter.tryAcquire(hit.rule.surface, hit.rule.appPackage, now)) return outcome
            actions += hit.rule.action
            arbiter.confirm(hit.rule.surface, hit.rule.appPackage, hit.rule.action in ESCAPE_ACTIONS, acted = true)
            return outcome
        }
    }

    @Test
    fun `in-budget entry into a playback surface performs zero actions and accrues`() {
        val engine = Engine()
        val stopwatch = SurfaceStopwatch()

        assertEquals(GateOutcome.PAY, engine.scan(listOf(player), exhausted = false, locked = false, now = 0))
        // The stopwatch is what "pays": mirror FilterService.trackBudget on a PAY verdict.
        stopwatch.enter("$YT/shorts_player", 0)
        assertEquals(1L, stopwatch.tick(1_000))
        assertEquals(1L, stopwatch.tick(2_000))

        assertTrue("no escape action while the budget lasts", engine.actions.isEmpty())
        assertNull(RuleGate.chosen(listOf(player), budgetExhausted = false, scheduleLocked = false))
        assertEquals("$YT/shorts_player", stopwatch.activeSurface)
    }

    @Test
    fun `exhausted entry produces exactly one escape, not one per stale scan`() {
        val engine = Engine()
        assertEquals(GateOutcome.ESCAPE, engine.scan(listOf(player), exhausted = true, locked = false, now = 0))
        // Three stale-tree scans in the same episode, well past the debounce.
        engine.scan(listOf(player), exhausted = true, locked = false, now = 1_300)
        engine.scan(listOf(player), exhausted = true, locked = false, now = 5_000)
        assertEquals(listOf(Action.BACK), engine.actions)
    }

    @Test
    fun `N rapid re-entries after exhaustion produce N identical escapes`() {
        val engine = Engine()
        repeat(5) { index ->
            val now = index * 100L
            assertEquals(GateOutcome.ESCAPE, engine.scan(listOf(player), exhausted = true, locked = false, now))
            engine.arbiter.observe(emptySet()) // the user is back on the feed
        }
        assertEquals(List(5) { Action.BACK }, engine.actions)
    }

    @Test
    fun `an armed inspector capture performs zero actions and restores afterwards`() {
        DumpArm.cancel()
        try {
            DumpArm.arm("policy test", now = 0)
            assertTrue(DumpArm.isArmed(0))
            assertEquals(
                GateOutcome.NONE,
                RuleGate.decide(listOf(player), budgetExhausted = true, scheduleLocked = true, dumpArmed = DumpArm.isArmed(0)),
            )
            assertNull(RuleGate.chosen(listOf(player), true, true, dumpArmed = true))

            // Capture completes → enforcement is back automatically.
            assertEquals("policy test", DumpArm.consume(0))
            assertFalse(DumpArm.isArmed(0))
            assertEquals(
                GateOutcome.ESCAPE,
                RuleGate.decide(listOf(player), budgetExhausted = true, scheduleLocked = false, dumpArmed = DumpArm.isArmed(0)),
            )

            // Expiry restores it too.
            DumpArm.arm("expiry test", now = 0)
            assertFalse(DumpArm.isArmed(DumpArm.EXPIRY_MS))
        } finally {
            DumpArm.cancel()
        }
    }

    @Test
    fun `shipped passive rules are retired and perform no actions`() {
        val rules = loadRuleSet(java.io.File("src/main/assets/rules.json").readText()).rules
        val matcher = RuleSetMatcher(rules)
        val feed = node(
            children = listOf(
                node(viewId = "$IG:id/video_container"),
                node(description = "Shorts"),
                node(description = "Clip - play Short"),
            ),
        )
        val hits = matcher.hits(AppTarget.INSTAGRAM, IG, feed)
        assertTrue("retired feed rules cannot even match", hits.isEmpty())
        assertEquals(GateOutcome.NONE, RuleGate.decide(hits, budgetExhausted = true, scheduleLocked = true))

        val enabled = rules.filter { it.enabled }
        assertEquals(7, enabled.size)
        assertTrue(
            "every enabled non-carve-out rule is a direct-engagement surface",
            enabled.filter { it.action != Action.SKIP }.all {
                SurfaceClassifier.classify(it.appPackage, it.surface) == SurfaceClass.DIRECT
            },
        )
        assertEquals(0, enabled.count { it.action == Action.SCROLL_PAST })
        assertTrue(rules.filter { !it.enabled }.all { it.action == Action.SCROLL_PAST })
    }

    @Test
    fun `surface classifier marks every direct surface and every passive one`() {
        assertEquals(SurfaceClass.DIRECT, SurfaceClassifier.classify(IG, "clips_viewer"))
        assertEquals(SurfaceClass.DIRECT, SurfaceClassifier.classify(IG, "reels_tab"))
        assertEquals(SurfaceClass.DIRECT, SurfaceClassifier.classify(YT, "shorts_player"))
        assertEquals(SurfaceClass.DIRECT, SurfaceClassifier.classify(TT, "playback"))
        assertEquals(SurfaceClass.DIRECT, SurfaceClassifier.classify(TT_GLOBAL, "playback"))
        assertEquals(SurfaceClass.PASSIVE, SurfaceClassifier.classify(IG, "feed_reel_cell"))
        assertEquals(SurfaceClass.PASSIVE, SurfaceClassifier.classify(YT, "shorts_shelf"))
        assertEquals(SurfaceClass.UNKNOWN, SurfaceClassifier.classify(IG, "clips_viewer_renamed"))
    }

    private companion object {
        val ESCAPE_ACTIONS = setOf(Action.BACK, Action.TAP_HOME_TAB, Action.REDIRECT_HOME)
    }
}
