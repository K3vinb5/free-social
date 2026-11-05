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
    fun `proof criteria are AND-combined, on any node`() {
        val spec = MatchSpec(
            viewId = "$IG:id/clips_viewer_view_pager",
            classNameRegex = "androidx\\.viewpager\\.widget\\.ViewPager",
        )
        // View id and class may live on the pager and a child — still one match.
        val pager = node(
            viewId = "$IG:id/clips_viewer_view_pager",
            children = listOf(node(className = "androidx.viewpager.widget.ViewPager")),
        )
        assertEquals("$IG:id/clips_viewer_view_pager", match(spec, pager)!!.viewId)

        // View id present, class absent: no match.
        assertNull(match(spec, node(viewId = "$IG:id/clips_viewer_view_pager")))
        // Class present, view id absent: no match.
        assertNull(match(spec, node(className = "androidx.viewpager.widget.ViewPager")))
    }

    @Test
    fun `a localized text hint never decides a match`() {
        val proof = node(viewId = "$IG:id/clips_viewer_view_pager")
        val spec = MatchSpec(
            viewId = "$IG:id/clips_viewer_view_pager",
            textRegex = "(?i)original audio",
            contentDescriptionRegex = "(?i)^reels$",
        )
        // Hint absent (a Portuguese UI, or the app reworded the caption): the proof still matches.
        assertEquals(proof, match(spec, proof))
        // Hint present: it confirms the same anchor, never replaces it.
        val withHint = match(
            spec,
            node(
                viewId = "$IG:id/clips_viewer_view_pager",
                children = listOf(node(text = "Áudio original", description = "Reels")),
            ),
        )
        assertEquals("$IG:id/clips_viewer_view_pager", withHint!!.viewId)
    }

    @Test
    fun `a text-only spec can never prove a match`() {
        val textOnly = MatchSpec(textRegex = "(?i)for you")
        assertNull(match(textOnly, node(text = "For You")))
        assertNull(match(MatchSpec(contentDescriptionRegex = "(?i)^reels$"), node(description = "Reels")))
        assertEquals(false, RuleMatcher(textOnly).hasProof)
        assertEquals(true, RuleMatcher(textOnly).hasCriteria)
    }

    @Test
    fun `a spec without criteria fails open`() {
        assertNull(match(MatchSpec(), node(text = "anything")))
        assertEquals(false, RuleMatcher(MatchSpec()).hasCriteria)
    }

    @Test
    fun `invisible and zero-sized nodes never match`() {
        val spec = MatchSpec(viewId = "$IG:id/video_container", textRegex = "Reels")
        assertNull(match(spec, node(viewId = "$IG:id/video_container", visible = false)))
        assertNull(match(spec, node(viewId = "$IG:id/video_container", bounds = Bounds.EMPTY)))
    }

    @Test
    fun `class names are regex-matched proof signals`() {
        val klass = "androidx.recyclerview.widget.RecyclerView"
        assertNotNull(match(MatchSpec(classNameRegex = ".*(RecyclerView|ViewPager).*"), node(className = klass)))
        assertNull(match(MatchSpec(classNameRegex = ".*PlayerView.*"), node(className = klass)))
        // A hint cannot stand in for a class match.
        assertNull(
            match(
                MatchSpec(classNameRegex = ".*PlayerView.*", contentDescriptionRegex = "(?i)reels?$"),
                node(description = "Reels"),
            ),
        )
    }

    @Test
    fun `a class-only proof prefers the candidate a hint confirms`() {
        // Reused container class: the localized description merely breaks the tie.
        val spec = MatchSpec(
            classNameRegex = "android\\.view\\.ViewGroup",
            contentDescriptionRegex = "(?i)^shorts$",
        )
        val tree = node(
            className = "android.widget.FrameLayout",
            children = listOf(
                node(className = "android.view.ViewGroup", description = "Recomendados"),
                node(className = "android.view.ViewGroup", description = "Clipes curtos"),
            ),
        )
        assertNotNull(match(spec, tree))
        val hinted = node(
            className = "android.widget.FrameLayout",
            children = listOf(
                node(className = "android.view.ViewGroup", description = "Recomendados"),
                node(className = "android.view.ViewGroup", description = "Shorts"),
            ),
        )
        assertEquals("Shorts", match(spec, hinted)!!.contentDescription)
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
