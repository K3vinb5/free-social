package dev.k3vinb5.freesocial.filter

import java.time.Duration
import java.time.Instant

/**
 * Housekeeping policy for saved dumps, kept pure so the rules are testable on the JVM.
 *
 * A dump goes away when it is older than [MAX_AGE] or when it falls outside the newest
 * [MAX_PER_SURFACE] captures of its (package, surface) pair. The dump currently being written is
 * never selected. Nothing here touches the disk; [DumpExporter] applies the result.
 */
object DumpPruner {
    val MAX_AGE: Duration = Duration.ofDays(14)
    const val MAX_PER_SURFACE: Int = 25

    /** One dump already on disk; [surface] is the header's verified slug, or null when unknown. */
    data class Entry(
        val name: String,
        val packageName: String,
        val surface: String?,
        val timestamp: Instant,
    )

    /**
     * Names the policy deletes. [now] is the reference clock; [current] is the file just written
     * (or null), which is always kept. Deterministic: ties break on the file name.
     */
    fun select(entries: List<Entry>, now: Instant, current: String? = null): List<String> {
        val cutoff = now.minus(MAX_AGE)
        return entries
            .asSequence()
            .filter { it.name != current }
            .groupBy { it.packageName to it.surface }
            .values
            .flatMap { group ->
                val newestFirst = group.sortedWith(
                    compareByDescending<Entry> { it.timestamp }.thenBy { it.name },
                )
                newestFirst.withIndex()
                    .filter { (index, entry) -> index >= MAX_PER_SURFACE || entry.timestamp.isBefore(cutoff) }
                    .map { it.value.name }
            }
            .toList()
    }
}
