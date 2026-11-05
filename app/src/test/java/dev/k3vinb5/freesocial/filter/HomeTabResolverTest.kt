package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTabResolverTest {

    private fun igTabBar(reelsSelected: Boolean) = node(
        children = listOf(
            node(viewId = "$IG:id/feed_tab", description = "Home", clickable = true),
            node(viewId = "$IG:id/clips_tab", description = "Reels", selected = reelsSelected),
            node(viewId = "$IG:id/profile_tab", description = "Profile", clickable = true),
        ),
    )

    @Test
    fun `known id wins and resolves the Home tab, never the matched Reels tab`() {
        val found = HomeTabResolver.homeTab(igTabBar(reelsSelected = true), IG)
        assertEquals("$IG:id/feed_tab", found?.viewId)
    }

    @Test
    fun `content description is the fallback when the app exposes no resource ids`() {
        val yt = node(
            children = listOf(
                node(className = "android.widget.Button", description = "Shorts"),
                node(className = "android.widget.Button", description = "Home", clickable = true),
            ),
        )
        val found = HomeTabResolver.homeTab(yt, YT)
        assertEquals("Home", found?.contentDescription)
    }

    @Test
    fun `a Home description on a non-clickable node is not a tab`() {
        val tree = node(children = listOf(node(description = "Home", clickable = false)))
        assertNull(HomeTabResolver.homeTab(tree, YT))
    }

    @Test
    fun `no identifiable Home tab means the caller falls back to global Home`() {
        // TikTok exposes obfuscated ids and empty tab descriptions.
        val tt = node(
            children = listOf(
                node(viewId = "$TT:id/ey3", description = ""),
                node(viewId = "$TT:id/iqh", description = ""),
            ),
        )
        assertNull(HomeTabResolver.homeTab(tt, TT))
        // An IG screen without the nav bar (e.g. clips viewer) resolves nothing.
        assertNull(HomeTabResolver.homeTab(node(viewId = "$IG:id/clips_viewer_view_pager"), IG))
        assertNull(HomeTabResolver.homeTab(igTabBar(reelsSelected = true), null))
    }
}
