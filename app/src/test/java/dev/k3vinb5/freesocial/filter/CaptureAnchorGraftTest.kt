package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.core.loadRuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D8 round 3 (R1): on the pruned YouTube home the Shorts shelf's header and cards carry **no**
 * resource-id, so neither the walk nor a per-rule view-id lookup can reach them — but their nearest
 * id-bearing ancestor, `com.google.android.youtube:id/results`, is reachable through
 * `findAccessibilityNodeInfosByViewId` and its child links are intact below it.
 *
 * Shape taken from `reverse/runtime-reno/pt-yt-home3.xml` (135 nodes, pt-PT): `results` is a
 * scrollable RecyclerView `[0,398][1080,2158]`; two `ViewGroup`s down sits the `"Shorts"` header
 * `[192,443][353,509]` and the four `"… – reproduzir Short"` cards.
 */
class CaptureAnchorGraftTest {

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
        override val childCount: Int get() = kids.size
        override fun child(index: Int): NodeSource? = kids.getOrNull(index)
    }

    private val resultsId = "$YT:id/results"

    private val shelfRule = loadRuleSet(
        java.io.File("src/main/assets/rules.json").readText(),
    ).rules.single { it.id == "yt_shorts_shelf" }

    /** Ghost window: root -> [navigationBarBackground, empty android:id/content]. */
    private fun ghostRoot(): FakeNode {
        val content = FakeNode(viewId = "android:id/content", bounds = Bounds(0, 0, 1080, 2300))
        val bar = FakeNode(viewId = "android:id/navigationBarBackground", bounds = Bounds(0, 2300, 1080, 2400))
        return FakeNode(viewId = "android:id/root", bounds = Bounds(0, 0, 1080, 2400), kids = listOf(bar, content))
    }

    /** `results` + the pt-PT shelf from the dump; not linked from the ghost root. */
    private fun resultsContainer(): FakeNode {
        val header = FakeNode(
            className = "android.view.ViewGroup",
            contentDescription = "Shorts",
            bounds = Bounds(192, 443, 353, 509),
        )
        val card = FakeNode(
            className = "android.view.ViewGroup",
            contentDescription = "Mac mini M6: is 32GB enough to run local AI? – reproduzir Short",
            bounds = Bounds(551, 1314, 1044, 2053),
        )
        return FakeNode(
            viewId = resultsId,
            className = "android.support.v7.widget.RecyclerView",
            bounds = Bounds(0, 398, 1080, 2158),
            scrollable = true,
            kids = listOf(header, card),
        )
    }

    private fun captureWithContainer(): Pair<NodeTreeCapture, FakeNode> {
        val results = resultsContainer()
        val capture = NodeTreeBuilder.graft(
            NodeTreeBuilder.build(listOf(ghostRoot())),
            listOf(GraftNode(results, resultsId, subtree = true)),
        )
        return capture to results
    }

    @Test
    fun `the container id is declared for youtube and no other package`() {
        assertEquals(listOf(resultsId), CaptureAnchors.idsFor(YT))
        assertTrue(CaptureAnchors.idsFor(IG).isEmpty())
        assertTrue(CaptureAnchors.idsFor(null).isEmpty())
    }

    @Test
    fun `grafting the container walks its children and the desc rule matches the pt-PT shelf`() {
        val (capture, _) = captureWithContainer()
        val nodes = NodeTree.flatten(capture.root)

        assertEquals("container + header + card", 3, capture.grafts)
        val header = nodes.single { it.contentDescription == "Shorts" }
        assertTrue("children recovered below the graft are marked", header.grafted)
        assertTrue(nodes.any { it.contentDescription?.endsWith("– reproduzir Short") == true && it.grafted })

        val match = RuleMatcher(shelfRule.match).match(capture.root)
        assertNotNull("the untouched matcher must reach the shelf through the graft", match)
        assertEquals(header.index, match?.index)
    }

    @Test
    fun `scroll past frames on the grafted container, which is also the live scroll node`() {
        val (capture, results) = captureWithContainer()
        val match = RuleMatcher(shelfRule.match).match(capture.root)!!
        val plan = ScrollPastGesture.plan(capture.root, match.index)

        assertEquals(ScrollPath.ACTION_FORWARD, plan.path)
        assertEquals(resultsId, plan.frameNode?.viewId)
        assertNotNull(plan.swipe)
        // The live mapping still resolves the frame: ACTION_SCROLL_FORWARD runs on the real node.
        assertSame(results, capture.live[plan.frameNode!!.index])
    }

    @Test
    fun `a plain rule-id graft stays a single node and cannot drag in another surface`() {
        // The Shorts player exposes `reel_recycler`; a plain id graft must not descend, or the
        // player subtree would make the (layer-1) shelf rule match while the player is up.
        val player = FakeNode(
            viewId = "$YT:id/reel_recycler",
            className = "android.support.v7.widget.RecyclerView",
            bounds = Bounds(0, 0, 1080, 2158),
            scrollable = true,
            kids = listOf(
                FakeNode(className = "android.view.ViewGroup", contentDescription = "Shorts"),
            ),
        )
        val capture = NodeTreeBuilder.graft(
            NodeTreeBuilder.build(listOf(ghostRoot())),
            listOf(GraftNode(player, "$YT:id/reel_recycler")),
        )
        assertEquals(1, capture.grafts)
        assertFalse(NodeTree.flatten(capture.root).any { it.contentDescription == "Shorts" })
    }

    @Test
    fun `a container already walked through the links is not grafted twice`() {
        // An intact platform: `results` is captured through the child links, so the lookup is a no-op.
        val results = resultsContainer()
        val linked = FakeNode(
            viewId = "android:id/root",
            bounds = Bounds(0, 0, 1080, 2400),
            kids = listOf(FakeNode(viewId = resultsId, className = results.className, bounds = results.bounds)),
        )
        val base = NodeTreeBuilder.build(listOf(linked))
        val capture = NodeTreeBuilder.graft(base, listOf(GraftNode(results, resultsId, subtree = true)))

        assertEquals(0, capture.grafts)
        assertEquals(1, NodeTree.flatten(capture.root).count { it.viewId == resultsId })
        assertFalse(NodeTree.flatten(capture.root).any { it.contentDescription == "Shorts" })
    }
}
