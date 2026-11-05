package dev.k3vinb5.freesocial.filter

import android.content.Context

/**
 * D7 — dead-engine detection (SPEC §2.4).
 *
 * The filter service writes a heartbeat while it is connected and handling events. The settings
 * hub reads it: a heartbeat that was never written or is older than [STALE_AFTER_MS] means the
 * engine is not filtering, even though the accessibility toggle is on. Silent degradation is the
 * exact failure this exists to surface, so the prefs file is separate from the settings store.
 *
 * [isStale] is pure and takes the last beat explicitly, so the truth table is JVM-testable.
 */
object EngineHealth {
    const val PREFS_NAME = "engine_health"
    const val KEY_HEARTBEAT_MS = "engine_heartbeat_ms"

    /** Older than this counts as dead. Two missed 60 s beats. */
    const val STALE_AFTER_MS = 120_000L

    /** The service writes at most one beat per this interval while events keep arriving. */
    const val BEAT_INTERVAL_MS = 60_000L

    private const val NEVER = -1L

    /** Writes the heartbeat; called on connect and on event activity (throttled by the service). */
    fun recordBeat(context: Context, nowMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_HEARTBEAT_MS, nowMs)
            .apply()
    }

    /** The last written heartbeat, or null when the engine has never been up. */
    fun lastBeat(context: Context): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_HEARTBEAT_MS, NEVER)
            .takeIf { it > 0 }

    /** Pure decision: never written → stale; older than [staleAfterMs] → stale. */
    fun isStale(
        lastBeatMs: Long?,
        nowMs: Long,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): Boolean {
        if (lastBeatMs == null || lastBeatMs <= 0) return true
        val age = nowMs - lastBeatMs
        // A beat from the future is a wall-clock jump, not a dead engine.
        return age > staleAfterMs
    }

    fun isStale(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
        isStale(lastBeat(context), nowMs)
}
