package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.core.loadRuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locale variants for `yt_shorts_shelf` (D8 round 2): the live Reno4 YouTube speaks Portuguese, so
 * every shelf card is described `"<title> – reproduzir Short"` (U+2013 en dash) instead of
 * `" - play Short"`, and the old English-only regex matched nothing (`matched=0`).
 *
 * The alternates are plain top-level regex branches — the mechanism the model already supports, and
 * one [RuleLookup] already turns into literal `findAccessibilityNodeInfosByText` queries. The pt
 * values are taken verbatim from `reverse/runtime-reno/pt-yt-home3.xml` (device locale pt-PT; the
 * video titles are pt-BR but the UI string observed is pt-PT's). The header stays "Shorts".
 */
class YouTubeShelfLocaleTest {

    private val rule: Rule = loadRuleSet(
        java.io.File("src/main/assets/rules.json").readText(),
    ).rules.single { it.id == "yt_shorts_shelf" }

    private fun match(tree: NodeSnapshot): NodeSnapshot? = RuleMatcher(rule.match).match(tree)

    /** pt-yt-home3 shape: header "Shorts" plus four `"… – reproduzir Short"` shelf cards. */
    private fun shelfTree(cardDescriptions: List<String>, header: Boolean = true): NodeSnapshot {
        val children = mutableListOf<NodeSnapshot>()
        if (header) {
            children += node(
                className = "android.view.ViewGroup",
                description = "Shorts",
                bounds = Bounds(192, 443, 353, 509),
            )
        }
        cardDescriptions.forEachIndexed { i, desc ->
            children += node(
                className = "android.view.ViewGroup",
                description = desc,
                bounds = Bounds(36 + i * 260, 554, 529 + i * 260, 1293),
            )
        }
        return node(className = "android.widget.FrameLayout", children = children)
    }

    @Test
    fun `the pt-PT shelf string from the live dump matches`() {
        // Verbatim from pt-yt-home3 (pt-PT device): the card the rule must catch.
        val ptCard = "Mac mini M6: is 32GB enough to run local AI? – reproduzir Short"
        val hit = match(shelfTree(listOf(ptCard)))
        assertNotNull("pt shelf card must match the locale alternate", hit)
        assertEquals("Shorts", hit?.contentDescription) // header is first in pre-order
    }

    @Test
    fun `the pt card alone matches, without the shared Shorts header`() {
        val ptCard = "Goku Fought Jiren In 10x GRAVITY?!… – reproduzir Short"
        assertNotNull("the pt branch must not depend on the header", match(shelfTree(listOf(ptCard), header = false)))
    }

    @Test
    fun `the English shelf strings still match`() {
        assertNotNull(match(shelfTree(listOf("The smartest girl in the US! - play Short"))))
        assertNotNull(match(shelfTree(emptyList()))) // bare "Shorts" header, 234610-yt_home
    }

    @Test
    fun `a non-shorts pt description does not match`() {
        // pt-yt-home1: ordinary video card, "reproduzir vídeo" is not a Short.
        val ptVideo = "Davy Jones viu o GTA VI ANTES de todo mundo – 8 minutos e 39 segundos – reproduzir vídeo"
        assertNull(match(shelfTree(listOf(ptVideo), header = false)))
    }

    @Test
    fun `both locale literals are queryable for the text graft`() {
        val queries = RuleLookup.literalQueries(rule.match.contentDescriptionRegex)
        assertTrue("EN literal must stay queryable", queries.contains("- play short"))
        assertTrue("pt literal must be queryable", queries.contains("– reproduzir short"))
        assertTrue("bare header literal must stay queryable", queries.contains("shorts"))
    }
}
