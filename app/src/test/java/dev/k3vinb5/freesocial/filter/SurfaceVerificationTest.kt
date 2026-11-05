package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Action
import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.settings.AppTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Surface-verified captures: a dump is labelled with a surface id only when that surface's own
 * markers match; otherwise it is `unknown-surface` and carries what was actually on screen.
 */
class SurfaceVerificationTest {

    private val ytHome = MatchSpec(viewId = "$YT:id/browse_fragment_layout_coordinator_layout")

    @Test
    fun `markers matched labels the surface`() {
        val nodes = listOf(
            node(viewId = "android:id/content"),
            node(viewId = "$YT:id/browse_fragment_layout_coordinator_layout"),
        )

        val verdict = CaptureSurfaces.verify("home", listOf(ytHome), nodes)

        assertTrue(verdict.verified)
        assertEquals("home", verdict.surface)
        assertEquals("home", verdict.label)
        assertTrue(verdict.seen.isEmpty())
    }

    @Test
    fun `markers not matched labels unknown-surface and lists what was seen`() {
        // Instagram home is on screen; asking for the YouTube home surface must not label it.
        val nodes = listOf(
            node(viewId = "$IG:id/feed_tab", selected = true),
            node(description = "Reels"),
            node(text = "For you"),
        )

        val verdict = CaptureSurfaces.verify("home", listOf(ytHome), nodes)

        assertFalse(verdict.verified)
        assertEquals(CaptureSurfaces.UNKNOWN_SURFACE, verdict.label)
        assertTrue(verdict.seen.contains("viewId=$IG:id/feed_tab"))
        assertTrue(verdict.seen.any { it.startsWith("desc=\"Reels\"") })
        assertTrue("evidence is bounded", verdict.seen.size <= 8)
    }

    @Test
    fun `surface registry merges the rule catalog markers with built-in detectors`() {
        val rule = Rule(
            id = "yt_shorts_player",
            appPackage = YT,
            surface = "shorts_player",
            layer = 2,
            match = MatchSpec(viewId = "$YT:id/reel_recycler"),
            action = Action.BACK,
        )

        val surfaces = CaptureSurfaces.forApp(AppTarget.YOUTUBE, listOf(rule))
        val shorts = surfaces.first { it.id == "shorts_player" }
        val home = surfaces.first { it.id == "home" }

        assertEquals("Shorts player", shorts.displayName)
        assertTrue(shorts.markers.any { it.viewId == "$YT:id/reel_recycler" })
        assertTrue(home.markers.any { it.viewId == "$YT:id/browse_fragment_layout_coordinator_layout" })
        // No rule models YouTube Home: detection still works from the built-in container id.
        assertEquals("Home feed", CaptureSurfaces.displayName("home"))
    }

    @Test
    fun `observed evidence is de-duplicated and keeps visible nodes only`() {
        val nodes = listOf(
            node(viewId = "$IG:id/feed_tab"),
            node(viewId = "$IG:id/feed_tab"),
            node(viewId = "$IG:id/invisible", visible = false),
            node(description = "Reels"),
        )

        val seen = CaptureSurfaces.observed(nodes)

        assertEquals(listOf("viewId=$IG:id/feed_tab", "desc=\"Reels\""), seen)
    }
}
