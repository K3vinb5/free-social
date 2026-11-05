package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2b: one scan aggregates **all** of the foreground package's windows under the shared 400-node
 * cap and de-duplicates nodes that appear in more than one window.
 */
class NodeTreeBuilderTest {

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

    private fun texts(root: NodeSnapshot): List<String?> = NodeTree.flatten(root).map { it.text }

    @Test
    fun `nodes from two windows land in one tree`() {
        val fyp = FakeNode(
            text = "fyp-root",
            bounds = Bounds(0, 0, 1080, 2424),
            kids = listOf(FakeNode(text = "For You", bounds = Bounds(698, 137, 899, 289))),
        )
        val sheet = FakeNode(
            text = "sheet-root",
            bounds = Bounds(0, 1000, 1080, 2424),
            kids = listOf(FakeNode(text = "Choose how ads are shown", bounds = Bounds(0, 1000, 1080, 2424))),
        )

        val capture = NodeTreeBuilder.build(listOf(fyp, sheet))

        assertTrue(texts(capture.root).containsAll(listOf("fyp-root", "For You", "sheet-root", "Choose how ads are shown")))
        assertEquals(4, capture.live.size)
        // The synthetic root is never a rule anchor.
        assertFalse(capture.root.visible)
        // index -> live mapping spans both windows.
        assertEquals("sheet-root", capture.live[capture.root.children[1].index].text)
    }

    @Test
    fun `a node exposed by two windows is walked once`() {
        fun window(): FakeNode = FakeNode(
            text = "duplicate-root",
            kids = listOf(FakeNode(text = "Like video. 561 likes", bounds = Bounds(912, 1375, 1080, 1533))),
        )

        val capture = NodeTreeBuilder.build(listOf(window(), window()))

        assertEquals(2, capture.live.size)
        assertEquals(1, texts(capture.root).count { it == "duplicate-root" })
        assertEquals(1, texts(capture.root).count { it == "Like video. 561 likes" })
    }

    @Test
    fun `the cap is budgeted across windows`() {
        fun window(prefix: String): FakeNode = FakeNode(
            text = "$prefix-root",
            kids = (1..5).map { FakeNode(text = "$prefix-$it", bounds = Bounds(0, it * 100, 1080, it * 100 + 50)) },
        )

        val capture = NodeTreeBuilder.build(listOf(window("a"), window("b")), cap = 3)

        assertEquals(3, capture.live.size)
        // The second window starts only after the first one spent the budget.
        assertTrue(texts(capture.root).contains("a-1"))
        assertFalse(texts(capture.root).contains("b-root"))
        assertEquals(3, NodeTree.flatten(capture.root).count { it.index >= 0 })
    }

    @Test
    fun `no windows yields an empty capture and no match`() {
        val capture = NodeTreeBuilder.build(emptyList())
        assertEquals(0, capture.live.size)
        assertTrue(NodeTree.flatten(capture.root).size == 1)
    }

    @Test
    fun `scrollable flag survives the capture walk`() {
        val list = FakeNode(className = "androidx.recyclerview.widget.RecyclerView", scrollable = true, bounds = Bounds(0, 152, 1080, 2172))
        val capture = NodeTreeBuilder.build(listOf(FakeNode(kids = listOf(list))))
        assertTrue(NodeTree.flatten(capture.root).any { it.scrollable && it.className?.contains("RecyclerView") == true })
    }
}
