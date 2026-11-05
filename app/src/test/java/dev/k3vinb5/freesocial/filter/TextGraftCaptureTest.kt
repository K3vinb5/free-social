package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Action
import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.settings.AppTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D8 layer (c): on ColorOS the child links stay pruned even though the framework's own search
 * still reaches the content. The rules that matter on YouTube/TikTok are anchored on text and
 * content description (never on view ids), so the graft has to be driven by
 * `findAccessibilityNodeInfosByText` with the literal values derived from the rule regexes.
 *
 * These tests use the production seam: [RuleLookup] derives the query strings, a fake text search
 * returns the live nodes the way the framework does (case-insensitive containment on text *or*
 * description), and [NodeTreeBuilder.graft] splices them in unchanged.
 */
class TextGraftCaptureTest {

    private class FakeNode(
        override val viewId: String? = null,
        override val className: String? = "android.widget.FrameLayout",
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val bounds: Bounds = Bounds(0, 0, 1080, 2400),
        override val visible: Boolean = true,
        override val selected: Boolean = false,
        override val clickable: Boolean = false,
        override val scrollable: Boolean = false,
        private val kids: List<FakeNode> = emptyList(),
    ) : NodeSource {
        var liveParent: NodeSource? = null

        init {
            kids.forEach { it.liveParent = this }
        }

        override val childCount: Int get() = kids.size
        override fun child(index: Int): NodeSource? = kids.getOrNull(index)
        override fun parent(): NodeSource? = liveParent
    }

    /** The framework's behaviour: case-insensitive containment over text and content description. */
    private fun findAllByText(candidates: List<FakeNode>, value: String): List<FakeNode> {
        val needle = value.lowercase()
        return candidates.filter { node ->
            node.text?.lowercase()?.contains(needle) == true ||
                node.contentDescription?.lowercase()?.contains(needle) == true
        }
    }

    /** The shipped YouTube shelf rule, verbatim (EN + pt locale alternates). */
    private val shortsDescRegex = "(?i)(^shorts$| - play short$| – reproduzir short$)"

    /** Ghost window: root -> [navigationBarBackground, empty android:id/content]. */
    private fun prunedWindow(): Pair<FakeNode, FakeNode> {
        val content = FakeNode(viewId = "android:id/content", bounds = Bounds(0, 0, 1080, 2300))
        val bar = FakeNode(viewId = "android:id/navigationBarBackground", bounds = Bounds(0, 2300, 1080, 2400))
        val root = FakeNode(viewId = "android:id/root", bounds = Bounds(0, 0, 1080, 2400), kids = listOf(bar, content))
        return root to content
    }

    @Test
    fun `literal queries are derived from the shipped text and description rules`() {
        // YouTube Shorts shelf: three alternative descriptions (EN header/card + pt card), all literal.
        assertEquals(
            listOf("shorts", "- play short", "– reproduzir short"),
            RuleLookup.literalQueries(shortsDescRegex),
        )
        // TikTok playback chrome: five alternatives, two with an escaped dot.
        assertEquals(
            listOf(
                "for you",
                "like video.",
                "read or add comments",
                "share video.",
                "add or remove this video from favorites",
            ),
            RuleLookup.literalQueries(
                "(?i)^for you\$|like video\\.|read or add comments|share video\\.|" +
                    "add or remove this video from favorites",
            ),
        )
        // Instagram Reels tab.
        assertEquals(listOf("reels"), RuleLookup.literalQueries("(?i)^reels\$"))
        // A real regex cannot be asked of findAccessibilityNodeInfosByText.
        assertEquals(emptyList<String>(), RuleLookup.literalQueries("^shorts.*shelf"))
        assertEquals(emptyList<String>(), RuleLookup.literalQueries("a|b|c"))
        assertEquals(emptyList<String>(), RuleLookup.literalQueries(null))
    }

