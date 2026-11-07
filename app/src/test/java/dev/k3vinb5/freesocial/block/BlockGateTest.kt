package dev.k3vinb5.freesocial.block

import dev.k3vinb5.freesocial.filter.DumpArm
import dev.k3vinb5.freesocial.settings.AppMode
import dev.k3vinb5.freesocial.settings.AppTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision table for the whole-app gate (SPEC §2.3), no Android runtime needed. */
class BlockGateTest {

    private val ownPackage = "dev.k3vinb5.freesocial"
    private val le = "com.instagram.android"
    private val launcher = "com.google.android.apps.nexuslauncher"

    private fun gate() = BlockGate(ownPackage = ownPackage)

    private fun BlockGate.decide(
        pkg: String? = le,
        target: AppTarget? = AppTarget.INSTAGRAM,
        mode: AppMode? = AppMode.BLOCK,
        interactive: Boolean = true,
        keyguard: Boolean = false,
        locked: Boolean = false,
        now: Long = 1_000L,
        allowed: Set<String> = emptySet(),
        dumpArmed: Boolean = false,
    ) = decide(
        packageName = pkg,
        target = target,
        mode = mode,
        deviceInteractive = interactive,
        keyguardLocked = keyguard,
        scheduleLocked = locked,
        nowMillis = now,
        allowedPackages = allowed,
        dumpArmed = dumpArmed,
    )

    @Test
    fun `screen off never launches`() {
        assertEquals(BlockDecision.Ignore, gate().decide(interactive = false))
    }

    @Test
    fun `keyguard locked never launches`() {
        assertEquals(BlockDecision.Ignore, gate().decide(keyguard = true))
    }

    @Test
    fun `own package is never blocked`() {
        assertEquals(BlockDecision.Ignore, gate().decide(pkg = ownPackage, target = null))
    }

    @Test
    fun `system ui and com android packages are never blocked`() {
        assertEquals(BlockDecision.Ignore, gate().decide(pkg = "com.android.systemui", target = null))
        assertEquals(BlockDecision.Ignore, gate().decide(pkg = "com.android.settings", target = null))
    }

    @Test
    fun `launcher package passed in allowed set is never blocked`() {
        assertEquals(
            BlockDecision.Ignore,
            gate().decide(pkg = launcher, target = null, allowed = setOf(launcher)),
        )
    }

    @Test
    fun `filter and allow modes never launch`() {
        assertEquals(BlockDecision.Ignore, gate().decide(mode = AppMode.FILTER))
        assertEquals(BlockDecision.Ignore, gate().decide(mode = AppMode.ALLOW))
        assertEquals(BlockDecision.Ignore, gate().decide(mode = null))
    }

    @Test
    fun `unknown package never launches`() {
        assertEquals(BlockDecision.Ignore, gate().decide(pkg = "com.example.other", target = null))
    }

    @Test
    fun `block mode launches with app reason and platform slug`() {
        val decision = gate().decide()
        assertEquals(
            BlockDecision.Launch(platform = "instagram", reason = BlockReason.APP_BLOCK, packageName = le),
            decision,
        )
    }

    @Test
    fun `schedule locked wins over the app reason`() {
        val decision = gate().decide(locked = true)
        assertEquals(BlockReason.SCHEDULE, (decision as BlockDecision.Launch).reason)
    }

    @Test
    fun `all three targets map to their slugs`() {
        assertEquals("youtube", (gate().decide(pkg = AppTarget.YOUTUBE.packageName, target = AppTarget.YOUTUBE) as BlockDecision.Launch).platform)
        assertEquals("tiktok", (gate().decide(pkg = AppTarget.TIKTOK.packageName, target = AppTarget.TIKTOK) as BlockDecision.Launch).platform)
    }

    @Test
    fun `tiktok global build resolves to the tiktok target`() {
        assertEquals(AppTarget.TIKTOK, BlockGate.resolveTarget("com.ss.android.ugc.trill"))
        assertEquals(AppTarget.INSTAGRAM, BlockGate.resolveTarget("com.instagram.android"))
        assertEquals(null, BlockGate.resolveTarget("com.example.other"))
        assertEquals(null, BlockGate.resolveTarget("  "))
    }

    @Test
    fun `500 ms dedupe suppresses the second launch but not the third`() {
        val gate = gate()
        assertTrue(gate.decide(now = 1_000L) is BlockDecision.Launch)
        assertEquals(BlockDecision.Ignore, gate.decide(now = 1_499L))
        assertTrue(gate.decide(now = 1_500L) is BlockDecision.Launch)
    }

