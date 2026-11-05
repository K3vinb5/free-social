package dev.k3vinb5.freesocial.filter

/**
 * Action throttle (03a §1.4): a 1200 ms debounce per surface plus a per-app quota, so a burst of
 * layout events can never machine-gun Back presses.
 *
 * Pure: the caller supplies the monotonic clock reading in milliseconds.
 */
class ActionThrottle(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val quotaMax: Int = DEFAULT_QUOTA_MAX,
    private val quotaWindowMs: Long = DEFAULT_QUOTA_WINDOW_MS,
) {
    private val lastActionBySurface = mutableMapOf<String, Long>()
    private val actionStampsByPackage = mutableMapOf<String, ArrayDeque<Long>>()

    /** True when this action may run now; records the action when it does. */
    fun tryAcquire(surfaceKey: String, appPackage: String, now: Long): Boolean {
        val last = lastActionBySurface[surfaceKey]
        if (last != null && now - last < debounceMs) return false

        val stamps = actionStampsByPackage.getOrPut(appPackage) { ArrayDeque() }
        while (stamps.isNotEmpty() && now - stamps.first() >= quotaWindowMs) stamps.removeFirst()
        if (stamps.size >= quotaMax) return false

        lastActionBySurface[surfaceKey] = now
        stamps.addLast(now)
        return true
    }

    fun actionsInWindow(appPackage: String): Int = actionStampsByPackage[appPackage]?.size ?: 0

    /** Clears one surface's debounce stamp — a genuine re-entry must not inherit it. */
    fun reset(surfaceKey: String) {
        lastActionBySurface.remove(surfaceKey)
    }

    fun reset() {
        lastActionBySurface.clear()
        actionStampsByPackage.clear()
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 1200L
        const val DEFAULT_QUOTA_MAX = 20
        const val DEFAULT_QUOTA_WINDOW_MS = 60_000L
    }
}

/**
 * Stopwatch for a budgeted short-video surface (SPEC §2.2).
 *
 * Time accrues only while a surface is active; [tick] returns the whole seconds that became due,
 * keeping the sub-second remainder so repeated ticks do not drift. Leaving the surface and the
 * screen going off both stop the clock, and the final partial second flushes on leave.
 */
class SurfaceStopwatch {
    var activeSurface: String? = null
        private set

    private var lastTimestamp = 0L
    private var pendingMillis = 0L

    /** Enter a surface; re-entering the same one is a no-op, switching flushes the old one. */
    fun enter(surface: String, now: Long): Long {
        if (activeSurface == surface) return 0
        val flushed = if (activeSurface != null) {
            accumulate(now)
            drain()
        } else {
            0
        }
        activeSurface = surface
        lastTimestamp = now
        return flushed
    }

    /** Leave the current surface and flush everything accrued, including the partial second. */
    fun leave(now: Long): Long {
        if (activeSurface == null) return 0
        accumulate(now)
        activeSurface = null
        val seconds = pendingMillis / MILLIS_PER_SECOND
        pendingMillis = 0
        return seconds
    }

    /** 1 s heartbeat; returns seconds to record, or 0 when nothing is active. */
    fun tick(now: Long): Long {
        if (activeSurface == null) return 0
        accumulate(now)
        return drain()
    }

    private fun accumulate(now: Long) {
        val delta = now - lastTimestamp
        if (delta > 0) {
            pendingMillis += delta
            lastTimestamp = now
        }
    }

    private fun drain(): Long {
        val seconds = pendingMillis / MILLIS_PER_SECOND
        if (seconds > 0) pendingMillis -= seconds * MILLIS_PER_SECOND
        return seconds
    }

    companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val TICK_INTERVAL_MS = 1000L
    }
}
