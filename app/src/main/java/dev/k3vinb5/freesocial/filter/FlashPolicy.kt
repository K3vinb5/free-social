package dev.k3vinb5.freesocial.filter

/**
 * Rate limit for the escape flash, scoped to the escape **episode** (D12, 2026-11-07).
 *
 * The original policy (2026-11-05) throttled one flash per [windowMs] per *package*, which muted a
 * genuine new episode whenever the user re-entered a short-video surface within 3 s of the last
 * flash — four 0-budget Reels entries produced two flashes on the phone. An episode is the same
 * unit [ActionArbiter] uses: it ends the moment the escaped surface leaves a capture, and the next
 * entry is a new, deterministic escape that must explain itself again.
 *
 * So: a new (package, surface) episode always flashes; the window only spaces repeats *inside* one
 * episode. [observe] is fed every capture's visible surfaces and drops the episode when its surface
 * is gone. A suppressed call does not refresh the window: the next allowed flash is measured from
 * the last flash that was actually shown. The clock is injected, so the policy is pure.
 */
class FlashPolicy(private val windowMs: Long = WINDOW_MS) {

    /** Last flash actually shown per package. */
    private val lastFlashMs = HashMap<String, Long>()

    /** The surface whose episode owns the package's current flash window. */
    private val episodeSurface = HashMap<String, String>()

    /**
     * True when a flash may be shown for [surface] of [packageName] at [nowMs] — and records it if
     * so. A different surface is a different episode and is never throttled by the previous one.
     */
    fun shouldFlash(packageName: String, surface: String, nowMs: Long): Boolean {
        val last = lastFlashMs[packageName]
        val sameEpisode = episodeSurface[packageName] == surface
        if (last != null && sameEpisode && nowMs - last in 0 until windowMs) return false
        lastFlashMs[packageName] = nowMs
        episodeSurface[packageName] = surface
        return true
    }

    /**
     * Feeds one capture's visible surfaces: when the surface that took the package's last flash is
     * no longer on screen, its episode is over and the next escape flashes immediately.
     */
    fun observe(packageName: String, visibleSurfaces: Set<String>) {
        val surface = episodeSurface[packageName] ?: return
        if (surface !in visibleSurfaces) episodeSurface.remove(packageName)
    }

    /** True while [surface] owns [packageName]'s current flash episode (diagnostics/tests). */
    fun episodeOf(packageName: String): String? = episodeSurface[packageName]

    companion object {
        /** Three seconds: one flash per escape episode, comfortably below the 1200 ms debounce noise. */
        const val WINDOW_MS = 3_000L
    }
}
