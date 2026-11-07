package dev.k3vinb5.freesocial.ui.screens

import dev.k3vinb5.freesocial.settings.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard's auto-advance rules driven through the same glue the screen uses (resume → armed
 * beat → advance) against a fake live status, so "did it move?" is asserted without Compose.
 */
class WizardAutoAdvanceTest {

    /** Stands in for OnboardingScreen: one machine, one step, a captured beat. */
    private class Harness(initialStep: Int, initialStatus: PermissionStatus) {
        val machine = WizardAutoAdvance()
        var step = initialStep
        var status = initialStatus
        var finishes = 0
        var beatPending = false

        init {
            enter(initialStep)
        }

        private fun enter(target: Int) {
            step = target
            machine.onStepEntered(target, WizardSteps.condition(target, status))
        }

        /** One lifecycle resume with a freshly-read permission snapshot. */
        fun resume(read: PermissionStatus = status) {
            status = read
            if (machine.onResume(step, WizardSteps.condition(step, status))) beatPending = true
        }

        /** The user taps the step's own Next / Skip / Finish. */
        fun manualNext() {
            beatPending = false
            if (WizardProgress.isLast(step)) finishes++ else enter(WizardProgress.next(step))
        }

        /** The ~650 ms beat elapses; returns true when the step actually moved or finished. */
        fun beatElapsed(): Boolean {
            if (!beatPending) return false
            beatPending = false
            val target = machine.celebratingStep ?: return false
            if (!machine.onBeatElapsed(target)) return false
            if (WizardProgress.isLast(target)) finishes++ else enter(WizardProgress.next(target))
            return true
        }
    }

    private val nothing = PermissionStatus()

    @Test
    fun `grant while away advances one step after the beat`() {
        val h = Harness(WizardSteps.FILTER, nothing)

        h.resume(nothing)
        assertEquals(WizardSteps.FILTER, h.step)
        assertFalse("no flip yet, no beat", h.beatPending)
        assertFalse(h.beatElapsed())

        h.resume(PermissionStatus(filterService = true))
        assertEquals("the beat shows the step it came from", WizardSteps.FILTER, h.machine.celebratingStep)
        assertEquals("nothing moves before the beat elapses", WizardSteps.FILTER, h.step)

        assertTrue(h.beatElapsed())
        assertEquals(WizardSteps.BLOCK, h.step)
        assertNull(h.machine.celebratingStep)
        assertEquals(0, h.finishes)
    }

    @Test
    fun `return without granting stays on the step`() {
        val h = Harness(WizardSteps.FILTER, nothing)

        h.resume(nothing)
        h.resume(PermissionStatus(blockService = true, overlay = true, batteryExempt = true))

        assertEquals(WizardSteps.FILTER, h.step)
        assertFalse(h.beatPending)
        assertNull(h.machine.celebratingStep)
        assertFalse(h.beatElapsed())
    }

    @Test
    fun `double resume during the beat advances exactly once`() {
        val h = Harness(WizardSteps.FILTER, nothing)
        val granted = PermissionStatus(filterService = true)

        h.resume(granted)
        h.resume(granted)
        h.resume(granted)
        assertTrue(h.beatElapsed())
        assertEquals(WizardSteps.BLOCK, h.step)

        // A stale timer or a late resume cannot move the step again.
        assertFalse(h.beatElapsed())
        h.resume(granted)
        assertEquals(WizardSteps.BLOCK, h.step)
        assertEquals(0, h.finishes)
    }

    @Test
    fun `leaving the step manually disarms the pending beat`() {
        val h = Harness(WizardSteps.FILTER, nothing)

        h.resume(PermissionStatus(filterService = true))
        h.manualNext()

        assertEquals(WizardSteps.BLOCK, h.step)
        assertFalse("a beat already left behind must not fire", h.beatElapsed())
        assertEquals(WizardSteps.BLOCK, h.step)
    }

    @Test
    fun `block step is optional but still self-advances when enabled`() {
        val h = Harness(WizardSteps.BLOCK, nothing)

        h.resume(nothing)
        h.resume(PermissionStatus(blockService = true))
        assertTrue(h.beatElapsed())
        assertEquals(WizardSteps.PERMISSIONS, h.step)
    }

    @Test
    fun `last step needs both permissions and then auto-finishes`() {
        val h = Harness(WizardSteps.PERMISSIONS, nothing)

        h.resume(PermissionStatus(overlay = true))
        assertFalse("one of two is not the step's condition", h.beatPending)
        assertEquals(WizardSteps.PERMISSIONS, h.step)

        h.resume(PermissionStatus(overlay = true, batteryExempt = true))
        assertTrue(h.beatElapsed())
        assertEquals(1, h.finishes)
        assertEquals(WizardSteps.PERMISSIONS, h.step)
        assertNull(h.machine.celebratingStep)
    }

    @Test
    fun `a step already satisfied on arrival never skips itself`() {
        val already = PermissionStatus(filterService = true, overlay = true, batteryExempt = true)
        val h = Harness(WizardSteps.FILTER, already)

        h.resume(already)
        assertFalse(h.beatPending)
        assertFalse(h.beatElapsed())
        assertEquals(WizardSteps.FILTER, h.step)
    }

    @Test
    fun `steps with no readable condition stay manual`() {
        val granted = PermissionStatus(
            filterService = true,
            blockService = true,
            overlay = true,
            batteryExempt = true,
        )

        for (step in listOf(WizardSteps.WELCOME, WizardSteps.RESTRICTED)) {
            val h = Harness(step, granted)
            h.resume(granted)
            assertFalse("step $step must not auto-advance", h.beatPending)
            assertEquals(step, h.step)
        }
    }

    @Test
    fun `beat is short enough to read and long enough to see`() {
        assertTrue(WizardAutoAdvance.BEAT_MS in 500L..800L)
    }
}
