package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.core.loadRuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2a + rule #271: `tiktok_playback` is proved by the player container id (`player_view`) AND a
 * video surface class, both locale-independent. The FYP chrome descriptions are auxiliary hints
 * now: a pt-PT UI must still match. The rule must never depend on `video_seek_bar` — a player
 * control the host hides (`isVisibleToUser=false`) during playback while uiautomator still lists it.
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
    fun `a photo post (no video surface) does not match`() {
        // 235543-tt_fyp_next2: same chrome, player_view present, no TextureView, no seek bar.
        val photo = node(
            bounds = Bounds(0, 0, 1080, 2424),
            children = listOf(
                node(className = "android.widget.LinearLayout", description = "For You", bounds = Bounds(698, 137, 899, 289)),
                node(className = "android.widget.Button", description = "Like video. 43 likes", bounds = Bounds(912, 1375, 1080, 1533)),
                node(
                    viewId = "$TT:id/player_view",
                    className = "android.widget.FrameLayout",
                    bounds = Bounds(0, 137, 1080, 2169),
                ),
                node(className = "android.widget.TextView", text = "Photo", bounds = Bounds(0, 1800, 400, 1900)),
            ),
        )
        assertNull(match(photo))
        assertTrue("the video surface class is what separates a video post", rule.match.classNameRegex!!.contains("TextureView"))
    }
}
