package dev.k3vinb5.freesocial.filter

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Housekeeping policy for saved dumps: 14-day age cutoff plus newest 25 per (package, surface). */
class DumpPrunerTest {

    private val now = Instant.parse("2026-11-05T12:00:00Z")

    private fun entry(
        name: String,
        packageName: String = YT,
        surface: String? = "home",
        ageDays: Long = 0,
        ageMinutes: Long = 0,
    ) = DumpPruner.Entry(
        name,
        packageName,
        surface,
        now.minus(Duration.ofDays(ageDays)).minus(Duration.ofMinutes(ageMinutes)),
    )

    @Test
    fun `dumps older than 14 days are deleted, newer ones are kept`() {
        val doomed = DumpPruner.select(
            listOf(
                entry("old.json", ageDays = 15),
                entry("edge.json", ageDays = 14),
                entry("fresh.json", ageDays = 1),
            ),
            now,
        ).toSet()
        assertEquals(setOf("old.json"), doomed)
    }

    @Test
    fun `only the newest 25 dumps of a surface survive`() {
        val entries = (1..30).map { i -> entry("dump-$i.json", ageMinutes = i.toLong()) }
        val doomed = DumpPruner.select(entries, now).toSet()
        assertEquals((26..30).map { "dump-$it.json" }.toSet(), doomed)
        assertTrue("dump-25.json" !in doomed)
    }

    @Test
    fun `age and the per-surface cap apply independently across packages and surfaces`() {
        val entries = listOf(
            entry("yt-home-old.json", ageDays = 20),
            entry("yt-home-fresh.json", ageDays = 2),
            entry("yt-reels-fresh.json", surface = "reels", ageDays = 1),
            entry("ig-home-old.json", packageName = IG, ageDays = 30),
            entry("ig-home-fresh.json", packageName = IG, ageDays = 3),
            entry("yt-unknown.json", surface = null, ageDays = 4),
        )
        val doomed = DumpPruner.select(entries, now).toSet()
        assertEquals(setOf("yt-home-old.json", "ig-home-old.json"), doomed)
    }

    @Test
    fun `an empty list deletes nothing`() {
        assertTrue(DumpPruner.select(emptyList(), now).isEmpty())
    }

    @Test
    fun `the dump being written is never deleted even when over the cap`() {
        val entries = (1..30).map { i -> entry("dump-$i.json", ageMinutes = i.toLong()) }
        val doomed = DumpPruner.select(entries, now, current = "dump-30.json").toSet()
        assertTrue("the current dump is never selected", "dump-30.json" !in doomed)
        assertEquals((26..29).map { "dump-$it.json" }.toSet(), doomed)
    }
}
