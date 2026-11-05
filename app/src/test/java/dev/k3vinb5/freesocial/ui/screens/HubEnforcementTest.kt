package dev.k3vinb5.freesocial.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC §2.4 / acceptance item 7: the hub pill may only claim filtering when the filter service is
 * actually enabled, and a degraded state must carry the persistent banner.
 */
class HubEnforcementTest {

    @Test
    fun `master on plus service on claims filtering on`() {
        val state = hubEnforcement(masterEnabled = true, serviceEnabled = true)
        assertEquals(HubEnforcement.ON, state)
        assertFalse(hubEnforcementDegraded(state))
    }

    @Test
    fun `master on plus service off is paused and shows the banner`() {
        val state = hubEnforcement(masterEnabled = true, serviceEnabled = false)
        assertEquals(HubEnforcement.PAUSED, state)
        assertTrue(hubEnforcementDegraded(state))
    }

    @Test
    fun `master off is off and shows no banner`() {
        val state = hubEnforcement(masterEnabled = false, serviceEnabled = false)
        assertEquals(HubEnforcement.OFF, state)
        assertFalse(hubEnforcementDegraded(state))
        // The master switch wins: a disabled master is not a degraded state.
        assertEquals(HubEnforcement.OFF, hubEnforcement(masterEnabled = false, serviceEnabled = true))
        assertFalse(hubEnforcementDegraded(hubEnforcement(false, true)))
    }
}
