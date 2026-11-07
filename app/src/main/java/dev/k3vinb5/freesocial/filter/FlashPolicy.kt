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
 *
 * Episode boundary (D12 addendum, 2026-11-07): a capture is only evidence when the engine can read
 * it. A kept-surface match keeps the episode; any other direct surface ends it immediately; only a
 * *trustworthy* capture with **no** matched surface at all counts toward departure, and it takes
 * [DEPARTURES_TO_END] consecutive such captures to end the episode. Empty or view-id-less captures
 * are [observe]d with `captureValid = false` and never make a boundary decision, because in those
 * trees every viewId rule misses and "the surface left" is indistinguishable from "nothing matched".
 */
class FlashPolicy(private val windowMs: Long = WINDOW_MS) {

    /** Last flash actually shown per package. */
    private val lastFlashMs = HashMap<String, Long>()

    /** The surface whose episode owns the package's current flash window. */
    private val episodeSurface = HashMap<String, String>()

    /** Consecutive trustworthy captures that matched no surface, per package. */
    private val departures = HashMap<String, Int>()

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
        departures.remove(packageName)
        return true
    }

    /**
     * Feeds one capture's visible surfaces. A kept surface holds the episode; a different direct
     * surface ends it at once; an empty match set ends it only after [DEPARTURES_TO_END]
     * consecutive trustworthy captures. An untrustworthy capture ([captureValid] = false — empty
     * tree, no view ids) is not evidence and leaves the episode (and the miss count) untouched.
     */
    fun observe(packageName: String, visibleSurfaces: Set<String>, captureValid: Boolean = true) {
        val surface = episodeSurface[packageName] ?: return
        if (!captureValid) return
        if (surface in visibleSurfaces) {
            departures.remove(packageName)
            return
        }
        if (visibleSurfaces.isNotEmpty()) {
            // A different direct surface is on screen: the escaped surface left unambiguously.
            endEpisode(packageName)
            return
        }
        val misses = (departures[packageName] ?: 0) + 1
        if (misses >= DEPARTURES_TO_END) endEpisode(packageName) else departures[packageName] = misses
    }

    /**
     * The foreground window moved to [packageName]: every other package's episode is over, because
     * its app is no longer the visible window. The new package keeps its own episode, so a window
     * change inside the same app cannot manufacture a fresh flash on a surface that never left.
     */
    fun onPackageChanged(packageName: String) {
        episodeSurface.keys.filter { it != packageName }.forEach { endEpisode(it) }
    }

    /** True while [surface] owns [packageName]'s current flash episode (diagnostics/tests). */
    fun episodeOf(packageName: String): String? = episodeSurface[packageName]

    private fun endEpisode(packageName: String) {
        episodeSurface.remove(packageName)
        departures.remove(packageName)
    }

    companion object {
        /** Three seconds: one flash per escape episode, comfortably below the 1200 ms debounce noise. */
        const val WINDOW_MS = 3_000L

        /**
         * Trustworthy captures with no matched surface needed before the episode is over. One
         * capture can miss a stale or half-drawn screen; two in a row is a real departure.
         */
        const val DEPARTURES_TO_END = 2
    }
}
