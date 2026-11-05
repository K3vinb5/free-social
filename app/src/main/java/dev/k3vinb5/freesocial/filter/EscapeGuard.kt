package dev.k3vinb5.freesocial.filter

/**
 * R3 fix — post-escape hold (per package + surface).
 *
 * The 1200 ms [ActionThrottle] debounce is enough for a burst of layout events, but an escape
 * action is asynchronous: `performGlobalAction(BACK)` returns before the exit animation ends and
 * the accessibility tree still shows the surface that was just left. The accessibility events
 * caused by the navigation then trigger fresh scans on that stale tree; once the debounce has
 * expired (1.2 s) each of them fires another BACK. On the Reno4 R3 run that was
 * `11:42:03.535 → 11:42:04.815 → 11:42:06.147`, three BACKs for one exhausted budget.
 *
 * This records a successful escape and suppresses that same package+surface until either
 * 1. a later capture no longer contains the escaped surface (the window state really changed), or
 * 2. [holdMs] has elapsed (a short fixed window, so a BACK that was swallowed — e.g. by a sheet —
 *    is still retried instead of leaving the surface permanently unguarded).
 *
 * Pure: the caller supplies the monotonic clock and the "does this scan still show the surface"
 * fact, so the stale-tree sequence is JVM-testable.
 */
class EscapeGuard(private val holdMs: Long = DEFAULT_HOLD_MS) {
    private val heldUntil = mutableMapOf<String, Long>()

    /** Called after the framework confirmed an escape action, so it does not re-fire on stale trees. */
    fun record(key: String, now: Long) {
        heldUntil[key] = now
    }

    /**
     * True when an escape for [key] already ran and the current capture still shows the same
     * surface. A capture that no longer shows it releases the hold immediately.
     */
    fun suppressed(key: String, now: Long, surfaceStillPresent: Boolean): Boolean {
        val stamp = heldUntil[key] ?: return false
        if (!surfaceStillPresent || now - stamp >= holdMs) {
            heldUntil.remove(key)
            return false
        }
        return true
    }

    fun reset() = heldUntil.clear()

    companion object {
        /** The stale-tree window this run observed was 1.3 s; 5 s is short and covers it. */
        const val DEFAULT_HOLD_MS = 5_000L
    }
}

/**
 * The one place a scan asks "may I act?" — the existing 1200 ms debounce plus the R3 post-escape
 * hold, in that order:
 *
 * 1. a held escape suppresses first (no throttle quota is spent by a stale-tree scan), then
 * 2. [ActionThrottle.tryAcquire] keeps its 1200 ms per-surface debounce and per-app quota.
 *
 * The caller reports the outcome through [confirm]; only a confirmed escape action
 * (BACK / TAP_HOME_TAB / REDIRECT_HOME) opens a hold. `SCROLL_PAST` and `CLICK_VIEW_ID` stay on
 * the debounce alone, so a shelf that is only partly displaced can still be scrolled again.
 */
class ActionArbiter(
    private val throttle: ActionThrottle = ActionThrottle(),
    private val escape: EscapeGuard = EscapeGuard(),
) {
    fun tryAcquire(
        surface: String,
        appPackage: String,
        now: Long,
        surfaceStillPresent: Boolean,
    ): Boolean {
        val key = key(appPackage, surface)
        if (escape.suppressed(key, now, surfaceStillPresent)) return false
        return throttle.tryAcquire(surface, appPackage, now)
    }

    /** Records a confirmed escape so the following stale-tree scans cannot re-fire it. */
    fun confirm(surface: String, appPackage: String, now: Long, escapeAction: Boolean) {
        if (escapeAction) escape.record(key(appPackage, surface), now)
    }

    private fun key(appPackage: String, surface: String): String = "$appPackage/$surface"
}
