package dev.k3vinb5.freesocial.ui.screens

import dev.k3vinb5.freesocial.core.Action
import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.filter.RuleCatalog
import dev.k3vinb5.freesocial.filter.RuleOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heal loop's failure modes, pinned on the decisions the Rule Inspector makes (found on device
 * 2026-11-07, `reverse/runtime-reno/heal/REPORT.md`, bugs A/B and the export-overwrites-failure
 * defect).
 *
 * The screen is Compose, so the contract under test is the pure seam it delegates to: which
 * [ExportEffect] a catalog result produces, and which [RuleCatalog.Adoption.Decision] a parsed
 * document plus a write outcome produces. Behaviour, not source text: a screen that refreshed the
 * catalog or opened the picker on a rejected document fails here.
 */
class RuleInspectorHealTest {

    private val rule = Rule(
        id = "ig_reels_tab",
        appPackage = "com.instagram.android",
        surface = "reels_tab",
        layer = 1,
        match = MatchSpec(viewId = "com.instagram.android:id/clips_tab"),
        action = Action.TAP_HOME_TAB,
    )

    private fun parsed(text: String = VALID_DOCUMENT): RuleOverride.Parse = RuleOverride.parse(text)

    /** Locale policy (rule #271): a localized string alone can never be the only anchor. */
    private val invalidDocument = """
        {
          "schemaVersion": 1,
          "rules": [
            {
              "id": "ig_reels_tab",
              "appPackage": "com.instagram.android",
              "surface": "reels_tab",
              "layer": 1,
              "match": { "textRegex": "(?i)^reels${'$'}" },
              "action": "TAP_HOME_TAB"
            }
          ]
        }
    """.trimIndent()

    // --- Export: adoption, picker and banner ------------------------------------------------

    @Test
    fun `a published document is adopted, refreshes rules-in-use and opens the picker`() {
        val effect = exportEffect(RuleCatalog.ApplyResult.Ok(listOf(rule)))

        assertTrue("the override becomes the active rule set", effect.adopted)
        assertTrue("the user can save a copy elsewhere", effect.openPicker)
        assertEquals(ExportMessage.EXPORTED, effect.message)
        assertNull(
            "a success carries no failure reason",
            RuleCatalog.ApplyResult.Ok(listOf(rule)).errorOrNull(),
        )
    }

    @Test
    fun `an invalid document is not adopted, opens no picker and reports the validator error`() {
        val applied = RuleCatalog.ApplyResult.Invalid("rules[0].match: missing viewId, text or desc")
        val effect = exportEffect(applied)

        assertFalse("nothing was written, so nothing may be called the override", effect.adopted)
        assertFalse("the picker must not offer to save a document that is not active", effect.openPicker)
        assertEquals(ExportMessage.REJECTED, effect.message)
        assertEquals(
            "the banner shows the validator's own reason",
            "rules[0].match: missing viewId, text or desc",
            applied.errorOrNull(),
        )
    }

    @Test
    fun `a write failure is not adopted, opens no picker and reports the filesystem error`() {
        val applied = RuleCatalog.ApplyResult.Failed("Permission denied")
        val effect = exportEffect(applied)

        assertFalse("a failed write leaves the previous rules active", effect.adopted)
        assertFalse(effect.openPicker)
        assertEquals(
            "a failed adoption must never claim it exported anything",
            ExportMessage.REJECTED,
            effect.message,
        )
        assertEquals("Permission denied", applied.errorOrNull())
    }

    // --- Import: only a real adoption may claim the rules changed ---------------------------

    @Test
    fun `only an adopted import claims the rules changed`() {
        assertEquals(
            RuleApplyMessage.IMPORTED,
            RuleCatalog.ApplyResult.Ok(listOf(rule)).importMessage(),
        )
        assertEquals(
            RuleApplyMessage.REJECTED,
            RuleCatalog.ApplyResult.Invalid("bad").importMessage(),
        )
        assertEquals(
            RuleApplyMessage.REJECTED,
            RuleCatalog.ApplyResult.Failed("disk full").importMessage(),
        )
    }

    // --- RuleCatalog.Adoption: the decision that changes the live engine -------------------

    @Test
    fun `a valid document that was written is adopted with its rules`() {
        val decision = RuleCatalog.Adoption.decide(parsed(), writeError = null)

        assertTrue("expected an adoption, got $decision", decision is RuleCatalog.Adoption.Decision.Adopt)
        val adopted = (decision as RuleCatalog.Adoption.Decision.Adopt).rules
        assertEquals(listOf("ig_reels_tab"), adopted.map { it.id })
    }

    @Test
    fun `a valid document whose write failed is rejected and nothing is adopted`() {
        val decision = RuleCatalog.Adoption.decide(parsed(), writeError = "No space left on device")

        assertEquals(
            RuleCatalog.Adoption.Decision.Reject("No space left on device"),
            decision,
        )
    }

    @Test
    fun `an invalid document is rejected with the validator error`() {
        val decision = RuleCatalog.Adoption.decide(parsed(invalidDocument), writeError = null)

        assertTrue(
            "a document the loader rejects must never reach the engine: $decision",
            decision is RuleCatalog.Adoption.Decision.Reject,
        )
        assertTrue(
            "the banner shows the validator's own reason: $decision",
            (decision as RuleCatalog.Adoption.Decision.Reject).error.contains("locale-proof"),
        )
    }

    private companion object {
        /** One rule, in the shape `Export rules` writes (see `rules.json`). */
        val VALID_DOCUMENT = """
            {
              "schemaVersion": 1,
              "rules": [
                {
                  "id": "ig_reels_tab",
                  "appPackage": "com.instagram.android",
                  "surface": "reels_tab",
                  "layer": 1,
                  "match": { "viewId": "com.instagram.android:id/clips_tab" },
                  "action": "TAP_HOME_TAB"
                }
              ]
            }
        """.trimIndent()
    }
}
