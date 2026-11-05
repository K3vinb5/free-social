package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.core.loadRuleSet
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2a: `tiktok_playback` must match on always-visible FYP chrome plus a video surface, and must not
 * depend on `video_seek_bar` — a player control the host hides (`isVisibleToUser=false`) during
 * playback while uiautomator still lists it.
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
        assertNull(rule.match.viewId)
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
