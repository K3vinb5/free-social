package dev.k3vinb5.freesocial.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The F1–F5 step chain must reach the last step (and therefore "Finish setup"). */
class WizardProgressTest {

    @Test
    fun `next advances one step at a time until the last step`() {
        var step = 0
        val visited = mutableListOf(step)
        while (!WizardProgress.isLast(step)) {
            step = WizardProgress.next(step)
            visited += step
        }
        assertEquals(listOf(0, 1, 2, 3, 4), visited)
        assertEquals(WizardProgress.LAST_STEP, step)
    }

    @Test
    fun `next clamps at the last step and back clamps at welcome`() {
        assertEquals(WizardProgress.LAST_STEP, WizardProgress.next(WizardProgress.LAST_STEP))
        assertEquals(0, WizardProgress.back(0))
        assertFalse(WizardProgress.isLast(3))
        assertTrue(WizardProgress.isLast(4))
        // Out-of-range values cannot escape the wizard bounds.
        assertEquals(0, WizardProgress.back(-3))
        assertEquals(WizardProgress.LAST_STEP, WizardProgress.next(99))
    }
}
