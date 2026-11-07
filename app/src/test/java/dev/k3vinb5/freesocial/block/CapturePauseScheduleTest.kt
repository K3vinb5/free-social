package dev.k3vinb5.freesocial.block

import dev.k3vinb5.freesocial.filter.DumpArm
import dev.k3vinb5.freesocial.settings.AppMode
import dev.k3vinb5.freesocial.settings.AppTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D16 — blocking must come back when a capture arm expires, without waiting for the next
 * accessibility event. The service asks [CapturePauseSchedule] when to re-run the gate; this test
 * drives that decision with a fake clock and simulates the service's handler queue explicitly.
 */
class CapturePauseScheduleTest {

    private var now = 5_000_000L

    @Before
    fun setUp() {
        DumpArm.useClock { now }
        DumpArm.cancel()
    }

    @After
    fun tearDown() {
        DumpArm.setOnDisarmed(null)
        DumpArm.useClock()
        DumpArm.cancel()
    }

    private fun schedule() = CapturePauseSchedule(
        remainingMillis = { millis -> DumpArm.remainingMillis(millis) },
        now = { now },
    )

    private fun gate() = BlockGate(ownPackage = "dev.k3vinb5.freesocial")

    /** The service's evaluation at [now], with the capture state sampled on the same clock. */
    private fun BlockGate.decideOnThisDevice() = decide(
        packageName = AppTarget.INSTAGRAM.packageName,
        target = AppTarget.INSTAGRAM,
        mode = AppMode.BLOCK,
        deviceInteractive = true,
        keyguardLocked = false,
        scheduleLocked = false,
        nowMillis = now,
        dumpArmed = DumpArm.isArmed(now),
    )

    @Test
    fun `nothing armed evaluates immediately`() {
        assertEquals(CapturePauseSchedule.Step.Evaluate, schedule().step())
    }

    @Test
    fun `an arm schedules exactly one re-check at its deadline`() {
        DumpArm.armGuided("home")

        assertEquals(
            CapturePauseSchedule.Step.RecheckIn(DumpArm.GUIDED_EXPIRY_MS),
            schedule().step(),
        )
    }

    @Test
    fun `a refreshed arm moves the scheduled re-check`() {
        DumpArm.armGuided("home")
        now += 30_000
        DumpArm.refreshGuided(now)

        assertEquals(
            CapturePauseSchedule.Step.RecheckIn(DumpArm.GUIDED_EXPIRY_MS),
            schedule().step(),
        )
    }

    @Test
    fun `the re-check never fires before the arm deadline`() {
        DumpArm.armGuided("home")
        val gate = gate()
        val step = schedule().step() as CapturePauseSchedule.Step.RecheckIn

        now += step.delayMs - 1
        assertEquals(BlockDecision.Ignore, gate.decideOnThisDevice())
        assertEquals(CapturePauseSchedule.Step.RecheckIn(1L), schedule().step())
    }

    /**
     * Reviewer finding: this is the BlockService wiring in one test. `GuidedCapture.finish()` calls
     * `DumpArm.cancel()`; the registered consumer is the service's immediate re-check. The clock does
     * not move at all between arm and cancel, so the gate can only be active again because the
     * disarm was noticed — not because a deadline passed.
     */
    @Test
    fun `a cancelled capture re-activates the gate immediately, before any deadline`() {
        val gate = gate()
        var onDisarmDecision: BlockDecision? = null
        // Same thing BlockService.onArmDisarmed() ends up doing: evaluate the current package now.
        DumpArm.setOnDisarmed { onDisarmDecision = gate.decideOnThisDevice() }
        try {
            DumpArm.armGuided("home")
            assertEquals(BlockDecision.Ignore, gate.decideOnThisDevice())
            assertEquals(CapturePauseSchedule.Step.RecheckIn(DumpArm.GUIDED_EXPIRY_MS), schedule().step())

            DumpArm.cancel() // GuidedCapture.finish(): zero events, zero elapsed time

            assertTrue("the consumer ran synchronously", onDisarmDecision is BlockDecision.Launch)
            assertEquals(
                AppTarget.INSTAGRAM.packageName,
                (onDisarmDecision as BlockDecision.Launch).packageName,
            )
            assertEquals(CapturePauseSchedule.Step.Evaluate, schedule().step())
            assertNull(DumpArm.remainingMillis())
        } finally {
            DumpArm.setOnDisarmed(null)
            DumpArm.cancel()
        }
    }

    /**
     * The F4 bug in one test: the user arms a capture, the phone then produces **zero** further
     * accessibility events, and the arm expires. The only thing that runs is the re-check the
     * service scheduled; at that moment the gate must be active again for the app still in front.
     */
    @Test
    fun `arm expiry re-activates the gate with zero events`() {
        val gate = gate()
        DumpArm.armGuided("home")

        // Event 1 (the arm itself): blocking is down and the service is told when to look again.
        val step = schedule().step() as CapturePauseSchedule.Step.RecheckIn
        assertEquals(DumpArm.GUIDED_EXPIRY_MS, step.delayMs)
        assertEquals(BlockDecision.Ignore, gate.decideOnThisDevice())

        // Between here and the deadline there is no event: only the scheduled re-check advances.
        now += step.delayMs
        assertEquals(CapturePauseSchedule.Step.Evaluate, schedule().step())

        val decision = gate.decideOnThisDevice()
        assertTrue(
            "the app the user is sitting on must be blocked again",
            decision is BlockDecision.Launch,
        )
        assertEquals(BlockReason.APP_BLOCK, (decision as BlockDecision.Launch).reason)
        assertEquals(AppTarget.INSTAGRAM.packageName, decision.packageName)
    }
}
