package dev.k3vinb5.freesocial.filter

/**
 * DIRECT-ENGAGEMENT action arbiter (policy 2026-11-05).
 *
 * There is no post-escape suppression window any more. The old 5 s hold produced the user-visible
 * allow/deny alternation: `click a reel → goes back, click again → allows, scroll → goes back`.
 * The verdict is now a pure function of the capture + the budget ([RuleGate]); this class only
 * absorbs event storms and duplicate actions for the *same* surface episode:
 *
 * 1. [observe] runs on every scan with the surfaces the capture actually shows. A surface that
 *    disappears ends its episode; when it appears again its 1200 ms debounce stamp is cleared, so
 *    a real re-entry is acted on immediately and identically (N entries → N escapes).
 * 2. [tryAcquire] refuses a second action for a surface that was already escaped and is still on
 *    screen (the asynchronous BACK's stale tree), and otherwise applies the unchanged
 *    [ActionThrottle] debounce/quota.
 *
 * Debounce only ever delays a repeat within one episode; it can never turn an exhausted entry into
 * an allowed one. Pure: the caller supplies the monotonic clock and the captured surface set.
 */
class ActionArbiter(
    private val throttle: ActionThrottle = ActionThrottle(),
) {
    /** Surfaces already escaped in the current, still-visible episode. */
    private val escaped = mutableSetOf<String>()
    private var visible: Set<String> = emptySet()

    /**
     * Records one capture's visible surfaces (package-qualified keys). Surfaces that are gone leave
     * the escaped set; surfaces that just appeared get a fresh debounce so a re-entry is not
     * delayed by the previous entry's action.
     */
    fun observe(present: Set<String>) {
        val appeared = present - visible
        // ActionThrottle keys its debounce by surface slug, so strip the package qualifier.
        for (key in appeared) throttle.reset(key.substringAfter('/'))
        escaped.retainAll(present)
        visible = present
    }

    /** True when this surface may be acted on now. Never consults a time window. */
    fun tryAcquire(surface: String, appPackage: String, now: Long): Boolean {
        val key = key(appPackage, surface)
        if (key in escaped) return false
        return throttle.tryAcquire(surface, appPackage, now)
    }

    /**
     * Records the result of an action. Only a confirmed escape opens the episode guard; a rejected
     * action leaves the surface actionable (the debounce still spaces retries).
     */
    fun confirm(surface: String, appPackage: String, escapeAction: Boolean, acted: Boolean) {
        if (!escapeAction || !acted) return
        val key = key(appPackage, surface)
        escaped += key
        visible = visible + key
    }

    fun reset() {
        escaped.clear()
        visible = emptySet()
        throttle.reset()
    }

    private fun key(appPackage: String, surface: String): String = "$appPackage/$surface"
}
