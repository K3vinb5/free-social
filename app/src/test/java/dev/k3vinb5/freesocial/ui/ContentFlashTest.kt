package dev.k3vinb5.freesocial.ui

import dev.k3vinb5.freesocial.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The escape flash contract (2026-11-05): reason mapping, the manifest declaration, and the launch
 * flags. The manifest is read as XML (the unit-test cousin of `aapt2 xmltree`) and the launch code
 * as text, so a `CLEAR_TASK` creeping back into the flash path fails here.
 */
class ContentFlashTest {

    private val manifest = File("src/main/AndroidManifest.xml")
    private val flashSource = File("src/main/java/dev/k3vinb5/freesocial/ui/ContentFlashActivity.kt")
    private val serviceSource = File("src/main/java/dev/k3vinb5/freesocial/filter/FilterService.kt")

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

    // --- Launch flags ---------------------------------------------------------------------

    /** Source without comments: the comments are allowed to name the flag they forbid. */
    private fun codeOf(file: File): String = file.readText()
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    @Test
    fun `flash launches with NEW_TASK only`() {
        val source = codeOf(flashSource)
        assertTrue("flash intent must use NEW_TASK", source.contains("FLAG_ACTIVITY_NEW_TASK"))
        assertFalse("flash intent must never clear the user's task", source.contains("CLEAR_TASK"))
    }

    @Test
    fun `single call site in FilterService never clears the task`() {
        val source = codeOf(serviceSource)
        assertTrue("FilterService must launch the flash", source.contains("ContentFlash.intent("))
        assertEquals(
            "exactly one flash launch site",
            1,
            Regex("ContentFlash\\.intent\\(").findAll(source).count(),
        )
        assertFalse(source.contains("CLEAR_TASK"))
    }
}
