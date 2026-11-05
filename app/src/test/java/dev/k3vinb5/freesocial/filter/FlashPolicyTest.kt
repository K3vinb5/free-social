package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escape-flash rate limit (2026-11-05): one flash per 3 s per package. Rapid follow-up
 * escapes of the same episode must not stack, and a different app's escape is independent.
 */
class FlashPolicyTest {

    @Test
    fun `first escape flashes`() {
        val policy = FlashPolicy()
        assertTrue(policy.shouldFlash("com.instagram.android", 1_000L))
    }

    @Test
    fun `second escape within three seconds is suppressed`() {
        val policy = FlashPolicy()
        assertTrue(policy.shouldFlash("com.instagram.android", 1_000L))
        assertFalse(policy.shouldFlash("com.instagram.android", 2_000L))
        // Right at the edge: still inside the window.
        assertFalse(policy.shouldFlash("com.instagram.android", 3_999L))
    }

    @Test
    fun `after three seconds the same package flashes again`() {
        val policy = FlashPolicy()
        assertTrue(policy.shouldFlash("com.instagram.android", 1_000L))
        assertTrue(policy.shouldFlash("com.instagram.android", 4_000L))
        // The window restarts from the flash that was actually shown.
        assertFalse(policy.shouldFlash("com.instagram.android", 5_000L))
        assertTrue(policy.shouldFlash("com.instagram.android", 7_000L))
    }

    @Test
    fun `suppressed calls do not refresh the window`() {
        val policy = FlashPolicy()
        assertTrue(policy.shouldFlash("com.google.android.youtube", 0L))
        assertFalse(policy.shouldFlash("com.google.android.youtube", 2_900L))
        assertTrue(policy.shouldFlash("com.google.android.youtube", 3_000L))
    }

    @Test
    fun `packages are independent`() {
        val policy = FlashPolicy()
        assertTrue(policy.shouldFlash("com.instagram.android", 1_000L))
        assertTrue(policy.shouldFlash("com.zhiliaoapp.musically", 1_000L))
        assertTrue(policy.shouldFlash("com.google.android.youtube", 1_000L))
        assertFalse(policy.shouldFlash("com.instagram.android", 1_500L))
        assertFalse(policy.shouldFlash("com.zhiliaoapp.musically", 1_500L))
    }
}
