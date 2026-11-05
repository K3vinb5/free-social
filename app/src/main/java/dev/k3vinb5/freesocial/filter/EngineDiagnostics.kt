package dev.k3vinb5.freesocial.filter

/**
 * D5 — self-healing initialisation.
 *
 * `onServiceConnected` used to be the only place the settings repo and the rule catalog were
 * loaded; any throw there left every event returning silently for the life of the process. This
 * keeps the last failure and re-runs the loader lazily, at most once per [retryAfterMs], until it
 * succeeds. Pure: the loader and the clock are injected, so the retry schedule is JVM-testable.
 */
class EngineBootstrap<T>(
    private val load: () -> T?,
    private val clock: () -> Long,
    private val retryAfterMs: Long = RETRY_AFTER_MS,
) {
    @Volatile
    var deps: T? = null
        private set

    /** The throwable from the most recent failed attempt, for the loud log line. */
    @Volatile
    var lastError: Throwable? = null
        private set

    private var lastFailureAt: Long? = null

    /** The loaded dependencies, or null when init failed this attempt (and is not yet due again). */
    fun attempt(now: Long = clock(), force: Boolean = false): T? {
        deps?.let { return it }
        if (!force && !due(now)) return null
        return try {
            val loaded = load() ?: error("engine init returned null")
            deps = loaded
            lastError = null
            lastFailureAt = null
            loaded
        } catch (t: Throwable) {
            lastError = t
            lastFailureAt = now
            null
        }
    }

    /** Cached dependency, or a lazy attempt when it is missing (every accessibility event calls this). */
    fun current(now: Long = clock()): T? = deps ?: attempt(now)

    private fun due(now: Long): Boolean {
        val failedAt = lastFailureAt ?: return true
        return now - failedAt >= retryAfterMs
    }

    companion object {
        /** Retry a failed init at most every 5 s of event activity. */
        const val RETRY_AFTER_MS = 5_000L
    }
}

/**
 * D5(c)/(d) — one log per reason per window. Keys are caller-chosen reasons ("repo null",
 * "not interactive", "no view ids:com.instagram.android", …), so a noisy reason cannot mute a
 * different one. Pure: the caller supplies the monotonic clock reading.
 */
class LogLimiter(private val windowMs: Long) {
    private val lastByReason = mutableMapOf<String, Long>()

    /** True exactly when this reason has not logged within the window; records the log when true. */
    fun allow(reason: String, now: Long): Boolean {
        val previous = lastByReason[reason]
        if (previous != null && now - previous < windowMs) return false
        lastByReason[reason] = now
        return true
    }

    fun reset() {
        lastByReason.clear()
    }
}

/**
 * D8 — ghost-tree probe formatting.
 *
 * The ColorOS defect hands the service a decor-only root (3 nodes) while `uiautomator` sees the
 * full content tree. This keeps the *formatting and candidate selection* pure and JVM-testable;
 * every live read (`rootInActiveWindow`, `getChild`, `findAccessibilityNodeInfosByViewId`) stays in
 * FilterService. The `probe ` prefix is the grep anchor and is always present.
 */
object GhostTreeProbe {
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    /** Candidate view ids that exist in a real YouTube tree but never in the decor-only root. */
    val YOUTUBE_VIEW_IDS: List<String> = listOf(
        "$YOUTUBE_PACKAGE:id/menu_item_0",
        "$YOUTUBE_PACKAGE:id/app_bar_wrapper",
        "$YOUTUBE_PACKAGE:id/results",
    )

    /** Shape of the live root; `childCount == -1` and nulls mean "not readable". */
    data class RootShape(
        val packageName: String? = null,
        val viewId: String? = null,
        val childCount: Int = -1,
        val grandchildCount: Int? = null,
        /** D8 layer (d): childCount of `event.source` — the tree the framework hands us per event. */
        val eventSourceChildCount: Int? = null,
        /** D8 layer (d): `root.refresh()` then `root.childCount` — a stale-links snapshot shows up here. */
        val refreshChildCount: Int? = null,
    )

    /** The ids worth probing for [packageName]: the YouTube trio, or nothing for other packages. */
    fun idsFor(packageName: String?): List<String> =
        if (packageName == YOUTUBE_PACKAGE) YOUTUBE_VIEW_IDS else emptyList()

    /** `menu_item_0` for `com.google.android.youtube:id/menu_item_0`. */
    fun shortId(viewId: String): String = viewId.substringAfterLast('/')

    /** `menu_item_0=0 app_bar_wrapper=1 results=0`; `n/a` when there is nothing to probe. */
    fun idProbeText(ids: List<String>, counts: List<Int>): String {
        if (ids.isEmpty()) return "n/a"
        return ids.indices.joinToString(" ") { i ->
            "${shortId(ids[i])}=${counts.getOrElse(i) { 0 }}"
        }
    }

    /**
     * The whole probe line body. `rootChildCount=3` is the ghost-tree signature; the `idProbe`
     * counts say whether `findAccessibilityNodeInfosByViewId` can still reach the content.
     * `eventSourceChildCount` / `refreshChildCount` (D8 layer (d)) discriminate the remaining
     * mechanisms: a rich `event.source` means the capture should walk the event subtree, a rich
     * refreshed root means the window snapshot is stale and a `refresh()` at capture fixes it,
     * both pruned means the vendor prunes the framework's link tree and the lookup grafts are the
     * permanent mitigation.
     */
    fun format(pkg: String, shape: RootShape, idProbe: String): String =
        "probe $pkg rootPkg=${shape.packageName ?: "none"} rootChildCount=${shape.childCount} " +
            "rootViewId=${shape.viewId ?: "none"} " +
            "child0Grandchildren=${shape.grandchildCount ?: "none"} idProbe=$idProbe " +
            "eventSourceChildCount=${shape.eventSourceChildCount ?: "none"} " +
            "refreshChildCount=${shape.refreshChildCount ?: "none"}"

    /** The same root shape and counts appended to the per-target `scan` line (D5(d)). */
    fun scanSuffix(shape: RootShape, idProbe: String): String =
        "rootChildCount=${shape.childCount}, idProbe=$idProbe"
}

/**
 * D5(d)/D8 — the per-target `scan` line, pure so the diagnostic fields (notably `grafted=`) are
 * JVM-testable. `grafted` counts nodes the D8 lookups recovered beyond the child links, both the
 * view-id lookup and the text lookup.
 */
object ScanDiagnostics {
    fun line(
        appPackage: String,
        appName: String,
        nodes: Int,
        withViewIds: Int,
        matchedRules: Int,
        totalRules: Int,
        matched: Int,
        grafted: Int,
        windows: List<WindowStat>,
        probe: String,
    ): String =
        "scan $appPackage ($appName): $nodes nodes, $withViewIds with viewId, " +
            "rules=$matchedRules/$totalRules, matched=$matched, grafted=$grafted, " +
            "windows=${windows.size} [${windowText(windows)}], $probe"

    fun windowText(windows: List<WindowStat>): String = windows.joinToString("; ") { window ->
        "${window.title ?: window.className ?: "?"}(${window.layer})=${window.nodes}/${window.viewIds} ids"
    }
}
