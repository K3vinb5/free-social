package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D5: a failed connect-time init must not leave the engine permanently dead — the next event
 * retries, at most every 5 s, and the engine comes up as soon as the dependency is loadable.
 */
class EngineBootstrapTest {

    @Test
    fun `lazy re-init retries after failure and succeeds once the dependency is injectable`() {
        var available = false
        var loads = 0
        val bootstrap = EngineBootstrap(
            load = {
                loads++
                if (available) "engine" else null
            },
            clock = { 0L },
            retryAfterMs = 5_000L,
        )

        assertNull(bootstrap.current(0L))
        assertEquals(1, loads)
        assertNotNull(bootstrap.lastError)

        // Within the 5 s window: no new attempt, still null.
        assertNull(bootstrap.current(1_000L))
        assertNull(bootstrap.current(4_999L))
        assertEquals(1, loads)

        // The dependency becomes available; the next due attempt heals the engine.
        available = true
        assertEquals("engine", bootstrap.current(5_000L))
        assertEquals(2, loads)
        assertNull(bootstrap.lastError)

        // Once healthy it is cached: no further loader calls.
        assertEquals("engine", bootstrap.current(120_000L))
        assertEquals(2, loads)
    }

    @Test
    fun `a throwing loader is recorded and retried, exactly once per window`() {
        var throwNow = true
        var loads = 0
        val bootstrap = EngineBootstrap<String>(
            load = {
                loads++
                if (throwNow) error("asset missing") else "ok"
            },
            clock = { 0L },
            retryAfterMs = 5_000L,
        )

        assertNull(bootstrap.attempt(0L))
        assertEquals("asset missing", bootstrap.lastError?.message)
        assertNull(bootstrap.attempt(4_000L))
        assertEquals(1, loads)

        throwNow = false
        assertEquals("ok", bootstrap.attempt(5_000L))
        assertEquals(2, loads)
    }

    @Test
    fun `forced attempt bypasses the retry window and returns the cached dependency`() {
        var loads = 0
        val bootstrap = EngineBootstrap(
            load = { loads++; "engine" },
            clock = { 0L },
        )
        assertEquals("engine", bootstrap.attempt(0L, force = true))
        assertEquals("engine", bootstrap.attempt(10L, force = true))
        assertEquals(1, loads)
    }
}

/** D5(c): one log per reason per window, and reasons never mute each other. */
class LogLimiterTest {

    @Test
    fun `one log per window per reason`() {
        val limiter = LogLimiter(windowMs = 30_000L)
        assertTrue(limiter.allow("repo null", 0L))
        assertFalse(limiter.allow("repo null", 1_000L))
        assertFalse(limiter.allow("repo null", 29_999L))
        assertTrue(limiter.allow("repo null", 30_000L))
    }

    @Test
    fun `different reasons have independent windows`() {
        val limiter = LogLimiter(windowMs = 10_000L)
        assertTrue(limiter.allow("not interactive", 0L))
        assertTrue(limiter.allow("no windows", 0L))
        assertFalse(limiter.allow("not interactive", 1L))
        assertTrue(limiter.allow("no windows", 10_000L))
    }

    @Test
    fun `reset clears every reason`() {
        val limiter = LogLimiter(windowMs = 30_000L)
        assertTrue(limiter.allow("repo null", 0L))
        assertFalse(limiter.allow("repo null", 1L))
        limiter.reset()
        assertTrue(limiter.allow("repo null", 2L))
    }
}
