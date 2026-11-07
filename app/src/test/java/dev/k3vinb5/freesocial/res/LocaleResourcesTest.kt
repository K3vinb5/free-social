package dev.k3vinb5.freesocial.res

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Completeness and placeholder parity for the shipped locales. This is the test docs/dev/LOCALES.md
 * points at: adding values-xx/ without translating every key (or by changing a %1$s placeholder)
 * fails here before the build can ship a half-translated screen.
 */
class LocaleResourcesTest {

    private val resDir = File("src/main/res")
    private val locales = listOf("pt", "es")

    /** name -> (value, translatable) for <string>; name -> quantity -> value for <plurals>. */
    private class Table(
        val strings: Map<String, Pair<String, Boolean>>,
        val plurals: Map<String, Map<String, String>>,
    )

    private fun load(folder: String): Table {
        val file = File(resDir, "$folder/strings.xml")
        assertTrue("${file.path} is missing", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val strings = mutableMapOf<String, Pair<String, Boolean>>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> strings[name] = (node.textContent ?: "") to
                    (node.getAttribute("translatable") != "false")
                "plurals" -> {
                    val items = mutableMapOf<String, String>()
                    for (j in 0 until node.childNodes.length) {
                        val item = node.childNodes.item(j) as? Element ?: continue
                        if (item.tagName == "item") items[item.getAttribute("quantity")] = item.textContent ?: ""
                    }
                    plurals[name] = items
                }
            }
        }
        return Table(strings, plurals)
    }

    private val default = load("values")

    /** Translatable keys only: symbols and brand marks may stay English. */
    private val required = default.strings.filterValues { it.second }.keys

    private fun placeholders(text: String): Set<String> =
        Regex("""%(?:\d+\$)?[a-zA-Z]""").findAll(text).map { it.value }.toSet()

    @Test
    fun `every translatable key exists in each locale`() {
        for (locale in locales) {
            val table = load("values-$locale")
            assertEquals(
                "values-$locale must translate every default string",
                required,
                table.strings.filterValues { it.second }.keys,
            )
            assertEquals(
                "values-$locale must translate every default plural",
                default.plurals.keys,
                table.plurals.keys,
            )
        }
    }

    @Test
    fun `format placeholders are identical across locales`() {
        for (locale in locales) {
            val table = load("values-$locale")
            for ((name, value) in table.strings) {
                val english = default.strings[name] ?: continue
                assertEquals(
                    "values-$locale/$name changed its format arguments",
                    placeholders(english.first),
                    placeholders(value.first),
                )
            }
            for ((name, quantities) in table.plurals) {
                val english = default.plurals[name] ?: continue
                for ((quantity, value) in quantities) {
                    val source = english[quantity] ?: continue
                    assertEquals(
                        "values-$locale plurals/$name ($quantity) changed its format arguments",
                        placeholders(source),
                        placeholders(value),
                    )
                }
            }
        }
    }

    @Test
    fun `every translated plural keeps the default quantities`() {
        for (locale in locales) {
            val table = load("values-$locale")
            for ((name, quantities) in default.plurals) {
                val translated = table.plurals.getValue(name)
                assertTrue(
                    "values-$locale plurals/$name is missing a quantity",
                    translated.keys.containsAll(quantities.keys),
                )
            }
        }
    }

    @Test
    fun `translatable-false strings are not duplicated in locale files`() {
        val nonTranslatable = default.strings.filterValues { !it.second }.keys
        for (locale in locales) {
            val table = load("values-$locale")
            val duplicated = table.strings.keys intersect nonTranslatable
            assertTrue("values-$locale must not redeclare $duplicated", duplicated.isEmpty())
        }
    }
}
