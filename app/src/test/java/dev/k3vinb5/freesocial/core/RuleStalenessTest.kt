package dev.k3vinb5.freesocial.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleStalenessTest {

    private val igStamp = "Instagram 454.0.0.54.77"

    // --- one rule: installed vs verified -------------------------------------------------

    @Test
    fun newerInstalledAppIsStale() {
        assertEquals(RuleFreshness.STALE, RuleStaleness.compare(igStamp, "460.1.2.3.4"))
    }

    @Test
    fun equalVersionsAreFresh() {
        assertEquals(RuleFreshness.FRESH, RuleStaleness.compare(igStamp, "454.0.0.54.77"))
    }

    @Test
    fun olderInstalledAppIsFresh() {
        assertEquals(RuleFreshness.FRESH, RuleStaleness.compare(igStamp, "450.1"))
    }

    /** Rules verified on a version the user does not have yet are not drift. */
    @Test
    fun rulesNewerThanInstalledAppAreFresh() {
        assertEquals(RuleFreshness.FRESH, RuleStaleness.compare("Instagram 460.1.2.3.4", "454.0.0.54.77"))
    }

    @Test
    fun shorterVersionIsOlder() {
        assertEquals(RuleFreshness.FRESH, RuleStaleness.compare(igStamp, "454.0.0"))
    }

    @Test
    fun trailingZeroSegmentsCompareEqual() {
        assertEquals(RuleFreshness.FRESH, RuleStaleness.compare(igStamp, "454.0.0.54.77.0"))
    }

    // --- unknowns -----------------------------------------------------------------------

    @Test
    fun neverVerifiedRuleIsUnknown() {
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.compare(null, "454.0.0.54.77"))
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.compare("", "454.0.0.54.77"))
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.compare("   ", "454.0.0.54.77"))
    }

    @Test
    fun unparseableStampIsUnknown() {
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.compare("Instagram", "454.0.0.54.77"))
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.compare("Instagram beta", "454.0.0.54.77"))
    }

    @Test
    fun unparseableInstalledVersionIsUnknown() {
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.compare(igStamp, null))
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.compare(igStamp, ""))
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.compare(igStamp, "abc"))
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.compare(igStamp, "454..5"))
    }

    @Test
    fun appNotInstalledIsUnknown() {
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.app(listOf(igStamp), null))
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.app(listOf(igStamp), ""))
    }

    // --- suffix tolerance ---------------------------------------------------------------

    @Test
    fun trailingSuffixIsTolerated() {
        assertEquals(RuleFreshness.FRESH, RuleStaleness.compare("Instagram 454.0.0.54.beta", "454.0.0.54"))
        assertEquals(RuleFreshness.STALE, RuleStaleness.compare("Instagram 454.0.0.54.beta", "454.0.0.55"))
    }

    @Test
    fun qualifierInsideSegmentDecidesOnItsNumber() {
        assertEquals(RuleFreshness.FRESH, RuleStaleness.compare(igStamp, "454.0.0.54.77b"))
        assertEquals(RuleFreshness.STALE, RuleStaleness.compare(igStamp, "454.0.0.54.78-rc1"))
    }

    @Test
    fun qualifierBeforeANumberIsUnknown() {
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.compare(igStamp, "454.beta.5"))
    }

    // --- per-app rollup -----------------------------------------------------------------

    @Test
    fun anyStaleRuleMakesTheAppStale() {
        val verdict = RuleStaleness.app(
            listOf("Instagram 454.0.0.54.77", "Instagram 460.0.0.1"),
            "460.1.2.3.4",
        )
        assertEquals(RuleFreshness.STALE, verdict)
    }

    @Test
    fun oneFreshRuleKeepsTheAppFresh() {
        assertEquals(RuleFreshness.FRESH, RuleStaleness.app(listOf(igStamp, null), "454.0.0.54.77"))
    }

    @Test
    fun allUnknownOrNoRulesIsUnknown() {
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.app(listOf(null, "Instagram"), "454.0.0.54.77"))
        assertEquals(RuleFreshness.UNKNOWN, RuleStaleness.app(emptyList(), "454.0.0.54.77"))
    }

    // --- UI wiring (AppDetail banner) ----------------------------------------------------

    @Test
    fun bannerIsOnlyShownForStale() {
        assertTrue(RuleStaleness.showsBanner(RuleFreshness.STALE))
        assertFalse(RuleStaleness.showsBanner(RuleFreshness.FRESH))
        assertFalse(RuleStaleness.showsBanner(RuleFreshness.UNKNOWN))
    }

    @Test
    fun bannerCopyUsesMajorSegments() {
        assertEquals("454", RuleStaleness.majorVersion(igStamp))
        assertEquals("460", RuleStaleness.majorVersion("460.1.2.3.4"))
        assertNull(RuleStaleness.majorVersion(null))
        assertNull(RuleStaleness.majorVersion("Instagram"))
    }
}
