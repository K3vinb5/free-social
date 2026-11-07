package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.ui.screens.DumpDelete
import dev.k3vinb5.freesocial.ui.screens.performDumpDelete
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Track B: user-triggered dump deletion in the Rule Inspector.
 *
 * The auto-pruner's policy is untouched (it always keeps the newest dump per package+surface); the
 * user can still remove that one and every other listed dump, and "delete all" must never touch a
 * file that is not one of our parsable dumps.
 */
class DumpDeleteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun stamp(minutesAgo: Long = 0L): String =
        ZonedDateTime.now().minusMinutes(minutesAgo).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun writeDump(
        dir: File,
        name: String,
        pkg: String = YT,
        surface: String = "home",
        capturedAt: String = stamp(),
    ): File {
        val document = DumpDocument(
            appPackage = pkg,
            screen = surface,
            surface = surface,
            surfaceVerified = true,
            capturedAt = capturedAt,
        )
        return File(dir, name).apply { writeText(DumpExporter.encode(document)) }
    }

    @Test
    fun `deleteOne removes exactly one file and its list entry`() {
        val dir = tmp.newFolder("dumps")
        val home = writeDump(dir, "$YT-home.json", surface = "home")
        val reels = writeDump(dir, "$YT-reels.json", surface = "reels", capturedAt = stamp(1))
        assertEquals(2, DumpExporter.listIn(dir).size)

        val entry = DumpExporter.listIn(dir).first { it.file.name == home.name }
        assertTrue(DumpExporter.deleteOne(entry.file))

        assertFalse("the chosen dump is gone", home.exists())
        assertTrue("its neighbour is untouched", reels.exists())
        val after = DumpExporter.listIn(dir)
        assertEquals(1, after.size)
        assertEquals(reels.name, after.single().file.name)
    }

    @Test
    fun `a dump auto-prune keeps as newest is still deletable`() {
        val dir = tmp.newFolder("dumps")
        // 27 fresh captures of one surface: housekeeping keeps the newest 25 ("in use") and drops
        // the two oldest. The user may still remove a kept file on request.
        val files = (1..27).map { i ->
            writeDump(dir, "$YT-home-$i.json", capturedAt = stamp(i.toLong()))
        }

        val listed = DumpExporter.listIn(dir)
        assertEquals("the per-surface cap is still applied by the pruner", 25, listed.size)
        assertFalse("the oldest capture was pruned", files.last().exists())
        assertEquals("$YT-home-1.json", listed.first().file.name)

        assertTrue(DumpExporter.deleteOne(listed.first().file))
        assertFalse("the newest, cap-exempt dump is gone", files.first().exists())
        assertEquals(24, DumpExporter.listIn(dir).size)
    }

    @Test
    fun `deleteAll removes every dump and never a non-dump file`() {
        val dir = tmp.newFolder("dumps")
        writeDump(dir, "$YT-home.json", surface = "home")
        writeDump(dir, "$IG-home.json", pkg = IG, surface = "home")
        val foreign = File(dir, "notes.txt").apply { writeText("not a dump") }
        val broken = File(dir, "broken.json").apply { writeText("{half-written") }

        assertEquals(2, DumpExporter.deleteAllIn(dir))

        assertTrue(DumpExporter.listIn(dir).isEmpty())
        assertTrue("a foreign file is left alone", foreign.exists())
        assertTrue("an unparsable .json is not one of our dumps", broken.exists())
    }

    @Test
    fun `an empty dumps directory deletes zero`() {
        val dir = tmp.newFolder("dumps")
        assertEquals(0, DumpExporter.deleteAllIn(dir))
    }

    @Test
    fun `confirmed delete calls exactly the matching repository operation`() {
        val dir = tmp.newFolder("dumps")
        val file = writeDump(dir, "$YT-home.json")
        val entry = DumpFile(file, DumpExporter.read(file)!!.document, 0)

        var oneCalls = 0
        var allCalls = 0
        val oneFiles = mutableListOf<File>()

        val removedOne = performDumpDelete(
            target = DumpDelete.One(entry),
            deleteOne = { one -> oneCalls++; oneFiles += one; true },
            deleteAll = { allCalls++; 0 },
        )
        assertEquals(1, removedOne)
        assertEquals(1, oneCalls)
        assertEquals(0, allCalls)
        assertEquals(listOf(file), oneFiles)

        val removedAll = performDumpDelete(
            target = DumpDelete.All,
            deleteOne = { error("delete all must not delete one by one") },
            deleteAll = { 7 },
        )
        assertEquals(7, removedAll)
    }

    @Test
    fun `the inspector arms a confirmation before wiring any delete`() {
        // Compose has no JVM harness here; the repo's UI tests assert the wiring as text. Every
        // entry point sets the prompt state, and the deleters appear once, inside the confirm block.
        val source = File(
            "src/main/java/dev/k3vinb5/freesocial/ui/screens/RuleInspectorScreen.kt",
        ).readText()
        assertTrue(source.contains("onDelete = { pendingDelete = DumpDelete.One(dump) }"))
        assertTrue(source.contains("onClick = { pendingDelete = DumpDelete.All }"))
        assertEquals(1, Regex("""DumpExporter\.deleteOne\(""").findAll(source).count())
        assertEquals(1, Regex("""DumpExporter\.deleteAll\(""").findAll(source).count())
        assertTrue(
            "the delete calls sit after the destructive confirm button",
            source.indexOf("DumpExporter.deleteOne(") >
                source.indexOf("R.string.inspector_delete_confirm"),
        )
    }
}
