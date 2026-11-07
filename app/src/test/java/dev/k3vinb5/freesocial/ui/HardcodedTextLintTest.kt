package dev.k3vinb5.freesocial.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Style lint, not a behavioural test: user-facing copy belongs in res/values/strings.xml, so a
 * literal that reaches a Compose text/accessibility slot under ui/ fails the build.
 *
 * The pattern list is deliberately tight — Log tags, preference keys, intent extras, MIME types
 * and content-description *regexes* are not UI copy and must not trip it. Interpolated strings
 * (starting with "${...}") are exempt because they compose runtime data; the surrounding text they
 * add is still covered by the string resources they concatenate.
 */
class HardcodedTextLintTest {

    private val uiDir = File("src/main/java/dev/k3vinb5/freesocial/ui")

    /** `Text("…")`, `text = "…"`, `contentDescription = "…"`, `label = "…"`, etc. */
    private val literal = Regex(
        """\b(?:Text|text|contentDescription|title|subtitle|label|actionLabel|status)\s*(?:\(\s*|=)\s*"[^"$]""",
    )

    /** `Toast.makeText(context, "…", …)`. */
    private val toast = Regex("""Toast\.makeText\([^,]+,\s*"[^"$]""")

    @Test
    fun `no user-facing string literals remain under ui`() {
        val offenders = mutableListOf<String>()
        uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            codeOf(file).lineSequence().forEachIndexed { index, line ->
                if (literal.containsMatchIn(line) || toast.containsMatchIn(line)) {
                    offenders += "${file.path}:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "Hardcoded UI text found; move it to res/values/strings.xml:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** Comments may name the pattern they forbid, so strip them before scanning. */
    private fun codeOf(file: File): String = file.readText()
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")
}
