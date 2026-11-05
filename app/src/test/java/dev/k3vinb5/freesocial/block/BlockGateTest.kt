package dev.k3vinb5.freesocial.block

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
    ) = decide(
        packageName = pkg,
        target = target,
        mode = mode,
        deviceInteractive = interactive,
        keyguardLocked = keyguard,
        scheduleLocked = locked,
        nowMillis = now,
        allowedPackages = allowed,
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
}
