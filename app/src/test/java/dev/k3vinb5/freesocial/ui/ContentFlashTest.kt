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

    // --- D12 timing contract --------------------------------------------------------------

    @Test
    fun `a flash can never outlive its hard stop`() {
        assertTrue(
            "the cap must be longer than the promised window",
            ContentFlashActivity.HARD_STOP_MS > ContentFlashActivity.FLASH_DURATION_MS,
        )
        assertTrue(
            "the cap must absorb at least the measured 1.537 s cold launch",
            ContentFlashActivity.HARD_STOP_MS - ContentFlashActivity.FLASH_DURATION_MS >= 1_500L,
        )
        val create = bodyOf(codeOf(flashSource), "override fun onCreate")
        assertTrue(
            "onCreate arms the absolute cap",
            create.contains("postDelayed(hardStop, HARD_STOP_MS)"),
        )
    }

    @Test
    fun `the visible window starts at first focus instead of onCreate`() {
        val source = codeOf(flashSource)
        val create = bodyOf(source, "override fun onCreate")
        assertFalse(
            "arming the auto-dismiss in onCreate is the 0.65 s-visible defect",
            create.contains("postDelayed(autoDismiss"),
        )
        val focus = bodyOf(source, "override fun onWindowFocusChanged")
        assertTrue("the first focus arms the window", focus.contains("if (hasFocus) armVisibleWindow()"))
        val arm = bodyOf(source, "private fun armVisibleWindow")
        assertTrue("armVisibleWindow posts the promised duration", arm.contains("postDelayed(autoDismiss, FLASH_DURATION_MS)"))
        assertTrue("the visible window cancels the hard stop", arm.contains("removeCallbacks(hardStop)"))
        val newIntent = bodyOf(source, "override fun onNewIntent")
        assertTrue("a reused singleInstance instance still re-arms", newIntent.contains("armVisibleWindow()"))
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

    /** One declaration's body in [source], up to the next class-level member. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing '$signature' in the flash source", start >= 0)
        val rest = source.substring(start + signature.length)
        val next = Regex("\\n    (override fun|private fun|fun |companion object)").find(rest)
        return if (next == null) rest else rest.substring(0, next.range.first)
    }

    @Test
    fun `flash launches with NEW_TASK only`() {
        val source = codeOf(flashSource)
        assertTrue("flash intent must use NEW_TASK", source.contains("FLAG_ACTIVITY_NEW_TASK"))
        assertFalse("flash intent must never clear the user's task", source.contains("CLEAR_TASK"))
    }

    @Test
    fun `the engine has one launch expression and reports both outcomes`() {
        val source = codeOf(serviceSource)
        assertTrue("FilterService must launch the flash", source.contains("ContentFlash.intent("))
        assertEquals(
            "exactly one flash launch expression (the retry re-enters launchFlash)",
            1,
            Regex("ContentFlash\\.intent\\(").findAll(source).count(),
        )
        assertFalse(source.contains("CLEAR_TASK"))
        // D12: a failed launch and a same-episode suppression are both stated in logcat.
        assertTrue("failed launches must be logged", source.contains("escape flash launch failed"))
        assertTrue("suppression must not hide behind infoOnce", source.contains("escape flash suppressed"))
    }
}