    @Test
    fun `dedupe is per package`() {
        val gate = gate()
        assertTrue(gate.decide(pkg = AppTarget.INSTAGRAM.packageName, target = AppTarget.INSTAGRAM, now = 1_000L) is BlockDecision.Launch)
        assertTrue(
            gate.decide(pkg = AppTarget.YOUTUBE.packageName, target = AppTarget.YOUTUBE, now = 1_100L)
                is BlockDecision.Launch,
        )
    }

    @Test
    fun `reset clears the dedupe window`() {
        val gate = gate()
        gate.decide(now = 1_000L)
        gate.reset()
        assertTrue(gate.decide(now = 1_001L) is BlockDecision.Launch)
    }

    /**
     * Track B: the same `DumpArm` state that pauses filtering pauses blocking. A guided arm is
     * checked with a fake clock; expiry and cancellation hand control back to the gate.
     */
    @Test
    fun `a pending capture arm pauses blocking until it expires`() {
        DumpArm.cancel()
        try {
            DumpArm.armGuided("IG · home feed", now = 1_000)
            val armedGate = gate()
            assertEquals(
                BlockDecision.Ignore,
                armedGate.decide(now = 2_000, dumpArmed = DumpArm.captureActive(2_000)),
            )
            assertEquals(
                BlockDecision.Ignore,
                armedGate.decide(
                    now = 1_000 + DumpArm.GUIDED_EXPIRY_MS - 1,
                    dumpArmed = DumpArm.captureActive(1_000 + DumpArm.GUIDED_EXPIRY_MS - 1),
                ),
            )
            // The arm is dead: the gate fires on the next event.
            assertTrue(
                armedGate.decide(
                    now = 1_000 + DumpArm.GUIDED_EXPIRY_MS,
                    dumpArmed = DumpArm.captureActive(1_000 + DumpArm.GUIDED_EXPIRY_MS),
                ) is BlockDecision.Launch,
            )
        } finally {
            DumpArm.cancel()
        }
    }

    @Test
    fun `cancelling the arm resumes blocking immediately`() {
        DumpArm.cancel()
        try {
            DumpArm.armGuided("YT · shorts player", now = 1_000)
            val armedGate = gate()
            assertEquals(
                BlockDecision.Ignore,
                armedGate.decide(now = 2_000, dumpArmed = DumpArm.captureActive(2_000)),
            )
            // The session finished: the arm is cancelled, so no leftover pause state remains.
            DumpArm.cancel()
            assertTrue(
                armedGate.decide(now = 2_001, dumpArmed = DumpArm.captureActive(2_001))
                    is BlockDecision.Launch,
            )
        } finally {
            DumpArm.cancel()
        }
    }

    @Test
    fun `a guided capture pauses blocking across detection and recording, then resumes`() {
        DumpArm.cancel()
        try {
            DumpArm.armGuided("home", now = 1_000)
            val armedGate = gate()
            // Detection can take up to 30 s and recording 15 s: the guided arm still owns the
            // device well past the first minute.
            assertEquals(
                BlockDecision.Ignore,
                armedGate.decide(now = 61_000, dumpArmed = DumpArm.captureActive(61_000)),
            )
            // Detection -> recording refreshes the window.
            DumpArm.refreshGuided(now = 100_000)
            assertEquals(
                BlockDecision.Ignore,
                armedGate.decide(now = 219_000, dumpArmed = DumpArm.captureActive(219_000)),
            )
            // Ceiling reached: enforcement is back.
            assertTrue(
                armedGate.decide(
                    now = 100_000 + DumpArm.GUIDED_EXPIRY_MS,
                    dumpArmed = DumpArm.captureActive(100_000 + DumpArm.GUIDED_EXPIRY_MS),
                ) is BlockDecision.Launch,
            )
        } finally {
            DumpArm.cancel()
        }
    }

    @Test
    fun `the pause never writes the dedupe map, so blocking is not muted after it`() {
        val gate = gate()
        assertEquals(
            BlockDecision.Ignore,
            gate.decide(now = 1_000L, dumpArmed = true),
        )
        // Same millisecond, capture over: a stale dedupe entry would have swallowed this launch.
        assertTrue(gate.decide(now = 1_000L, dumpArmed = false) is BlockDecision.Launch)
    }
}
