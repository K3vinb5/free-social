package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D7 staleness table: never written → stale, fresh → ok, older than 2 min → stale. */
class EngineHealthTest {

    private val now = 10_000_000L

    @Test
    fun `never written is stale`() {
        assertTrue(EngineHealth.isStale(lastBeatMs = null, nowMs = now))
        assertTrue(EngineHealth.isStale(lastBeatMs = -1L, nowMs = now))
        assertTrue(EngineHealth.isStale(lastBeatMs = 0L, nowMs = now))
    }

    @Test
    fun `a fresh heartbeat is healthy`() {
        assertFalse(EngineHealth.isStale(lastBeatMs = now - 1_000L, nowMs = now))
        assertFalse(EngineHealth.isStale(lastBeatMs = now - EngineHealth.STALE_AFTER_MS, nowMs = now))
        assertFalse(EngineHealth.isStale(lastBeatMs = now, nowMs = now))
    }

    @Test
    fun `three minutes old is stale`() {
        assertTrue(EngineHealth.isStale(lastBeatMs = now - 180_000L, nowMs = now))
        assertTrue(EngineHealth.isStale(lastBeatMs = now - EngineHealth.STALE_AFTER_MS - 1, nowMs = now))
    }

    @Test
    fun `a future beat is a clock jump not a dead engine`() {
        assertFalse(EngineHealth.isStale(lastBeatMs = now + 60_000L, nowMs = now))
    }
}
