package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Action
import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.core.hasLocaleProofSignal
import dev.k3vinb5.freesocial.core.loadRuleSet
import dev.k3vinb5.freesocial.core.proofKinds
import dev.k3vinb5.freesocial.settings.AppTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rule #271 — locale-proof filtering.
 *
 * The user's phone language is not fixed (EN today, PT/ES tomorrow), so a match may only be proved
 * by a locale-independent signal. These tests hold the shipped catalog and the matcher to that:
 * every enabled rule has a proof criterion, every proof anchor is dump-cited in RULES.md, and a
 * rule keeps matching when all of its localized hints are absent or reworded. The DM carve-outs
 * likewise keep shielding on structure (id + class), not on "Reply privately"-style strings.
 */
class LocaleProofRulesTest {

    private val rules: List<Rule> = loadRuleSet(File("src/main/assets/rules.json").readText()).rules

    private fun rule(id: String): Rule = rules.single { it.id == id }

    private fun match(id: String, root: NodeSnapshot): NodeSnapshot? =
        RuleMatcher(rule(id).match).match(root)

    @Test
    fun `every enabled rule carries a locale-proof criterion`() {
        val enabled = rules.filter { it.enabled }
        assertEquals(6, enabled.size)
        for (entry in enabled) {
            assertTrue(
                "rule '${entry.id}' has no locale-proof criterion: ${entry.match}",
                entry.match.hasLocaleProofSignal,
            )
            assertTrue("rule '${entry.id}' proof kinds", entry.match.proofKinds.isNotEmpty())
        }
    }

    @Test
    fun `every enabled rule's locale-proof anchor cites a dump file`() {
        val table = localeProofSection()
        for (entry in rules.filter { it.enabled }) {
            val row = table.firstOrNull { it.trimStart().startsWith("| `${entry.id}`") }
            assertNotNull("RULES.md locale-proof table has no row for '${entry.id}'", row)
            assertTrue(
                "row for '${entry.id}' must cite an exact .xml dump: $row",
                row!!.contains(".xml"),
            )
        }
    }

    @Test
    fun `the ig reels tab still matches when its localized description changed`() {
        // A pt-PT/es device (or an IG reword) never says "Reels"; the id + class + selected flag
        // must prove the surface on their own.
        val tab = node(
            viewId = "$IG:id/clips_tab",
            className = "android.widget.FrameLayout",
            description = "Clipes",
            selected = true,
            bounds = Bounds(216, 2172, 432, 2298),
        )
        assertNotNull(match("ig_reels_tab", node(children = listOf(tab))))
        // The same tab unselected is the feed/DM -> no match, still without any string.
        val unselected = node(
            viewId = "$IG:id/clips_tab",
            className = "android.widget.FrameLayout",
            description = "Clipes",
            selected = false,
            bounds = Bounds(216, 2172, 432, 2298),
        )
        assertNull(match("ig_reels_tab", node(children = listOf(unselected))))
    }

    @Test
    fun `tiktok playback matches on the player container with no FYP strings`() {
        // 234958 shape, but every accessibility string localized away: the player id alone proves it
        // (the 2026-11-06 Reno4 audit dropped the video-surface class from the key).
        val tree = node(
            bounds = Bounds(0, 0, 1080, 2424),
            children = listOf(
                node(
                    viewId = "$TT:id/player_view",
                    className = "android.widget.FrameLayout",
                    bounds = Bounds(0, 0, 1080, 2169),
                    children = listOf(
                        node(className = "android.view.TextureView", bounds = Bounds(0, 124, 1080, 2044)),
                    ),
                ),
            ),
        )
        val hit = match("tiktok_playback", tree)
        assertNotNull("the player id alone must prove playback", hit)
        assertEquals("$TT:id/player_view", hit!!.viewId)
    }

    @Test
    fun `a tiktok DM video still skips the carve-out on structure alone`() {
        // 001928 shape with no strings at all: the shared video plus the composer id.
        val tree = node(
            bounds = Bounds(0, 0, 1080, 2424),
            children = listOf(
                node(
                    viewId = "$TT:id/player_view",
                    className = "android.widget.FrameLayout",
                    bounds = Bounds(0, 137, 1080, 2169),
                    children = listOf(
                        node(className = "android.view.TextureView", bounds = Bounds(0, 137, 1080, 2057)),
                    ),
                ),
                node(
                    viewId = "$TT:id/udm",
                    className = "android.widget.Button",
                    bounds = Bounds(137, 2173, 647, 2280),
                ),
            ),
        )
        val hits = RuleSetMatcher(rules).hits(AppTarget.TIKTOK, TT, tree)
        assertTrue("playback must be detected", hits.any { it.rule.id == "tiktok_playback" })
        assertTrue("DM composer must be detected", hits.any { it.rule.id == "tiktok_dm_carve_out" })
        assertEquals(Action.SKIP, hits.single { it.rule.id == "tiktok_dm_carve_out" }.rule.action)
        assertEquals(GateOutcome.SKIP, RuleGate.decide(hits, budgetExhausted = true, scheduleLocked = true))
        assertNull(RuleGate.chosen(hits, budgetExhausted = true, scheduleLocked = true))
    }

    @Test
    fun `an ig shared-reel thread still skips the carve-out on structure alone`() {
        val tree = node(
            bounds = Bounds(0, 0, 1080, 2424),
            children = listOf(
                node(
                    viewId = "$IG:id/clips_viewer_view_pager",
                    className = "androidx.viewpager.widget.ViewPager",
                    bounds = Bounds(0, 152, 1080, 2172),
                ),
                node(
                    viewId = "$IG:id/row_thread_composer_edittext",
                    className = "android.widget.EditText",
                    bounds = Bounds(158, 2146, 621, 2277),
                ),
            ),
        )
        val hits = RuleSetMatcher(rules).hits(AppTarget.INSTAGRAM, IG, tree)
        assertTrue(hits.any { it.rule.id == "ig_clips_viewer" })
        assertTrue(hits.any { it.rule.id == "ig_dm_carve_out" })
        assertEquals(GateOutcome.SKIP, RuleGate.decide(hits, budgetExhausted = true, scheduleLocked = true))
    }

    /** The rows of the "## Locale-proof anchors" table, up to the next `## ` heading. */
    private fun localeProofSection(): List<String> {
        val lines = File("../docs/rules/RULES.md").readLines()
        val start = lines.indexOfFirst { it.startsWith("## Locale-proof anchors") }
        assertTrue("RULES.md must keep the '## Locale-proof anchors' section", start >= 0)
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("## ") }
        return if (end < 0) rest else rest.take(end)
    }
}
