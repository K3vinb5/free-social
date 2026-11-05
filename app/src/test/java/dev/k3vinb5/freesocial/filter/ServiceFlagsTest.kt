package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D8 layer (a): connect-time re-assertion ORs the three capture flags in without touching the
 * event-type/timeout flags the XML already resolved.
 */
class ServiceFlagsTest {

    private val includeNotImportantViews = 0x02
    private val reportViewIds = 0x10
    private val retrieveInteractiveWindows = 0x40

    @Test
    fun `capture flags are the three AccessibilityServiceInfo flags`() {
        assertEquals(
            includeNotImportantViews or reportViewIds or retrieveInteractiveWindows,
            ServiceFlags.CAPTURE_FLAGS,
        )
    }

    @Test
    fun `re-assertion preserves existing flags and adds the missing capture flags`() {
        val existing = 0x01 // any non-capture bit the XML already resolved must survive untouched
        assertEquals(existing or ServiceFlags.CAPTURE_FLAGS, ServiceFlags.withCapture(existing))
        assertEquals(ServiceFlags.CAPTURE_FLAGS, ServiceFlags.missing(existing))
    }

    @Test
    fun `re-assertion is idempotent`() {
        val full = ServiceFlags.withCapture(0)
        assertEquals(full, ServiceFlags.withCapture(full))
        assertEquals(0, ServiceFlags.missing(full))
        assertTrue(ServiceFlags.describe(full).contains("reportViewIds"))
    }
}
