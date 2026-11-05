package dev.k3vinb5.freesocial.filter

/**
 * Rate limit for the escape flash (2026-11-05): at most one flash per [windowMs] per package.
 *
 * Rapid follow-up escapes of the same episode (a BACK that lands on a second Reels entry, a scan
 * burst right after the action) must not stack flashes on top of each other. The clock is injected
 * so the policy is pure and the whole window is testable without Android or a real clock.
 *
 * A suppressed call does not refresh the window: the next allowed flash is measured from the last
 * flash that was actually shown.
 */
class FlashPolicy(private val windowMs: Long = WINDOW_MS) {

    private val lastFlashMs = HashMap<String, Long>()

    /** True when a flash may be shown for [packageName] at [nowMs] — and records it if so. */
    fun shouldFlash(packageName: String, nowMs: Long): Boolean {
        val last = lastFlashMs[packageName]
        if (last != null && nowMs - last in 0 until windowMs) return false
        lastFlashMs[packageName] = nowMs
        return true
    }

    companion object {
        /** Three seconds: one flash per escape episode, comfortably below the 1200 ms debounce noise. */
        const val WINDOW_MS = 3_000L
    }
}
