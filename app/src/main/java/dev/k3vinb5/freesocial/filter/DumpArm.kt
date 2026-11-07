package dev.k3vinb5.freesocial.filter

import android.os.SystemClock

/**
 * The one-shot capture handshake between the rule inspector and [FilterService].
 *
 * The inspector's guided session arms the capture and then launches the target app; the service
 * sees the target's `typeWindowStateChanged`/poll and [GuidedCapture] writes the verified tree.
 * Deliberately boring: two fields, plain synchronization, and an injectable clock so the state
 * machine stays JVM-testable.
 *
 * **One clock domain.** Every arm lives in monotonic elapsed-realtime millis — the same source
 * `GuidedCapture` polls on and `FilterService` uses. Mixing a wall-clock stamp with an
 * elapsedRealtime refresh made a guided arm look expired at the detection → recording handover
 * (and blocking then resumed mid-recording), so the domain is fixed and injectable, never implicit.
 *
 * D17: the old manual "arm, then switch app" entry point (`arm()`, 60 s `EXPIRY_MS`) had no
 * production caller after guided capture shipped and was removed; every arm is a guided one now.
 */
object DumpArm {

    /**
     * A guided capture owns the arm for the whole flow: up to 30 s of surface detection plus 15 s
     * of recording, with the notification and launch transition in between. It must never expire
     * while the recording is still running, or filtering would resume mid-capture.
     */
    const val GUIDED_EXPIRY_MS = 120_000L

    private var armedAt: Long = 0L
    private var screen: String? = null
    private var guided: Boolean = false

    /** Device clock: monotonic elapsedRealtime; the JVM fallback only bites in unit tests. */
    private val deviceClock: () -> Long = {
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.currentTimeMillis() }
    }

    @Volatile
    private var clock: () -> Long = deviceClock

    /**
     * Installs the clock used whenever a caller omits `now`. The device default is already this
     * domain; tests inject a fake, and `useClock()` with no argument restores the device clock.
     */
    fun useClock(provider: () -> Long = deviceClock) {
        clock = provider
    }

    /** Current time in the arm's single domain (elapsed-realtime millis on device). */
    fun nowMillis(): Long = clock()

    /**
     * Arms a guided capture: filtering stays paused through detection + recording, and the service
     * leaves the actual dump to [GuidedCapture] (see [readyFor]).
     */
    @Synchronized
    fun armGuided(screen: String, now: Long = clock()) {
        armedAt = now
        this.screen = screen
        guided = true
    }

    /**
     * Extends a guided arm across a phase change (detection -> recording). [now] is in the same
     * elapsed-realtime domain as every other arm write — see the class doc.
     */
    @Synchronized
    fun refreshGuided(now: Long = clock()) {
        if (guided && screen != null) armedAt = now
    }

    fun isGuided(now: Long = clock()): Boolean = guided && isArmed(now)

    /** The armed screen label, or null when nothing is armed / the arm has expired. */
    @Synchronized
    fun armedScreen(now: Long = clock()): String? {
        val label = screen ?: return null
        if (expired(now)) {
            clear()
            return null
        }
        return label
    }

    fun isArmed(now: Long = clock()): Boolean = armedScreen(now) != null

    /**
     * Millis left before the active guided arm expires, or null when nothing is armed (an expired
     * arm clears itself). `block.BlockService` schedules its post-expiry re-check on this (D16).
     */
    @Synchronized
    fun remainingMillis(now: Long = clock()): Long? {
        if (screen == null) return null
        if (expired(now)) {
            clear()
            return null
        }
        return GUIDED_EXPIRY_MS - (now - armedAt)
    }

    /**
     * True while a capture owns the device: a guided session across detection + recording (120 s
     * ceiling, refreshed per phase).
     *
     * Both enforcement engines stand down while this is true — filtering reads [isArmed] in
     * `FilterService`, blocking reads this (via [remainingMillis]) in `block.BlockService`. The
     * capture has to see the target app exactly as the user does: a block screen over it (or a
     * flash) would replace the tree the dump must record.
     */
    fun captureActive(now: Long = clock()): Boolean = isArmed(now)

    /**
     * Claims the arm for one capture. Returns the screen label and clears the state, or null when
     * nothing (valid) is armed — a capture then must not run.
     */
    @Synchronized
    fun consume(now: Long = clock()): String? {
        val label = armedScreen(now) ?: return null
        clear()
        return label
    }

    /**
     * True when the active window belongs to [target], i.e. the capture would read the target app.
     *
     * A launch's `TYPE_WINDOW_STATE_CHANGED` can reach the service while the *previous* app's
     * window is still the active root (Wave A A5 and Wave A retry R4 both captured a 4-node decor
     * tree labelled "YouTube" because of it). The arm must stay armed in that case and be consumed
     * by the next state change, once the target window is really up. An unreadable root
     * (`activePackage == null`) does not block the capture — the dump then reports the ghost tree
     * it actually saw.
     */
    fun readyFor(target: String, activePackage: String?): Boolean =
        if (guided) false else activePackage == null || activePackage == target

    /**
     * True when a capture is still the decor-only ghost tree (Wave A A5: 4 nodes, 1 view id, no
     * grafts) even though the target window is already active. The arm is then kept and the
     * capture retried for a short bounded window, so the inspector shows the same content the
     * scan sees a moment later instead of the launch transition.
     */
    fun looksGhost(nodeCount: Int, grafts: Int): Boolean =
        grafts == 0 && nodeCount <= GHOST_NODE_LIMIT

    /** The observed ghost is 4 nodes (synthetic root + 3 decor); real YouTube content is 40+. */
    const val GHOST_NODE_LIMIT = 4

    /** Disarms without capturing (expiry handling in the UI, or an explicit cancel). */
    @Synchronized
    fun cancel() {
        clear()
    }

    private fun expired(now: Long): Boolean =
        now - armedAt >= GUIDED_EXPIRY_MS

    private fun clear() {
        armedAt = 0L
        screen = null
        guided = false
    }
}
