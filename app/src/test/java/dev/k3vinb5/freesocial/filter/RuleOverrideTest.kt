package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Override semantics: a valid `rules-override.json` replaces the asset, a rejected one never takes
 * filtering down, and the active rules stay in place with the validator's error surfaced.
 */
class RuleOverrideTest {

    private val asset = """
        {"schemaVersion":1,"rules":[
          {"id":"asset_rule","appPackage":"com.google.android.youtube","surface":"shorts_player",
           "layer":2,"match":{"viewId":"com.google.android.youtube:id/reel_recycler"},"action":"BACK"}
        ]}
    """.trimIndent()

    private val override = """
        {"schemaVersion":1,"rules":[
          {"id":"user_rule","appPackage":"com.google.android.youtube","surface":"home",
           "layer":2,"match":{"viewId":"com.google.android.youtube:id/tabs_bar"},"action":"BACK"}
        ]}
    """.trimIndent()

    @Test
    fun `a valid override replaces the asset rules`() {
        val selection = RuleOverride.select(asset, override)

        assertEquals(RuleOverride.Source.OVERRIDE, selection.source)
        assertNull(selection.error)
        assertEquals(listOf("user_rule"), selection.rules.map { it.id })
    }

    @Test
    fun `an invalid override keeps the asset rules and reports the error`() {
        val broken = """{"schemaVersion":1,"rules":[{"id":"bad","appPackage":"x","surface":"y",
            "layer":9,"match":{"viewId":"x:id/y"},"action":"BACK"}]}"""

        val selection = RuleOverride.select(asset, broken)

        assertEquals(RuleOverride.Source.ASSETS, selection.source)
        assertEquals(listOf("asset_rule"), selection.rules.map { it.id })
        assertNotNull(selection.error)
        assertTrue(selection.error!!.contains("override rejected"))
        assertTrue(selection.error!!.contains("layer"))
    }

    @Test
    fun `a broken asset falls back to the currently active rules`() {
        val active = listOf(rule(id = "still_active"))

        val selection = RuleOverride.select(assetText = "{not json", overrideText = null, fallback = active)

        assertEquals(listOf("still_active"), selection.rules.map { it.id })
        assertNotNull(selection.error)
        assertTrue(selection.error!!.contains("asset rejected"))
    }

    @Test
    fun `a duplicate surface or retired action is rejected by the same loader validation`() {
        val duplicate = """
            {"schemaVersion":1,"rules":[
              {"id":"a","appPackage":"x","surface":"s","layer":1,"match":{"viewId":"x:id/a"},"action":"BACK"},
              {"id":"b","appPackage":"x","surface":"s","layer":1,"match":{"viewId":"x:id/b"},"action":"BACK"}
            ]}
        """.trimIndent()
        val retired = """
            {"schemaVersion":1,"rules":[
              {"id":"a","appPackage":"x","surface":"s","layer":1,"match":{"viewId":"x:id/a"},"action":"SCROLL_PAST"}
            ]}
        """.trimIndent()

        assertTrue(RuleOverride.parse(duplicate) is RuleOverride.Parse.Invalid)
        assertTrue(RuleOverride.parse(retired) is RuleOverride.Parse.Invalid)
    }

    @Test
    fun `an absent override selects the asset and an absent asset reports unavailability`() {
        val fromAsset = RuleOverride.select(asset, null)
        assertEquals(RuleOverride.Source.ASSETS, fromAsset.source)
        assertEquals(listOf("asset_rule"), fromAsset.rules.map { it.id })
        assertNull(fromAsset.error)

        val missing = RuleOverride.select(null, null)
        assertTrue(missing.rules.isEmpty())
        assertNotNull(missing.error)
    }
}
