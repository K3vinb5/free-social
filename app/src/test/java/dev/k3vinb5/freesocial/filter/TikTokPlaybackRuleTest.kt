package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.core.loadRuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D2a + rule #271 + the 2026-11-06 Reno4 audit: `tiktok_playback` is proved by the player container
 * id (`player_view`) alone — locale-independent and always present. A video-surface class is NOT
 * part of the key: on the Reno4 / Android 13 build TikTok 47.6.3 the player exposes no
 * TextureView/SurfaceView/VideoView node at all (anchor-audit/tt-a, tt-b, tt-c), so requiring one
 * made the rule unreachable while a video played. The FYP chrome descriptions are auxiliary hints:
 * a pt-PT UI must still match. The rule must never depend on `video_seek_bar` — a player control
 * the host hides (`isVisibleToUser=false`) during playback while uiautomator still lists it.
 */
class TikTokPlaybackRuleTest {

    private val rules: List<Rule> = loadRuleSet(
        java.io.File("src/main/assets/rules.json").readText(),
    ).rules

    private val rule: Rule = rules.single { it.id == "tiktok_playback" }

    private fun match(root: NodeSnapshot): NodeSnapshot? = RuleMatcher(rule.match).match(root)

    /**
     * 234958-tt_consent_check shape: "For You" tab, right-rail buttons, a TextureView video surface
     * and (optionally, and invisible while playing) the seek bar.
     */
    private fun fypTree(
        videoSurface: Boolean = true,
        chrome: Boolean = true,
        seekBar: Boolean = false,
        seekBarVisible: Boolean = false,
    ): NodeSnapshot {
        val children = mutableListOf<NodeSnapshot>()
        if (chrome) {
            children += node(className = "android.widget.LinearLayout", description = "For You", bounds = Bounds(698, 137, 899, 289))
            children += node(className = "android.widget.Button", description = "Like video. 561 likes", bounds = Bounds(912, 1375, 1080, 1533))
            children += node(className = "android.widget.Button", description = "Read or add comments. 2 comments", bounds = Bounds(912, 1533, 1080, 1691))
            children += node(className = "android.widget.Button", description = "Share video. 3 shares", bounds = Bounds(912, 1849, 1080, 2007))
        }
        if (videoSurface) {
            children += node(
                viewId = "$TT:id/player_view",
                className = "android.widget.FrameLayout",
                bounds = Bounds(0, 0, 1080, 2169),
                children = listOf(
                    node(className = "android.view.TextureView", bounds = Bounds(0, 124, 1080, 2044)),
                ),
            )
        }
        if (seekBar) {
            children += node(
                viewId = "$TT:id/video_seek_bar",
                className = "android.widget.LinearLayout",
                bounds = Bounds(0, 2130, 1080, 2204),
                visible = seekBarVisible,
            )
        }
        return node(bounds = Bounds(0, 0, 1080, 2424), children = children)
    }

    @Test
    fun `matches an always-visible FYP tree without any visible seek bar`() {
        assertNotNull(match(fypTree(seekBar = true, seekBarVisible = false)))
        assertNotNull("seek bar is optional, not required", match(fypTree(seekBar = false)))
    }

    @Test
    fun `an invisible seek bar alone can never be the match`() {
        assertNull(match(fypTree(videoSurface = false, chrome = false, seekBar = true, seekBarVisible = false)))
        // Even a visible seek bar is not the anchor any more.
        assertNull(match(fypTree(videoSurface = false, chrome = false, seekBar = true, seekBarVisible = true)))
        assertEquals("$TT:id/player_view", rule.match.viewId)
    }

    @Test
    fun `the localized FYP chrome is a hint, not a key`() {
        // A pt-PT device words "For You" / "Like video." differently (or the app rewords it):
        // the id + surface class must still prove the match.
        val withoutChrome = fypTree(chrome = false)
        assertNotNull("the id + video surface must match with every string changed", match(withoutChrome))
    }

    @Test
    fun `a live preview with a SurfaceView and no player container does not match`() {
        // 235326-tt_fyp_next: live preview renders a SurfaceView under a bare FrameLayout and has
        // no player_view id — the id is what separates a short-video post from LIVE.
        val live = node(
            bounds = Bounds(0, 0, 1080, 2424),
            children = listOf(
                node(
                    className = "android.widget.FrameLayout",
                    bounds = Bounds(0, 478, 1080, 2169),
                    children = listOf(
                        node(className = "android.view.SurfaceView", bounds = Bounds(0, 478, 1080, 2169)),
                    ),
                ),
                node(className = "android.widget.TextView", text = "LIVE now", bounds = Bounds(45, 1932, 186, 1990)),
            ),
        )
        assertNull(match(live))
    }

    @Test
    fun `a photo post in the FYP matches by id - recorded trade-off`() {
        // 2026-11-06 Reno4 audit, anchor-audit/tt-d-photo-post.json: the photo tree is a strict
        // subset of the video tree. It HAS player_view (visible, full playback bounds) and its only
        // missing nodes are the seek-bar chrome. With no video-surface node on this build there is
        // no locale-proof way to separate a photo carousel from a video post, so the id anchor
        // matches both: the surface being gated is the FYP player either way, and a DM-opened video
        // is still shielded by tiktok_dm_carve_out (SKIP). Live stays excluded (no player_view).
        val photo = node(
            bounds = Bounds(0, 0, 1080, 2424),
            children = listOf(
                node(className = "android.widget.LinearLayout", description = "Para Ti", bounds = Bounds(698, 137, 899, 289)),
                node(className = "android.widget.Button", description = "Gosto. 43 gostos", bounds = Bounds(912, 1375, 1080, 1533)),
                node(
                    viewId = "$TT:id/player_view",
                    className = "android.widget.FrameLayout",
                    bounds = Bounds(0, 110, 1080, 2121),
                ),
                node(className = "android.widget.TextView", text = "Foto", bounds = Bounds(0, 1800, 400, 1900)),
            ),
        )
        assertNotNull(match(photo))
        assertNull("no video-surface class may be required", rule.match.classNameRegex)
    }

    @Test
    fun `a playing video with no video surface node in the tree still matches`() {
        // The 2026-11-06 regression: tt-a-fresh-swipe / tt-b-midplay / tt-c-tap-focus (Reno4,
        // TikTok 47.6.3) carry player_view [0,110][1080,2121] and ZERO TextureView/SurfaceView/
        // VideoView nodes. Requiring a surface class logged matched=0 for a playing video.
        val renoVideo = node(
            bounds = Bounds(0, 0, 1080, 2400),
            children = listOf(
                node(className = "android.widget.LinearLayout", description = "Para Ti", bounds = Bounds(715, 110, 910, 284)),
                node(className = "android.widget.FrameLayout", description = "Início", bounds = Bounds(0, 2121, 216, 2268)),
                node(
                    viewId = "$TT:id/player_view",
                    className = "android.widget.FrameLayout",
                    bounds = Bounds(0, 110, 1080, 2121),
                ),
                node(
                    viewId = "$TT:id/video_seek_bar",
                    className = "android.widget.LinearLayout",
                    bounds = Bounds(0, 2076, 1080, 2160),
                ),
            ),
        )
        assertNotNull("player_view alone must prove playback on the audited build", match(renoVideo))
    }
}
