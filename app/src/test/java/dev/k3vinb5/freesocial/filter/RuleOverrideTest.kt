package dev.k3vinb5.freesocial.filter

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `revert deletes the override so the next select returns the asset`() {
        val dir = Files.createTempDirectory("rule-override").toFile()
        try {
            val file = File(dir, RuleOverride.FILE_NAME)
            file.writeText(override)

            // While the file is present the override still wins — precedence is unchanged.
            val active = RuleOverride.select(asset, file.readText())
            assertEquals(RuleOverride.Source.OVERRIDE, active.source)
            assertEquals(listOf("user_rule"), active.rules.map { it.id })

            assertTrue(RuleOverride.revert(file))
            assertFalse(file.exists())

            val after = RuleOverride.select(asset, file.takeIf { it.isFile }?.readText())
            assertEquals(RuleOverride.Source.ASSETS, after.source)
            assertEquals(listOf("asset_rule"), after.rules.map { it.id })
            assertNull(after.error)

            // Reverting again is harmless.
            assertTrue(RuleOverride.revert(file))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the import timestamp is the override file time and absent without a file`() {
        val dir = Files.createTempDirectory("rule-override").toFile()
        try {
            val file = File(dir, RuleOverride.FILE_NAME)
            assertNull(RuleOverride.importedAt(file))

            file.writeText(override)
            val written = file.lastModified()
            assertEquals(written, RuleOverride.importedAt(file))

            file.delete()
            assertNull(RuleOverride.importedAt(file))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an override older than seven days is flagged as stale`() {
        val now = 1_800_000_000_000L
        val day = 24L * 60L * 60L * 1000L

        assertFalse(RuleOverride.isStale(null, now))
        assertFalse(RuleOverride.isStale(now - 7 * day, now))
        assertFalse(RuleOverride.isStale(now - 6 * day, now))
        assertTrue(RuleOverride.isStale(now - 8 * day, now))
    }
}
