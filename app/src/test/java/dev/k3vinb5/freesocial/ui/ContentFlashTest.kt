package dev.k3vinb5.freesocial.ui

import android.content.Intent
import dev.k3vinb5.freesocial.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The escape flash contract (2026-11-05): reason mapping, the manifest declaration (`aapt2 xmltree`
 * read as XML, not source text), the D12 timing seam, and the launch flags. Behaviour lives on pure
 * seams so none of it depends on Robolectric.
 */
class ContentFlashTest {

    private val manifest = File("src/main/AndroidManifest.xml")

    private val androidNs = "http://schemas.android.com/apk/res/android"

    // --- Reason mapping -------------------------------------------------------------------

    @Test
    fun `exhausted budget shows the budget copy`() {
        val reason = ContentFlash.reasonFor(budgetExhausted = true, scheduleLocked = false)
        assertEquals(ContentFlash.Reason.BUDGET, reason)
        val copy = ContentFlash.copy(reason)
        assertEquals(R.string.flash_title, copy.title)
        assertEquals(R.string.flash_reason_budget, copy.reason)
        assertEquals(R.string.flash_caption_budget, copy.caption)
    }

    @Test
    fun `locked schedule shows the sleep copy`() {
        val reason = ContentFlash.reasonFor(budgetExhausted = true, scheduleLocked = true)
        assertEquals(ContentFlash.Reason.SLEEP, reason)
        val copy = ContentFlash.copy(reason)
        assertEquals(R.string.flash_title, copy.title)
        assertEquals(R.string.flash_reason_sleep, copy.reason)
        assertEquals(R.string.flash_caption_sleep, copy.caption)
    }

    @Test
    fun `unknown or absent extra reason falls back to the budget default`() {
        assertEquals(ContentFlash.Reason.BUDGET, ContentFlash.reasonFrom(null))
        assertEquals(ContentFlash.Reason.BUDGET, ContentFlash.reasonFrom(""))
        assertEquals(ContentFlash.Reason.BUDGET, ContentFlash.reasonFrom("nope"))
        assertEquals(ContentFlash.Reason.BUDGET, ContentFlash.reasonFrom("BUDGET"))
        assertEquals(ContentFlash.Reason.SLEEP, ContentFlash.reasonFrom("SLEEP"))
    }

    @Test
    fun `flash lasts about two seconds`() {
        assertTrue(ContentFlashActivity.FLASH_DURATION_MS in 2_000L..2_500L)
    }

    // --- Manifest -------------------------------------------------------------------------

    private fun flashActivity(): Element {
        assertTrue("${manifest.path} is missing", manifest.isFile)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val activities = doc.getElementsByTagName("activity")
        val matches = (0 until activities.length)
            .map { activities.item(it) as Element }
            .filter { it.getAttributeNS(androidNs, "name") == ".ui.ContentFlashActivity" }
        assertEquals("exactly one ContentFlashActivity entry", 1, matches.size)
        return matches.single()
    }

    @Test
    fun `flash activity declaration is unexported singleInstance and not a task of its own`() {
        val activity = flashActivity()
        assertEquals("false", activity.getAttributeNS(androidNs, "exported"))
        assertEquals("singleInstance", activity.getAttributeNS(androidNs, "launchMode"))
        assertEquals("true", activity.getAttributeNS(androidNs, "excludeFromRecents"))
        assertTrue("taskAffinity must be declared", activity.hasAttributeNS(androidNs, "taskAffinity"))
        assertEquals("", activity.getAttributeNS(androidNs, "taskAffinity"))
    }

    // --- D12 timing contract (pure seam, no Robolectric) ----------------------------------

    @Test
    fun `onCreate arms only the hard stop - never the visible window`() {
        assertEquals(FlashTiming.HARD_STOP_MS, FlashTiming.onCreateDelayMs())
        assertNotEquals(FlashTiming.VISIBLE_MS, FlashTiming.onCreateDelayMs())
    }

    @Test
    fun `the visible window starts at first focus`() {
        assertEquals(FlashTiming.VISIBLE_MS, FlashTiming.onFirstFocusDelayMs())
        assertTrue("a visible flash must be noticeable", FlashTiming.VISIBLE_MS in 2_000L..2_500L)
    }

    @Test
    fun `a flash can never outlive its hard stop`() {
        assertTrue(
            "the cap must be longer than the promised window",
            FlashTiming.HARD_STOP_MS > FlashTiming.VISIBLE_MS,
        )
        assertTrue(
            "the cap must absorb at least the measured 1.537 s cold launch",
            FlashTiming.HARD_STOP_MS - FlashTiming.VISIBLE_MS >= 1_500L,
        )
    }

    @Test
    fun `a reused singleInstance instance re-arms the right delay`() {
        assertEquals(FlashTiming.VISIBLE_MS, FlashTiming.rearmDelayMs(focused = true))
        assertEquals(FlashTiming.HARD_STOP_MS, FlashTiming.rearmDelayMs(focused = false))
    }

    // --- Launch flags ---------------------------------------------------------------------

    @Test
    fun `flash launches with NEW_TASK only`() {
        val flags = ContentFlash.launchFlags()
        assertTrue("flash intent must use NEW_TASK", flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("flash intent must never clear the user's task", 0, flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
