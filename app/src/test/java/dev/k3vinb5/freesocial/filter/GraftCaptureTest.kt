package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.MatchSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D8 layer (b): the child links are pruned to 2–3 decor nodes while
 * `findAccessibilityNodeInfosByViewId` on the same root still reaches the content. A lookup-graft
 * therefore has to (1) put that node into the snapshot with its real flags, (2) let the untouched
 * matcher match it, (3) keep it mapped to the live node, (4) link it under its nearest captured
 * ancestor when the parent chain survives, otherwise under the synthetic root, and (5) never
 * duplicate a node the walk already captured.
 */
class GraftCaptureTest {

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

    private val homeId = "$YT:id/menu_item_0"

    /**
     * The observed ghost shape: the window root exposes only the navigation bar and an empty
     * `android:id/content`. The Home node is not reachable through those links, but its live parent
     * is the captured `content`, so the graft has a real ancestor to hang from.
     */
    private fun prunedWindow(): Pair<FakeNode, FakeNode> {
        val home = FakeNode(
            viewId = homeId,
            className = "android.widget.Button",
            text = "Home",
            contentDescription = "Home tab",
            bounds = Bounds(0, 1500, 270, 1650),
            clickable = true,
            selected = true,
        )
        val content = FakeNode(viewId = "android:id/content", bounds = Bounds(0, 0, 1080, 2300))
        home.liveParent = content
        val bar = FakeNode(viewId = "android:id/navigationBarBackground", bounds = Bounds(0, 2300, 1080, 2400))
        val root = FakeNode(viewId = "android:id/root", bounds = Bounds(0, 0, 1080, 2400), kids = listOf(bar, content))
        return root to home
    }

    private fun capturePruned(root: FakeNode): NodeTreeCapture =
        NodeTreeBuilder.build(listOf(root))

    @Test
    fun `a lookup-only content node is grafted with its flags and matches a viewId rule`() {
        val (root, home) = prunedWindow()
        val base = capturePruned(root)
        assertFalse("the pruned walk must not reach the Home node", NodeTree.flatten(base.root).any { it.viewId == homeId })

        val capture = NodeTreeBuilder.graft(base, listOf(GraftNode(home, homeId)))

        val graft = NodeTree.flatten(capture.root).single { it.viewId == homeId }
        assertEquals(1, capture.grafts)
        assertTrue(graft.grafted)
        assertEquals("Home", graft.text)
        assertEquals("Home tab", graft.contentDescription)
        assertEquals("android.widget.Button", graft.className)
        assertEquals(Bounds(0, 1500, 270, 1650), graft.bounds)
        assertTrue(graft.visible)
        assertTrue(graft.selected)
        assertTrue(graft.clickable)
        assertFalse(graft.scrollable)

        // The live mapping stays intact: the action will run on the real node.
        assertSame(home, capture.live[graft.index])

        // The matcher is unchanged and sees the grafted node.
        val match = RuleMatcher(MatchSpec(viewId = homeId)).match(capture.root)
        assertNotNull(match)
        assertEquals(graft.index, match?.index)
    }

    @Test
    fun `a grafted node hangs under its nearest captured ancestor`() {
        val (root, home) = prunedWindow()
        val capture = NodeTreeBuilder.graft(capturePruned(root), listOf(GraftNode(home, homeId)))

        val content = NodeTree.flatten(capture.root).single { it.viewId == "android:id/content" }
        assertTrue(content.children.any { it.viewId == homeId && it.grafted })
        assertFalse(capture.root.children.any { it.viewId == homeId })
    }

    @Test
    fun `a graft without a captured ancestor becomes a lookup-graft child of the root`() {
        val detached = FakeNode(
            viewId = "$IG:id/feed_tab",
            className = "android.widget.FrameLayout",
            contentDescription = "Home",
            bounds = Bounds(0, 2200, 216, 2400),
            clickable = true,
        )
        val (root, _) = prunedWindow()
        val capture = NodeTreeBuilder.graft(capturePruned(root), listOf(GraftNode(detached, "$IG:id/feed_tab")))

        val graft = NodeTree.flatten(capture.root).single { it.viewId == "$IG:id/feed_tab" }
        assertTrue(graft.grafted)
        assertEquals(1, capture.grafts)
        assertSame(detached, capture.live[graft.index])
        // No parent overlap: it hangs directly under the (top-most) window root.
        assertTrue(capture.root.children.first().children.contains(graft))
    }

    @Test
    fun `a node reachable both through links and lookup appears exactly once`() {
        val linked = FakeNode(
            viewId = homeId,
            text = "Home",
            bounds = Bounds(10, 10, 200, 200),
            clickable = true,
        )
        val root = FakeNode(viewId = "android:id/root", bounds = Bounds(0, 0, 1080, 2400), kids = listOf(linked))
        val base = capturePruned(root)
        assertEquals(1, NodeTree.flatten(base.root).count { it.viewId == homeId })

        // A second live handle on the same node (same viewId + bounds) must not duplicate it.
        val sameNode = FakeNode(viewId = homeId, text = "Home", bounds = Bounds(10, 10, 200, 200), clickable = true)
        val capture = NodeTreeBuilder.graft(base, listOf(GraftNode(sameNode, homeId)))

        assertEquals(0, capture.grafts)
        assertEquals(1, NodeTree.flatten(capture.root).count { it.viewId == homeId })
    }

    @Test
    fun `the node cap also bounds grafts`() {
        val (root, home) = prunedWindow()
        val base = capturePruned(root)
        assertEquals(3, base.live.size)

        val capture = NodeTreeBuilder.graft(base, listOf(GraftNode(home, homeId)), cap = 3)

        assertEquals(0, capture.grafts)
        assertFalse(NodeTree.flatten(capture.root).any { it.viewId == homeId })
        assertTrue("a dropped graft must be reported as capped", capture.capped)
    }

    @Test
    fun `the scan diagnostic carries the graft count`() {
        val (root, home) = prunedWindow()
        val capture = NodeTreeBuilder.graft(capturePruned(root), listOf(GraftNode(home, homeId)))

        val line = ScanDiagnostics.line(
            appPackage = YT,
            appName = "YouTube",
            nodes = NodeTree.flatten(capture.root).size,
            withViewIds = NodeTree.flatten(capture.root).count { it.viewId != null },
            matchedRules = 1,
            totalRules = 1,
            matched = 1,
            grafted = capture.grafts,
            windows = capture.windows,
            probe = "rootChildCount=2, idProbe=menu_item_0=1",
        )

        assertTrue(line.startsWith("scan $YT (YouTube): "))
        assertTrue(line, line.contains("grafted=1"))
        assertTrue(line, line.contains("matched=1"))
        assertTrue(line, line.contains("windows=1"))
    }
}
