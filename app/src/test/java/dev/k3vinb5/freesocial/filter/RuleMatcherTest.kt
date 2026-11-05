package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.MatchSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuleMatcherTest {

    private fun match(spec: MatchSpec, vararg nodes: NodeSnapshot): NodeSnapshot? =
        RuleMatcher(spec).match(nodes.flatMap { NodeTree.flatten(it) })

    @Test
    fun `one criterion matches a visible node`() {
        val spec = MatchSpec(viewId = "$IG:id/clips_viewer_view_pager")
        val found = match(spec, node(text = "for you"), node(viewId = "$IG:id/clips_viewer_view_pager"))
        assertNotNull(found)
        assertEquals("$IG:id/clips_viewer_view_pager", found!!.viewId)
    }

    @Test
    fun `AND combination requires every criterion, on any node`() {
        val spec = MatchSpec(
            viewId = "$IG:id/clips_viewer_view_pager",
            textRegex = "(?i)original audio",
        )
        // View id lives on the pager, the caption on a child — still one match.
        val pager = node(
            viewId = "$IG:id/clips_viewer_view_pager",
            children = listOf(node(text = "Home · Original audio")),
        )
        assertEquals("$IG:id/clips_viewer_view_pager", match(spec, pager)!!.viewId)

        // View id present, text absent: no match.
        assertNull(match(spec, node(viewId = "$IG:id/clips_viewer_view_pager")))
        // Text present, view id absent: no match.
        assertNull(match(spec, node(text = "Home · Original audio")))
    }

    @Test
    fun `a spec without criteria fails open`() {
        assertNull(match(MatchSpec(), node(text = "anything")))
        assertEquals(false, RuleMatcher(MatchSpec()).hasCriteria)
    }

    @Test
    fun `invisible and zero-sized nodes never match`() {
        val spec = MatchSpec(textRegex = "Reels")
        assertNull(match(spec, node(text = "Reels", visible = false)))
        assertNull(match(spec, node(text = "Reels", bounds = Bounds.EMPTY)))
    }

    @Test
    fun `content description and class name are regex matches`() {
        assertNotNull(match(MatchSpec(contentDescriptionRegex = "(?i)reels?$"), node(description = "Reels")))
        assertNull(match(MatchSpec(contentDescriptionRegex = "^reels$"), node(description = "Reels tab")))

        val klass = "androidx.recyclerview.widget.RecyclerView"
        assertNotNull(match(MatchSpec(classNameRegex = ".*(RecyclerView|ViewPager).*"), node(className = klass)))
        assertNull(match(MatchSpec(classNameRegex = ".*PlayerView.*"), node(className = klass)))
    }

    @Test
    fun `selected is a tri-state predicate on the anchor node`() {
        val spec = MatchSpec(viewId = "$IG:id/clips_tab", selected = true)
        val selectedTab = node(viewId = "$IG:id/clips_tab", selected = true)
        val unselectedTab = node(viewId = "$IG:id/clips_tab", selected = false)

        assertEquals(selectedTab, match(spec, selectedTab))
        assertNull(match(spec, unselectedTab))
        // A selected sibling cannot satisfy the predicate for an unselected anchor.
        assertNull(match(spec, node(children = listOf(unselectedTab, node(selected = true)))))
        // selected = false is a real criterion too.
        assertNotNull(match(MatchSpec(viewId = "$IG:id/clips_tab", selected = false), unselectedTab))
    }

    @Test
    fun `selected is ignored when null`() {
        val spec = MatchSpec(viewId = "$IG:id/clips_tab")
        assertNotNull(match(spec, node(viewId = "$IG:id/clips_tab", selected = false)))
        assertNotNull(match(spec, node(viewId = "$IG:id/clips_tab", selected = true)))
        // selected alone is a valid criterion.
        assertNotNull(match(MatchSpec(selected = true), node(selected = true)))
        assertNull(match(MatchSpec(selected = true), node(selected = false)))
    }

    @Test
    fun `view id lookup is bounded to the fallback budget of 200 nodes`() {
        val spec = MatchSpec(viewId = "$YT:id/reel_recycler")

        fun treeWithTargetAt(position: Int): NodeSnapshot {
            val children = (0 until position).map { node(className = "android.view.View") }.toMutableList()
            children[position - 1] = node(viewId = "$YT:id/reel_recycler")
            return node(children = children)
        }

        // Index 0 is the root, so position 100 sits well inside the budget.
        assertNotNull(match(spec, treeWithTargetAt(100)))
        // Position 300 is beyond the 200-node deep fallback: the rule fails open.
        assertNull(match(spec, treeWithTargetAt(300)))
    }

    @Test
    fun `flatten is pre-order and honours the scan cap`() {
        val root = node(children = listOf(node(text = "a", children = listOf(node(text = "b"))), node(text = "c")))
        val flat = NodeTree.flatten(root)
        assertEquals(listOf("a", "b", "c"), flat.mapNotNull { it.text })

        val deep = node(children = (1..500).map { node(text = "n$it") })
        assertEquals(NodeTree.MAX_SCAN_NODES, NodeTree.flatten(deep).size)
    }
}
