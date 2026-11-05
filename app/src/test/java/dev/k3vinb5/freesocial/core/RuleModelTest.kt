package dev.k3vinb5.freesocial.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuleModelTest {

    private fun rule(
        id: String = "r1",
        appPackage: String = "com.instagram.android",
        surface: String = "clips_viewer",
        layer: Int = 2,
        match: String = """{"viewId":"com.instagram.android:id/clips_viewer_view_pager"}""",
        action: String = "\"BACK\"",
        extra: String = "",
    ) = """
        {
          "id": "$id",
          "appPackage": "$appPackage",
          "surface": "$surface",
          "layer": $layer,
          "match": $match,
          "action": $action$extra
        }
    """.trimIndent()

    private fun set(vararg rules: String) =
        """{"schemaVersion":1,"rules":[${rules.joinToString(",")}]}"""

    private fun expectError(json: String, messagePart: String) {
        try {
            loadRuleSet(json)
            fail("expected RuleSetException containing '$messagePart'")
        } catch (e: RuleSetException) {
            assertTrue("unexpected message: ${e.message}", e.message!!.contains(messagePart))
        }
    }

    @Test
    fun `parses a rule with defaults for optional fields`() {
        val set = loadRuleSet(set(rule()))
        assertEquals(1, set.schemaVersion)
        assertEquals(1, set.rules.size)
        val parsed = set.rules.single()
        assertEquals("r1", parsed.id)
        assertEquals("com.instagram.android", parsed.appPackage)
        assertEquals("clips_viewer", parsed.surface)
        assertEquals(2, parsed.layer)
        assertEquals(Action.BACK, parsed.action)
        assertEquals("com.instagram.android:id/clips_viewer_view_pager", parsed.match.viewId)
        assertNull(parsed.match.textRegex)
        assertNull(parsed.match.selected)
        assertNull(parsed.notes)
        assertNull(parsed.verifiedAgainstAppVersion)
    }

    @Test
    fun `all action variants survive the round trip`() {
        for (action in Action.entries) {
            val extra = if (action == Action.CLICK_VIEW_ID) ""","viewIdTarget":"com.instagram.android:id/tab_home"""" else ""
            val parsed = loadRuleSet(set(rule(action = "\"${action.name}\"", extra = extra))).rules.single()
            assertEquals(action, parsed.action)
        }
    }

    @Test
    fun `unknown action is rejected`() {
        expectError(set(rule(action = "\"SWIPE\"")), "malformed rule set")
    }

    @Test
    fun `layer must be 1 or 2`() {
        expectError(set(rule(layer = 3)), "layer must be 1 or 2")
        expectError(set(rule(layer = 0)), "layer must be 1 or 2")
        assertEquals(1, loadRuleSet(set(rule(layer = 1))).rules.single().layer)
    }

    @Test
    fun `empty match is rejected`() {
        expectError(set(rule(match = "{}")), "at least one criterion")
        expectError(set(rule(match = """{"viewId":"  "}""")), "blank viewId")
    }

    @Test
    fun `duplicate app package and surface is rejected`() {
        expectError(set(rule(id = "a"), rule(id = "b")), "duplicate rule")
        // Same surface in a different package is fine.
        val ok = loadRuleSet(set(rule(id = "a"), rule(id = "b", appPackage = "com.google.android.youtube")))
        assertEquals(2, ok.rules.size)
    }

    @Test
    fun `selected is an optional criterion on its own`() {
        val parsed = loadRuleSet(
            set(rule(match = """{"viewId":"com.instagram.android:id/clips_tab","selected":true}""")),
        ).rules.single()
        assertEquals(true, parsed.match.selected)
        assertEquals(false, parsed.match.isEmpty)

        val selectedOnly = loadRuleSet(set(rule(match = """{"selected":false}"""))).rules.single()
        assertEquals(false, selectedOnly.match.selected)
    }

    @Test
    fun `click view id needs a target`() {
        expectError(set(rule(action = "\"CLICK_VIEW_ID\"")), "needs viewIdTarget")
    }

    @Test
    fun `invalid regex is rejected`() {
        expectError(set(rule(match = """{"textRegex":"(unclosed"}""")), "invalid textRegex")
    }

    @Test
    fun `shipped asset covers the eight filter surfaces`() {
        val asset = java.io.File("src/main/assets/rules.json").readText()
        val set = loadRuleSet(asset)
        assertEquals(8, set.rules.size)
        assertEquals(
            setOf(
                "com.instagram.android" to "feed_reel_cell",
                "com.instagram.android" to "reels_tab",
                "com.instagram.android" to "clips_viewer",
                "com.instagram.android" to "dm_carve_out",
                "com.google.android.youtube" to "shorts_shelf",
                "com.google.android.youtube" to "shorts_player",
                "com.zhiliaoapp.musically" to "playback",
                "com.zhiliaoapp.musically" to "dm_carve_out",
            ),
            set.rules.map { it.appPackage to it.surface }.toSet(),
        )
        assertEquals(listOf(1, 2), set.rules.map { it.layer }.distinct().sorted())
        assertEquals(2, set.rules.count { it.action == Action.SKIP })

        // No in-app surface may leave the app; in-place swipes replace the old Home redirect.
        assertEquals(0, set.rules.count { it.action == Action.REDIRECT_HOME })
        assertEquals(
            setOf("feed_reel_cell", "shorts_shelf"),
            set.rules.filter { it.action == Action.SCROLL_PAST }.map { it.surface }.toSet(),
        )
        assertEquals(2, set.rules.count { it.action == Action.SCROLL_PAST })
        // The Reels tab only matches while it is actually selected (Reels screen), not on every tab bar.
        assertEquals(true, set.rules.single { it.id == "ig_reels_tab" }.match.selected)
    }

    @Test
    fun `shipped rules are version-stamped or name their missing evidence`() {
        val asset = java.io.File("src/main/assets/rules.json").readText()
        val set = loadRuleSet(asset)
        for (rule in set.rules) {
            val notes = rule.notes ?: ""
            assertTrue("rule '${rule.id}' has no notes", notes.length > 40)
            assertFalse("rule '${rule.id}' still says placeholder", notes.contains("placeholder", ignoreCase = true))
            val version = rule.verifiedAgainstAppVersion
            if (version == null) {
                assertTrue(
                    "unverified rule '${rule.id}' must name its missing evidence",
                    notes.contains("UNVERIFIED"),
                )
            } else {
                assertTrue(
                    "rule '${rule.id}' has an unexpected version '$version'",
                    version.matches(Regex("(Instagram|YouTube|TikTok) \\d[\\w.]*")),
                )
            }
        }
    }
}
