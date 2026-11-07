package dev.k3vinb5.freesocial.filter

/**
 * The one-shot "arm, then switch app" handshake between the rule inspector and [FilterService].
 *
 * The inspector cannot read another app's window from its own process, so the user arms a capture
 * and then opens the target app; the service sees the next `typeWindowStateChanged` for one of
 * [FilterService.TARGET_PACKAGES] and writes that tree. Deliberately boring: two fields, plain
 * synchronization, no Android imports so the state machine stays JVM-testable with an injected
 * clock.
 */
object DumpArm {

    /** An armed capture older than this is dead — the user did not open a target app in time. */
    const val EXPIRY_MS = 60_000L

    /**
     * A guided capture owns the arm for the whole flow: up to 30 s of surface detection plus 15 s
     * of recording, with the notification and launch transition in between. It must never expire
     * while the recording is still running, or filtering would resume mid-capture.
     */
    const val GUIDED_EXPIRY_MS = 120_000L

    private var armedAt: Long = 0L
    private var screen: String? = null
    private var guided: Boolean = false

    /** Arms one capture labelled [screen] (the inspector's screen name for the dump document). */
    @Synchronized
    fun arm(screen: String, now: Long = System.currentTimeMillis()) {
        armedAt = now
        this.screen = screen
        guided = false
    }

    /**
     * Arms a guided capture: filtering stays paused through detection + recording, and the service
     * leaves the actual dump to [GuidedCapture] (see [readyFor]).
     */
    @Synchronized
    fun armGuided(screen: String, now: Long = System.currentTimeMillis()) {
        armedAt = now
        this.screen = screen
        guided = true
    }

    /** Extends a guided arm across a phase change (detection -> recording). */
    @Synchronized
    fun refreshGuided(now: Long = System.currentTimeMillis()) {
        if (guided && screen != null) armedAt = now
    }

    fun isGuided(now: Long = System.currentTimeMillis()): Boolean = guided && isArmed(now)

    /** The armed screen label, or null when nothing is armed / the arm has expired. */
    @Synchronized
    fun armedScreen(now: Long = System.currentTimeMillis()): String? {
        val label = screen ?: return null
        if (expired(now)) {
            clear()
            return null
        }
        return label
    }

    fun isArmed(now: Long = System.currentTimeMillis()): Boolean = armedScreen(now) != null

    /**
     * True while a capture owns the device: a manual 60 s arm waiting for its target window, or a
     * guided session across detection + recording (120 s ceiling, refreshed per phase).
     *
     * Both enforcement engines stand down while this is true — filtering reads [isArmed] in
     * `FilterService`, blocking reads this in `block.BlockService`. The capture has to see the
     * target app exactly as the user does: a block screen over it (or a flash) would replace the
     * tree the dump must record.
     */
    fun captureActive(now: Long = System.currentTimeMillis()): Boolean = isArmed(now)

    /**
     * Claims the arm for one capture. Returns the screen label and clears the state, or null when
     * nothing (valid) is armed — a capture then must not run.
     */
    @Synchronized
    fun consume(now: Long = System.currentTimeMillis()): String? {
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
        now - armedAt >= if (guided) GUIDED_EXPIRY_MS else EXPIRY_MS

    private fun clear() {
        armedAt = 0L
        screen = null
        guided = false
    }
}
