package dev.k3vinb5.freesocial.filter

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The inspector <-> service one-shot handshake: arm, capture once, expire after 60 s. */
class DumpArmTest {

    @Before
    fun setUp() = DumpArm.cancel()

    @After
    fun tearDown() = DumpArm.cancel()

    @Test
    fun `arm then capture consumes the one shot`() {
        DumpArm.arm("IG · home feed", now = 1_000)
        assertTrue(DumpArm.isArmed(1_000))
        assertEquals("IG · home feed", DumpArm.armedScreen(1_000))

        assertEquals("IG · home feed", DumpArm.consume(2_000))
        // Auto-cleared after the single capture: a second event must not write again.
        assertFalse(DumpArm.isArmed(2_000))
        assertNull(DumpArm.consume(2_001))
    }

    @Test
    fun `arm expires at sixty seconds`() {
        DumpArm.arm("YT · shorts player", now = 10_000)
        assertTrue(DumpArm.isArmed(10_000 + DumpArm.EXPIRY_MS - 1))
        assertFalse(DumpArm.isArmed(10_000 + DumpArm.EXPIRY_MS))
        assertNull(DumpArm.consume(10_000 + DumpArm.EXPIRY_MS + 1))
    }

    @Test
    fun `capture while unarmed is ignored`() {
        assertFalse(DumpArm.isArmed(0))
        assertNull(DumpArm.consume(0))
        assertNull(DumpArm.armedScreen(0))
    }

    @Test
    fun `rearming replaces the label and restarts the window`() {
        DumpArm.arm("IG · home feed", now = 1_000)
        DumpArm.arm("TT · playback", now = 30_000)
        assertTrue(DumpArm.isArmed(30_000 + DumpArm.EXPIRY_MS - 1))
        assertEquals("TT · playback", DumpArm.consume(30_000))
    }

    @Test
    fun `cancel disarms before any capture`() {
        DumpArm.arm("IG · reels viewer", now = 5_000)
        DumpArm.cancel()
        assertFalse(DumpArm.isArmed(5_000))
    }
}
