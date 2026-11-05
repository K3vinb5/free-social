package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D6: the armed-dump capture aggregates every window of the target package, top-most first, keeps
 * nodes whose viewId is null (H2: the OS stripped IDs — those nodes are still real), reports
 * per-window stats, and flags a cap truncation instead of silently shrinking the tree.
 */
class CaptureWindowsTest {

    private class FakeNode(
        override val viewId: String? = null,
        override val className: String? = "android.widget.FrameLayout",
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val bounds: Bounds = Bounds(0, 0, 1080, 2424),
        override val visible: Boolean = true,
        override val selected: Boolean = false,
        override val clickable: Boolean = false,
        override val scrollable: Boolean = false,
        private val kids: List<FakeNode> = emptyList(),
    ) : NodeSource {
        override val childCount: Int get() = kids.size
        override fun child(index: Int): NodeSource? = kids.getOrNull(index)
    }

    @Test
    fun `two windows are aggregated with the top-most first`() {
        val bottom = FakeNode(
            text = "bottom-root",
            bounds = Bounds(0, 0, 1080, 2424),
            kids = listOf(FakeNode(viewId = "$IG:id/bottom_child", text = "bottom_child", bounds = Bounds(0, 0, 1080, 100))),
        )
        val top = FakeNode(
            text = "top-root",
            bounds = Bounds(0, 800, 1080, 2424),
            kids = listOf(FakeNode(viewId = "$IG:id/top_child", text = "top_child", bounds = Bounds(0, 800, 1080, 900))),
        )

        val capture = NodeTreeBuilder.buildWindows(
            listOf(
                WindowSource(bottom, title = "bottom", layer = 1, packageName = IG),
                WindowSource(top, title = "top", layer = 9, packageName = IG),
            ),
        )

        // Both windows aggregated, top-most (layer 9) first.
        val texts = NodeTree.flatten(capture.root).map { it.text }
        assertTrue(texts.containsAll(listOf("top-root", "top_child", "bottom-root", "bottom_child")))
        assertEquals(listOf("top", "bottom"), capture.windows.map { it.title })
        assertEquals("top-root", capture.root.children[0].text)
        assertEquals("bottom-root", capture.root.children[1].text)
        // Per-window stats: 2 nodes each, one view id each.
        assertEquals(2, capture.windows[0].nodes)
        assertEquals(1, capture.windows[0].viewIds)
        assertEquals(9, capture.windows[0].layer)
        assertEquals(2, capture.windows[1].nodes)
        assertEquals(1, capture.windows[1].viewIds)
        assertFalse(capture.capped)
    }

    @Test
    fun `nodes with a null view id are kept, and a zero-id tree is still complete`() {
        val root = FakeNode(
            text = "root",
            kids = listOf(
                FakeNode(text = "For You", contentDescription = "For You", bounds = Bounds(698, 137, 899, 289)),
                FakeNode(className = "android.view.SurfaceView", bounds = Bounds(0, 300, 1080, 2000)),
            ),
        )

        val capture = NodeTreeBuilder.buildWindows(
            listOf(WindowSource(root, title = "fyp", layer = 2, packageName = TT)),
        )

        val nodes = NodeTree.flatten(capture.root).filter { it.index >= 0 }
        assertEquals(3, nodes.size)
        assertTrue(nodes.all { it.viewId == null })
        assertEquals(0, capture.windows.single().viewIds)
        assertEquals(3, capture.windows.single().nodes)
    }

    @Test
    fun `the 400-node cap truncation is flagged, not silently dropped`() {
        fun window(prefix: String, layer: Int): WindowSource {
            val kids = (1..5).map { FakeNode(text = "$prefix-$it", bounds = Bounds(0, it * 100, 1080, it * 100 + 50)) }
            val root = FakeNode(text = "$prefix-root", kids = kids)
            return WindowSource(root, title = prefix, layer = layer, packageName = IG)
        }

        val truncated = NodeTreeBuilder.buildWindows(listOf(window("top", 2), window("bottom", 1)), cap = 4)
        assertEquals(4, truncated.live.size)
        assertTrue(truncated.capped)

        val complete = NodeTreeBuilder.buildWindows(listOf(window("only", 1)), cap = 400)
        assertEquals(6, complete.live.size)
        assertFalse(complete.capped)
    }
}
