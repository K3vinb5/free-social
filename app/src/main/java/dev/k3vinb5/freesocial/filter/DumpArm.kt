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

    private var armedAt: Long = 0L
    private var screen: String? = null

    /** Arms one capture labelled [screen] (the inspector's screen name for the dump document). */
    @Synchronized
    fun arm(screen: String, now: Long = System.currentTimeMillis()) {
        armedAt = now
        this.screen = screen
    }

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
     * Claims the arm for one capture. Returns the screen label and clears the state, or null when
     * nothing (valid) is armed — a capture then must not run.
     */
    @Synchronized
    fun consume(now: Long = System.currentTimeMillis()): String? {
        val label = armedScreen(now) ?: return null
        clear()
        return label
    }

    /** Disarms without capturing (expiry handling in the UI, or an explicit cancel). */
    @Synchronized
    fun cancel() {
        clear()
    }

    private fun expired(now: Long): Boolean = now - armedAt >= EXPIRY_MS

    private fun clear() {
        armedAt = 0L
        screen = null
    }
}