    @Test
    fun `the applicable rules expose their literal values at scan time`() {
        val rules = listOf(
            Rule(
                id = "yt_shorts_shelf",
                appPackage = YT,
                surface = "shorts_shelf",
                layer = 1,
                match = MatchSpec(
                    contentDescriptionRegex = shortsDescRegex,
                    classNameRegex = "android\\.view\\.ViewGroup",
                ),
                action = Action.SCROLL_PAST,
            ),
            Rule(
                id = "yt_shorts_player",
                appPackage = YT,
                surface = "shorts_player",
                layer = 2,
                match = MatchSpec(viewId = "$YT:id/reel_recycler"),
                action = Action.BACK,
            ),
        )
        val matcher = RuleSetMatcher(rules)

        // The view-id rule contributes nothing here; the text rule contributes all three branches.
        assertEquals(
            listOf("shorts", "- play short", "– reproduzir short"),
            matcher.lookupTexts(AppTarget.YOUTUBE, YT),
        )
        // Unrelated package: nothing to look up.
        assertTrue(matcher.lookupTexts(AppTarget.INSTAGRAM, IG).isEmpty())
    }

    @Test
    fun `a text-lookup node without any view id is grafted and the rule matches through it`() {
        val (root, content) = prunedWindow()
        val header = FakeNode(
            className = "android.view.ViewGroup",
            contentDescription = "Shorts",
            bounds = Bounds(0, 900, 1080, 1050),
        )
        // The live parent chain survives even though the child links were pruned.
        header.liveParent = content
        val base = NodeTreeBuilder.build(listOf(root))
        assertTrue(NodeTree.flatten(base.root).none { it.contentDescription == "Shorts" })

        val grafts = RuleLookup.literalQueries(shortsDescRegex).flatMap { value ->
            findAllByText(listOf(header), value).map { GraftNode(it, value) }
        }

        val capture = NodeTreeBuilder.graft(base, grafts)

        val graft = NodeTree.flatten(capture.root).single { it.contentDescription == "Shorts" }
        assertEquals(1, capture.grafts)
        assertTrue(graft.grafted)
        assertNull(graft.viewId)
        assertSame(header, capture.live[graft.index])
        // It hangs under the captured ancestor, not the synthetic root.
        assertTrue(
            NodeTree.flatten(capture.root).single { it.viewId == "android:id/content" }.children.contains(graft),
        )

        // RuleMatcher is untouched and now reaches the desc-anchored rule.
        val match = RuleMatcher(
            MatchSpec(contentDescriptionRegex = shortsDescRegex, classNameRegex = "android\\.view\\.ViewGroup"),
        ).match(capture.root)
        assertNotNull(match)
        assertEquals(graft.index, match?.index)
    }

    @Test
    fun `a node returned by both the id lookup and the text lookup is grafted exactly once`() {
        val (root, _) = prunedWindow()
        val reelsTab = FakeNode(
            viewId = "$IG:id/clips_tab",
            className = "android.widget.FrameLayout",
            contentDescription = "Reels",
            bounds = Bounds(0, 2250, 216, 2400),
            selected = true,
        )
        val base = NodeTreeBuilder.build(listOf(root))

        val capture = NodeTreeBuilder.graft(
            base,
            listOf(GraftNode(reelsTab, "$IG:id/clips_tab"), GraftNode(reelsTab, "reels")),
        )

        assertEquals(1, capture.grafts)
        assertEquals(1, NodeTree.flatten(capture.root).count { it.viewId == "$IG:id/clips_tab" })
        assertEquals(1, NodeTree.flatten(capture.root).count { it.grafted })
    }

    @Test
    fun `the node cap bounds text grafts too and marks the capture capped`() {
        val (root, content) = prunedWindow()
        val header = FakeNode(contentDescription = "Shorts", bounds = Bounds(0, 900, 1080, 1050))
        header.liveParent = content
        val base = NodeTreeBuilder.build(listOf(root))

        val capture = NodeTreeBuilder.graft(base, listOf(GraftNode(header, "shorts")), cap = base.live.size)

        assertEquals(0, capture.grafts)
        assertTrue("a dropped text graft must be reported as capped", capture.capped)
    }
}
