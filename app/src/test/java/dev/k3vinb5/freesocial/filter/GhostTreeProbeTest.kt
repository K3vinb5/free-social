package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D8: the ghost-tree probe line must be greppable, carry the root shape even when the root is
 * unreadable, and probe the YouTube ids only for YouTube. These are the numbers that decide
 * H-OURS (`idProbe>=1` on a 3-child root) vs H-VENDOR (all `idProbe=0`).
 */
class GhostTreeProbeTest {

    @Test
    fun `youtube gets the three candidate ids, every other package gets none`() {
        assertEquals(
            listOf(
                "com.google.android.youtube:id/menu_item_0",
                "com.google.android.youtube:id/app_bar_wrapper",
                "com.google.android.youtube:id/results",
            ),
            GhostTreeProbe.idsFor(GhostTreeProbe.YOUTUBE_PACKAGE),
        )
        assertTrue(GhostTreeProbe.idsFor("com.instagram.android").isEmpty())
        assertTrue(GhostTreeProbe.idsFor("dev.k3vinb5.freesocial").isEmpty())
        assertTrue(GhostTreeProbe.idsFor(null).isEmpty())
    }

    @Test
    fun `id probe text pairs short ids with counts and never drops a candidate`() {
        val ids = GhostTreeProbe.YOUTUBE_VIEW_IDS
        assertEquals(
            "menu_item_0=0 app_bar_wrapper=1 results=0",
            GhostTreeProbe.idProbeText(ids, listOf(0, 1, 0)),
        )
        // Short/absent counts read as 0 instead of throwing.
        assertEquals(
            "menu_item_0=4 app_bar_wrapper=0 results=0",
            GhostTreeProbe.idProbeText(ids, listOf(4)),
        )
        assertEquals("n/a", GhostTreeProbe.idProbeText(emptyList(), emptyList()))
    }

    @Test
    fun `ghost root line carries child counts, root id and probe counts`() {
        val line = GhostTreeProbe.format(
            "com.google.android.youtube",
            GhostTreeProbe.RootShape(
                packageName = "com.google.android.youtube",
                viewId = "android:id/content",
                childCount = 3,
                grandchildCount = 0,
            ),
            "menu_item_0=0 app_bar_wrapper=0 results=0",
        )

        assertTrue(line.startsWith("probe com.google.android.youtube "))
        assertEquals(
            "probe com.google.android.youtube rootPkg=com.google.android.youtube " +
                "rootChildCount=3 rootViewId=android:id/content child0Grandchildren=0 " +
                "idProbe=menu_item_0=0 app_bar_wrapper=0 results=0 " +
                "eventSourceChildCount=none refreshChildCount=none",
            line,
        )
    }

    @Test
    fun `probe carries the event-source and post-refresh child counts`() {
        // Rich event.source + pruned root: the capture should walk the event subtree (future fix).
        val richEvent = GhostTreeProbe.format(
            "com.google.android.youtube",
            GhostTreeProbe.RootShape(
                packageName = "com.google.android.youtube",
                childCount = 2,
                eventSourceChildCount = 12,
                refreshChildCount = 2,
            ),
            "n/a",
        )
        assertTrue(richEvent, richEvent.contains("rootChildCount=2"))
        assertTrue(richEvent, richEvent.contains("eventSourceChildCount=12"))
        assertTrue(richEvent, richEvent.contains("refreshChildCount=2"))

        // Rich post-refresh root: the window snapshot was stale, refresh() at capture fixes it.
        val stale = GhostTreeProbe.format(
            "com.google.android.youtube",
            GhostTreeProbe.RootShape(childCount = 2, refreshChildCount = 9),
            "n/a",
        )
        assertTrue(stale, stale.contains("eventSourceChildCount=none"))
        assertTrue(stale, stale.contains("refreshChildCount=9"))

        // Both pruned: vendor link pruning — the lookup grafts are the permanent mitigation.
        val pruned = GhostTreeProbe.format(
            "com.google.android.youtube",
            GhostTreeProbe.RootShape(childCount = 2, eventSourceChildCount = 2, refreshChildCount = 2),
            "menu_item_0=1",
        )
        assertTrue(pruned, pruned.contains("eventSourceChildCount=2"))
        assertTrue(pruned, pruned.contains("refreshChildCount=2"))
    }

    @Test
    fun `unreadable root still produces a parsable line`() {
        assertEquals(
            "probe com.instagram.android rootPkg=none rootChildCount=-1 " +
                "rootViewId=none child0Grandchildren=none idProbe=n/a " +
                "eventSourceChildCount=none refreshChildCount=none",
            GhostTreeProbe.format("com.instagram.android", GhostTreeProbe.RootShape(), "n/a"),
        )
        // A root with no child(0) is distinct from a child(0) with zero grandchildren.
        assertEquals(
            "probe dev.k3vinb5.freesocial rootPkg=dev.k3vinb5.freesocial rootChildCount=1 " +
                "rootViewId=android:id/content child0Grandchildren=none idProbe=n/a " +
                "eventSourceChildCount=none refreshChildCount=none",
            GhostTreeProbe.format(
                "dev.k3vinb5.freesocial",
                GhostTreeProbe.RootShape(
                    packageName = "dev.k3vinb5.freesocial",
                    viewId = "android:id/content",
                    childCount = 1,
                    grandchildCount = null,
                ),
                "n/a",
            ),
        )
    }

    @Test
    fun `scan suffix carries the same root child count and probe counts`() {
        assertEquals(
            "rootChildCount=3, idProbe=menu_item_0=0 app_bar_wrapper=0 results=0",
            GhostTreeProbe.scanSuffix(
                GhostTreeProbe.RootShape(childCount = 3, grandchildCount = 0),
                "menu_item_0=0 app_bar_wrapper=0 results=0",
            ),
        )
    }
}
