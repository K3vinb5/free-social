package dev.k3vinb5.freesocial.filter

import android.os.SystemClock

/**
 * The guided-capture arm shared by the rule inspector and the two enforcement services.
 *
 * `GuidedCapture` arms it for the whole session (surface detection + recording), refreshes it at
 * the detection → recording handover, and cancels it when the session ends. Filtering reads
 * [isArmed] (`FilterService`), blocking reads [remainingMillis] and registers [setOnDisarmed]
 * (`block.BlockService`, which re-evaluates the gate the moment the arm ends).
 *
 * **One clock domain.** Every arm lives in monotonic elapsed-realtime millis — the same source
 * `GuidedCapture` polls on and `FilterService` uses. Mixing a wall-clock stamp with an
 * elapsedRealtime refresh made a guided arm look expired at the detection → recording handover
 * (and blocking then resumed mid-recording), so the domain is fixed and injectable, never implicit.
 *
 * **Disarm notification.** An arm ends by `cancel()` (session finished, user stop, new session) or
 * by expiry. Both clear the state and then invoke the one registered listener *outside* the lock,
 * so a pause consumer never has to poll or wait for a deadline it can no longer see. This is what
 * made the block screen linger for the rest of the window after a capture finished early.
 *
 * D17 removed the manual "arm, then switch app" entry point. The follow-up sweep removed every API
 * only the deleted service-side consumer used (`consume`, `readyFor`, `looksGhost`, `isGuided`,
 * `captureActive`, the public armed-label accessor), so the surface below is exactly what production
 * calls plus the clock seam the JVM tests need.
 */
object DumpArm {

    /**
     * A guided capture owns the arm for the whole flow: up to 30 s of surface detection plus 15 s
     * of recording, with the notification and launch transition in between. It must never expire
     * while the recording is still running, or filtering would resume mid-capture.
     */
    const val GUIDED_EXPIRY_MS = 120_000L

    private val lock = Any()

    private var armedAt: Long = 0L
    private var screen: String? = null

    /** The single pause consumer (BlockService). Runs after the arm is cleared, never under [lock]. */
    @Volatile
    private var onDisarmed: (() -> Unit)? = null

    /** Device clock: monotonic elapsedRealtime; the JVM fallback only bites in unit tests. */
    private val deviceClock: () -> Long = {
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.currentTimeMillis() }
    }

    @Volatile
    private var clock: () -> Long = deviceClock

    /**
     * Installs the clock used whenever a caller omits `now`. The device default is already this
     * domain; tests inject a fake, and `useClock()` with no argument restores the device clock.
     *
     * Not a production entry point by design: it is the seam that lets the JVM tests drive the
     * single-clock-domain rules (arm / refresh / expiry) without sleeping.
     */
    fun useClock(provider: () -> Long = deviceClock) {
        clock = provider
    }

    /**
     * Registers the one pause consumer that must re-evaluate immediately when the arm ends, or
     * clears it with `null` when the service is destroyed. Replacing it is idempotent.
     */
    fun setOnDisarmed(listener: (() -> Unit)?) {
        onDisarmed = listener
    }

    /** Current time in the arm's single domain (elapsed-realtime millis on device). */
    fun nowMillis(): Long = clock()

    /** Arms a guided capture for [screen] (the surface slug recorded in the dump). */
    fun armGuided(screen: String, now: Long = clock()) {
        synchronized(lock) {
            armedAt = now
            this.screen = screen
        }
    }

    /**
     * Extends a guided arm across a phase change (detection -> recording). [now] is in the same
     * elapsed-realtime domain as every other arm write — see the class doc.
     */
    fun refreshGuided(now: Long = clock()) {
        synchronized(lock) {
            if (screen != null) armedAt = now
        }
    }

    /** True while a capture owns the device; `FilterService` pauses interaction on it. */
    fun isArmed(now: Long = clock()): Boolean = armedLabel(now) != null

    /**
     * Millis left before the active arm expires, or null when nothing is armed (an expired arm
     * clears itself and notifies, exactly like [cancel]). `block.BlockService` schedules its
     * post-expiry re-check on this (D16).
     */
    fun remainingMillis(now: Long = clock()): Long? {
        var expiredNow = false
        val remaining = synchronized(lock) {
            val current = screen
            if (current == null) {
                null
            } else if (expired(now)) {
                clearLocked()
                expiredNow = true
                null
            } else {
                GUIDED_EXPIRY_MS - (now - armedAt)
            }
        }
        if (expiredNow) notifyDisarmed()
        return remaining
    }

    /**
     * Disarms without capturing (a finished session, a user cancel, or a new session) and tells the
     * registered consumer so enforcement resumes now instead of at the arm's deadline.
     */
    fun cancel() {
        val wasArmed = synchronized(lock) { clearLocked() }
        if (wasArmed) notifyDisarmed()
    }

    /** The armed surface label, or null when nothing is armed / the arm has expired. */
    private fun armedLabel(now: Long): String? {
        var expiredNow = false
        val label = synchronized(lock) {
            val current = screen
            if (current == null) {
                null
            } else if (expired(now)) {
                clearLocked()
                expiredNow = true
                null
            } else {
                current
            }
        }
        if (expiredNow) notifyDisarmed()
        return label
    }

    private fun notifyDisarmed() {
        val listener = onDisarmed ?: return
        runCatching { listener() }
    }

    private fun expired(now: Long): Boolean =
        now - armedAt >= GUIDED_EXPIRY_MS

    /** Returns true when an arm was actually dropped (so only a real disarm notifies). */
    private fun clearLocked(): Boolean {
        val wasArmed = screen != null
        armedAt = 0L
        screen = null
        return wasArmed
    }
}
