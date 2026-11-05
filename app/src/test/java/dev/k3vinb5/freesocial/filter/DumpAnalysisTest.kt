package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Action
import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.core.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The inspector's offline matcher-over-dump helper: enabled rules only, with anchor evidence. */
class DumpAnalysisTest {

    private fun dumpNode(
        viewId: String? = null,
        text: String? = null,
        description: String? = null,
        bounds: String = "[0,0][1080,2400]",
        visible: Boolean = true,
        selected: Boolean = false,
    ) = DumpNode(
        viewId = viewId,
        className = "android.widget.FrameLayout",
        text = text,
        contentDescription = description,
        bounds = bounds,
        visible = visible,
        selected = selected,
    )

    private val reelsRule = Rule(
        id = "ig_reels_tab",
        appPackage = IG,
        surface = "reels_tab",
        layer = 1,
        match = MatchSpec(
            viewId = "$IG:id/clips_tab",
            contentDescriptionRegex = "(?i)^reels$",
            selected = true,
        ),
        action = Action.TAP_HOME_TAB,
    )

    @Test
    fun `enabled rules that match the dump are listed with their anchors`() {
        val nodes = listOf(
            dumpNode(viewId = "android:id/content"),
            dumpNode(viewId = "$IG:id/clips_tab", description = "Reels", selected = true),
        )

        val matches = DumpAnalysis.matches(nodes, listOf(reelsRule), IG)

        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals("ig_reels_tab", match.rule.id)
        assertTrue(match.anchors.contains("viewId=$IG:id/clips_tab"))
        assertTrue(match.anchors.contains("desc=\"Reels\""))
    }

    @Test
    fun `disabled, foreign and non-matching rules are excluded`() {
        val disabled = reelsRule.copy(id = "retired", enabled = false)
        val foreign = reelsRule.copy(id = "foreign", appPackage = YT)
        val nonMatching = reelsRule.copy(
            id = "other",
            match = MatchSpec(viewId = "$IG:id/not_here"),
        )
        val nodes = listOf(dumpNode(viewId = "$IG:id/clips_tab", description = "Reels", selected = true))

        val matches = DumpAnalysis.matches(nodes, listOf(disabled, foreign, nonMatching, reelsRule), IG)

        assertEquals(listOf("ig_reels_tab"), matches.map { it.rule.id })
    }

    @Test
    fun `an invisible node in the dump cannot satisfy a rule`() {
        val nodes = listOf(
            dumpNode(viewId = "$IG:id/clips_tab", description = "Reels", visible = false, selected = true),
        )

        assertTrue(DumpAnalysis.matches(nodes, listOf(reelsRule), IG).isEmpty())
    }

    @Test
    fun `exported bounds round-trip and invalid bounds stay mappable`() {
        val bounds = DumpAnalysis.parseBounds("[12,34][56,78]")
        assertEquals(12, bounds.left)
        assertEquals(34, bounds.top)
        assertEquals(56, bounds.right)
        assertEquals(78, bounds.bottom)

        val fallback = DumpAnalysis.parseBounds("garbage")
        assertFalse(fallback.isEmpty)
    }

    @Test
    fun `provenance stamps come from the rule or the RULES map`() {
        assertEquals("Instagram 454.0.0.54.77", RuleProvenance.versionFor(reelsRule))
        val stamped = reelsRule.copy(verifiedAgainstAppVersion = "Instagram 999")
        assertEquals("Instagram 999", RuleProvenance.versionFor(stamped))
    }
}
