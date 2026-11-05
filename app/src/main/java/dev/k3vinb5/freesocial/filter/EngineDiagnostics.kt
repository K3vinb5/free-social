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
