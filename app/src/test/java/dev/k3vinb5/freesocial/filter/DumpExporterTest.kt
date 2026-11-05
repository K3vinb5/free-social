package dev.k3vinb5.freesocial.filter

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4: the armed dump used to call `NodeTreeReader.captureAll` without the id/text lookup lists, so
 * the Rule Inspector wrote a 4-node ghost while the scan on the same window saw grafted content
 * (`grafted=2`). Both paths now feed the same lists; the dump header carries the graft count and
 * every recovered node is marked, so the inspector shows what the engine sees.
 */
class DumpExporterTest {

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

    @Test
    fun `a pruned tree plus lookups exports the grafted nodes and the pt-PT strings`() {
        val resultsId = "$YT:id/results"
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
        val results = FakeNode(
            viewId = resultsId,
            className = "android.support.v7.widget.RecyclerView",
            bounds = Bounds(0, 398, 1080, 2158),
            scrollable = true,
            kids = listOf(header, card),
        )
        val content = FakeNode(viewId = "android:id/content", bounds = Bounds(0, 0, 1080, 2300))
        val bar = FakeNode(viewId = "android:id/navigationBarBackground", bounds = Bounds(0, 2300, 1080, 2400))
        val ghost = FakeNode(viewId = "android:id/root", bounds = Bounds(0, 0, 1080, 2400), kids = listOf(bar, content))

        // The production pipeline: capture with the lookup ids/texts, then export the same tree.
        val ids = CaptureAnchors.idsFor(YT)
        val base = NodeTreeBuilder.build(listOf(ghost))
        val capture = NodeTreeBuilder.graft(base, ids.map { GraftNode(results, it, subtree = true) })
        assertEquals(3, capture.grafts)

        val doc = DumpExporter.document(
            appPackage = YT,
            screen = "YT · shorts player",
            tree = capture.root,
            now = ZonedDateTime.parse("2026-11-05T11:46:07+00:00"),
            windowStats = capture.windows,
            capped = capture.capped,
            grafted = capture.grafts,
        )
        val json = DumpExporter.encode(doc)

        assertEquals(3, doc.grafted)
        assertEquals("synthetic root + 3 ghost nodes + the grafted container subtree", 7, doc.nodeCount)
        assertTrue(json.contains("\"grafted\": 3"))
        assertTrue("graft markers per node", json.contains("\"grafted\": true"))
        assertTrue("the pt-PT shelf string the engine matched on", json.contains("– reproduzir Short"))
        assertTrue("the container id is in the dump", json.contains(resultsId))
        assertTrue("per-window stats stay in the header", json.contains("\"windows\""))
    }

    @Test
    fun `a verified capture records the surface id and an unverified one records what was seen`() {
        val tree = NodeTreeBuilder.build(
            listOf(
                FakeNode(
                    viewId = "com.google.android.youtube:id/browse_fragment_layout_coordinator_layout",
                    kids = listOf(FakeNode(contentDescription = "Início")),
                ),
            ),
        ).root
        val markers = listOf(
            dev.k3vinb5.freesocial.core.MatchSpec(
                viewId = "com.google.android.youtube:id/browse_fragment_layout_coordinator_layout",
            ),
        )

        val verified = CaptureSurfaces.verify("home", markers, NodeTree.flatten(tree))
        val ok = DumpExporter.document(
            appPackage = YT,
            screen = "Home feed",
            tree = tree,
            verdict = verified,
        )
        assertTrue(ok.surfaceVerified)
        assertEquals("home", ok.surface)
        assertEquals("home", ok.screen)
        assertTrue(ok.markersSeen.isEmpty())

        val reelsTree = NodeTreeBuilder.build(
            listOf(FakeNode(viewId = "$IG:id/clips_viewer_view_pager")),
        ).root
        val unknown = CaptureSurfaces.verify("home", markers, NodeTree.flatten(reelsTree))
        val bad = DumpExporter.document(
            appPackage = YT,
            screen = "Home feed",
            tree = reelsTree,
            verdict = unknown,
        )
        assertFalse(bad.surfaceVerified)
        assertEquals(null, bad.surface)
        assertEquals(CaptureSurfaces.UNKNOWN_SURFACE, bad.screen)
        assertTrue(bad.markersSeen.contains("viewId=$IG:id/clips_viewer_view_pager"))
        assertTrue(DumpExporter.encode(bad).contains("\"surfaceVerified\": false"))
    }
}
