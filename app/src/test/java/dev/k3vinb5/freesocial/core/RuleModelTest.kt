package dev.k3vinb5.freesocial.core

import org.junit.Assert.assertEquals
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
    fun `click view id needs a target`() {
        expectError(set(rule(action = "\"CLICK_VIEW_ID\"")), "needs viewIdTarget")
    }

    @Test
    fun `invalid regex is rejected`() {
        expectError(set(rule(match = """{"textRegex":"(unclosed"}""")), "invalid textRegex")
    }

    @Test
    fun `shipped asset parses with 8 unverified placeholder rules`() {
        val asset = java.io.File("src/main/assets/rules.json").readText()
        val set = loadRuleSet(asset)
        assertEquals(8, set.rules.size)
        for (rule in set.rules) {
            assertEquals("UNVERIFIED placeholder — replace from device dumps", rule.notes)
            assertNull(rule.verifiedAgainstAppVersion)
            assertTrue(rule.layer == 1 || rule.layer == 2)
        }
        assertEquals(
            listOf("com.instagram.android", "com.google.android.youtube", "com.zhiliaoapp.musically"),
            set.rules.map { it.appPackage }.distinct(),
        )
        assertTrue(set.rules.any { it.surface == "dm_carve_out" && it.action == Action.SKIP })
    }
}
