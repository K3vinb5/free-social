package dev.k3vinb5.freesocial.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pins for the heal-from-dump loop on the Rule Inspector, both found on device on
 * 2026-11-07 (`reverse/runtime-reno/heal/REPORT.md`, bugs A and B).
 *
 * The screen is Compose, so the observable contract is the handler wiring; the pure behaviour it
 * delegates to ([dev.k3vinb5.freesocial.filter.RuleCatalog.applyOverride],
 * [dev.k3vinb5.freesocial.filter.RuleOverride.parse]) is covered by `RuleOverrideTest` and the
 * device run.
 */
class RuleInspectorHealTest {

    private fun screenSource(): String =
        File("src/main/java/dev/k3vinb5/freesocial/ui/screens/RuleInspectorScreen.kt").readText()

    /** The `onClick` body of the "Export rules" button. */
    private fun exportHandler(): String = screenSource()
        .substringAfter("text = stringResource(R.string.inspector_export)")
        .substringBefore("text = stringResource(R.string.inspector_import)")

    @Test
    fun `export publishes the document through the catalog so the screen state matches the disk`() {
        val export = exportHandler()

        // Device bug A: the handler wrote filesDir/rules-override.json with a bare writeText, so a
        // file that *is* loaded at the next process start existed while the screen still read
        // "Built-in rules" and hid both the import time and "Use built-in rules".
        assertTrue(
            "the export must adopt the document it writes",
            export.contains("RuleCatalog.applyOverride("),
        )
        assertFalse(
            "a bare override-file write leaves the screen on the built-in source",
            export.contains("overrideFile(") || export.contains(".writeText("),
        )
        assertTrue(
            "the screen must re-read the catalog after publishing the file",
            export.contains("refreshCatalog()"),
        )
    }

    @Test
    fun `the import picker asks for json alone so the exported document is listed`() {
        // Device bug B: ColorOS DocumentsUI filters on the base type and, when
        // EXTRA_MIME_TYPES is present, hides the application/json rows — so
        // ActivityResultContracts.OpenDocument (base "*/*" + extras) never showed the
        // `rules-override.json` the app had just exported, while
        // `am start -a OPEN_DOCUMENT -t application/json` lists it immediately.
        assertTrue(
            "the base type must be the document the export writes",
            IMPORT_BASE_TYPE == "application/json",
        )
        val intent = screenSource()
            .substringAfter("private fun importDocumentIntent()")
            .substringBefore("/**")
        assertFalse(
            "EXTRA_MIME_TYPES hides the exported json on this picker",
            intent.contains("EXTRA_MIME_TYPES") || intent.contains("IMPORT_MIME_TYPES"),
        )
        assertTrue(
            "the import button must launch the shared intent",
            screenSource().contains("importLauncher.launch(importDocumentIntent())"),
        )
    }
}
