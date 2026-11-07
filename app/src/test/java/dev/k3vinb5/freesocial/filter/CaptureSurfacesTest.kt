package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.core.hasLocaleProofSignal
import dev.k3vinb5.freesocial.core.loadRuleSet
import dev.k3vinb5.freesocial.settings.AppTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D15 — a surface marker must be able to match a tree the shipped app actually produces.
 *
 * The node shapes below are lifted from the device dumps (Reno4, pt-PT; IG 454.0.0.54.77,
 * YT 21.43.523, TikTok 47.6.3). A marker that can never match turns guided capture into a 30 s
 * timeout (the IG Home `feed_tab` + `selected=true` defect) and a marker that matches the wrong
 * screen mislabels the dump (YT Home vs Shorts).
 */
class CaptureSurfacesTest {

    private val rules: List<Rule> =
        loadRuleSet(File("src/main/assets/rules.json").readText()).rules

    private fun markers(app: AppTarget, surface: String) =
        CaptureSurfaces.markers(app, surface, rules)

    private fun verify(app: AppTarget, surface: String, vararg nodes: NodeSnapshot) =
        CaptureSurfaces.verify(surface, markers(app, surface), nodes.toList())

    @Test
    fun `instagram home verifies from a photo-only feed row`() {
        // reverse/device-dumps/233005-ig_home.xml: photo post, no video_container, feed_tab
        // selected=false (the bottom-nav cell; the selected flag is on its child tab_icon).
        val feed = listOf(
            node(viewId = "$IG:id/tab_icon", selected = true),
            node(viewId = "$IG:id/feed_tab", selected = false),
            node(viewId = "$IG:id/row_feed_profile_header"),
            node(viewId = "$IG:id/row_feed_view_group_buttons"),
            node(viewId = "$IG:id/row_feed_photo_imageview"),
        )

        val verdict = verify(AppTarget.INSTAGRAM, "feed_reel_cell", *feed.toTypedArray())

        assertTrue("a home feed without a playing video must verify", verdict.verified)
        assertEquals("feed_reel_cell", verdict.surface)
        assertTrue(verdict.seen.isEmpty())
    }

    @Test
    fun `instagram home does not verify on the reels tab or a DM inbox`() {
        // 233528-ig_reels_tab.xml and 233758-ig_dm_inbox.xml: same bottom nav, no feed row.
        val reels = listOf(
            node(viewId = "$IG:id/clips_tab", selected = true, description = "Reels"),
            node(
                viewId = "$IG:id/clips_viewer_view_pager",
                className = "androidx.viewpager.widget.ViewPager",
            ),
            node(viewId = "$IG:id/feed_tab", selected = false),
        )
        val dm = listOf(
            node(viewId = "$IG:id/direct_tab", selected = true, description = "Message"),
            node(viewId = "$IG:id/feed_tab", selected = false),
            node(viewId = "$IG:id/inbox_refreshable_thread_list_recyclerview"),
        )

        assertFalse(verify(AppTarget.INSTAGRAM, "feed_reel_cell", *reels.toTypedArray()).verified)
        assertFalse(verify(AppTarget.INSTAGRAM, "feed_reel_cell", *dm.toTypedArray()).verified)
    }

    @Test
    fun `youtube home verifies from the feed recycler and not from the shorts player`() {
        // 234610-yt_home.xml / 170110-yt_home.xml: id/results is the feed RecyclerView.
        val home = listOf(
            node(
                viewId = "$YT:id/results",
                className = "android.support.v7.widget.RecyclerView",
                scrollable = true,
            ),
            node(viewId = "$YT:id/browse_fragment_layout_coordinator_layout"),
        )
        // 234810-yt_shorts_player.xml: Shorts renders over HomeActivity, so the browse container
        // is still there but the feed recycler and its rows are gone.
        val shorts = listOf(
            node(viewId = "$YT:id/browse_fragment_layout_coordinator_layout"),
            node(
                viewId = "$YT:id/reel_recycler",
                className = "android.support.v7.widget.RecyclerView",
            ),
        )

        assertTrue(verify(AppTarget.YOUTUBE, "home", *home.toTypedArray()).verified)
        assertFalse(verify(AppTarget.YOUTUBE, "home", *shorts.toTypedArray()).verified)
    }

    @Test
    fun `the rule-backed surfaces still verify on their real dumps`() {
        // IG clips viewer (233528-ig_reels_tab.xml): clips_viewer_view_pager + ViewPager.
        assertTrue(
            verify(
                AppTarget.INSTAGRAM,
                "clips_viewer",
                node(
                    viewId = "$IG:id/clips_viewer_view_pager",
                    className = "androidx.viewpager.widget.ViewPager",
                ),
            ).verified,
        )
        // IG Reels bottom-nav tab (233528): clips_tab, FrameLayout, selected=true.
        assertTrue(
            verify(
                AppTarget.INSTAGRAM,
                "reels_tab",
                node(viewId = "$IG:id/clips_tab", selected = true, description = "Reels"),
            ).verified,
        )
        // YT Shorts player (234810-yt_shorts_player.xml): reel_recycler + RecyclerView.
        assertTrue(
            verify(
                AppTarget.YOUTUBE,
                "shorts_player",
                node(
                    viewId = "$YT:id/reel_recycler",
                    className = "android.support.v7.widget.RecyclerView",
                ),
            ).verified,
        )
        // TikTok FYP (anchor-audit/tt-a-fresh-swipe.json): player_view is the only proof needed.
        assertTrue(
            verify(AppTarget.TIKTOK, "playback", node(viewId = "$TT:id/player_view")).verified,
        )
    }

    @Test
    fun `no shipped surface marker is a dead criterion`() {
        // Regression guard for the two defects this sweep found.
        val igHome = markers(AppTarget.INSTAGRAM, "feed_reel_cell")
        assertFalse(
            "id/feed_tab is the bottom-nav cell and is never selected",
            igHome.any { it.viewId?.endsWith(":id/feed_tab") == true },
        )
        val ytHome = markers(AppTarget.YOUTUBE, "home")
        assertFalse(
            "id/tabs_bar does not exist in any YouTube dump",
            ytHome.any { it.viewId?.endsWith(":id/tabs_bar") == true },
        )
        assertTrue(ytHome.any { it.viewId?.endsWith(":id/results") == true })

        // Every offered surface must have markers, and every marker needs a locale-proof
        // criterion — otherwise `RuleMatcher` returns null by design and capture can never verify.
        AppTarget.entries.forEach { app ->
            CaptureSurfaces.forApp(app, rules).forEach { surface ->
                assertTrue("${surface.id} has no markers", surface.markers.isNotEmpty())
                surface.markers.forEach { marker ->
                    assertTrue("${surface.id} marker has no proof signal: $marker", marker.hasLocaleProofSignal)
                }
            }
        }
    }
}
