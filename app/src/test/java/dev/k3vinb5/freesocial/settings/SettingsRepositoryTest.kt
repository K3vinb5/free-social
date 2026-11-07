package dev.k3vinb5.freesocial.settings

import dev.k3vinb5.freesocial.core.DaySchedule
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    private class InMemoryPrefs : PrefsStore {
        val booleans = mutableMapOf<String, Boolean>()
        val ints = mutableMapOf<String, Int>()
        val longs = mutableMapOf<String, Long>()
        val strings = mutableMapOf<String, String>()

        override fun getBoolean(key: String, default: Boolean) = booleans[key] ?: default
        override fun putBoolean(key: String, value: Boolean) {
            booleans[key] = value
        }

        override fun getInt(key: String, default: Int) = ints[key] ?: default
        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun getLong(key: String, default: Long) = longs[key] ?: default
        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }

        override fun getString(key: String) = strings[key]
        override fun putString(key: String, value: String) {
            strings[key] = value
        }
    }

    /** Same store, but notifies observers like SharedPreferences does, so cross-instance
     *  propagation can be tested without Android. */
    private class NotifyingPrefs : PrefsStore {
        private val values = mutableMapOf<String, Any>()
        private val listeners = mutableListOf<() -> Unit>()

        private fun notifyListeners() = listeners.toList().forEach { it() }

        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value; notifyListeners() }
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { values[key] = value; notifyListeners() }
        override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
        override fun putLong(key: String, value: Long) { values[key] = value; notifyListeners() }
        override fun getString(key: String) = values[key] as? String
        override fun putString(key: String, value: String) { values[key] = value; notifyListeners() }

        override fun observe(onChange: () -> Unit): AutoCloseable {
            listeners += onChange
            return AutoCloseable { listeners -= onChange }
        }
    }

    private val zone = ZoneId.of("Europe/Amsterdam")

    /** Wed 4 Nov 2026, 12:00 local. */
    private fun noon(day: Int = 4, hour: Int = 12, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)

    @Test
    fun `defaults match the design brief`() {
        val settings = SettingsRepository(InMemoryPrefs())

        assertTrue(settings.filteringEnabled)
        assertEquals(AppMode.FILTER, settings.modeFor(AppTarget.INSTAGRAM))
        assertEquals(AppMode.FILTER, settings.modeFor(AppTarget.YOUTUBE))
        assertEquals(AppMode.FILTER, settings.modeFor(AppTarget.TIKTOK))

        assertTrue(settings.surfaceEnabled(AppTarget.INSTAGRAM, "hide_feed_reels"))
        assertTrue(settings.surfaceEnabled(AppTarget.INSTAGRAM, "hide_reels_tab"))
        assertTrue(settings.surfaceEnabled(AppTarget.YOUTUBE, "hide_shorts_shelf"))
        assertTrue(settings.surfaceEnabled(AppTarget.YOUTUBE, "shorts_count_budget"))
        assertTrue(settings.surfaceEnabled(AppTarget.TIKTOK, "videos_count_budget"))
        assertTrue(settings.surfaceEnabled(AppTarget.TIKTOK, "back_when_spent"))

        assertEquals(20, settings.budgetLimitMinutes)
        assertEquals(DaySchedule(1350, 420), settings.daySchedule(1))
        assertEquals(DaySchedule(1350, 420), settings.daySchedule(7))
        // Every default day clears the six-hour floor (510 min for 22:30–07:00).
        assertEquals(510, settings.daySchedule(1).windowMinutes)
        for (weekday in 1..7) {
            assertTrue(settings.daySchedule(weekday).meetsMinimum)
        }
    }

    @Test
    fun `writes persist through the store`() {
        val store = InMemoryPrefs()
        val first = SettingsRepository(store)
        first.updateFiltering(false)
        first.setMode(AppTarget.YOUTUBE, AppMode.BLOCK)
        first.setSurfaceEnabled(AppTarget.YOUTUBE, "hide_shorts_shelf", false)
        first.updateBudgetLimit(45)
        assertTrue(first.setBedtimeMinutes(5, 1290))
        assertTrue(first.setWakeMinutes(5, 9 * 60))

        val second = SettingsRepository(store)
        assertFalse(second.filteringEnabled)
        assertEquals(AppMode.BLOCK, second.modeFor(AppTarget.YOUTUBE))
        assertFalse(second.surfaceEnabled(AppTarget.YOUTUBE, "hide_shorts_shelf"))
        assertEquals(45, second.budgetLimitMinutes)
        assertEquals(DaySchedule(1290, 9 * 60), second.daySchedule(5))
        assertEquals(11 * 60 + 30, second.daySchedule(5).windowMinutes)
    }

    @Test
    fun `app modes round-trip and unknown values fall back to filter`() {
        val store = InMemoryPrefs()
        val settings = SettingsRepository(store)
        for (mode in AppMode.entries) {
            settings.setMode(AppTarget.TIKTOK, mode)
            assertEquals(mode, settings.modeFor(AppTarget.TIKTOK))
            assertEquals(mode.storageValue, store.strings[SettingsRepository.modeKey(AppTarget.TIKTOK)])
        }
        store.strings[SettingsRepository.modeKey(AppTarget.INSTAGRAM)] = "bogus"
        assertEquals(AppMode.FILTER, SettingsRepository(store).modeFor(AppTarget.INSTAGRAM))
    }

    @Test
    fun `unknown surface keys are ignored`() {
        val settings = SettingsRepository(InMemoryPrefs())
        settings.setSurfaceEnabled(AppTarget.INSTAGRAM, "not_a_surface", false)
        assertTrue(settings.surfaceEnabled(AppTarget.INSTAGRAM, "not_a_surface"))
    }

    @Test
    fun `budget counts today's used seconds and resets on a new date`() {
        val settings = SettingsRepository(InMemoryPrefs())
        val today = noon()
        assertEquals(20, settings.remainingMinutesCeil(today))

        settings.recordBudgetSeconds(420, today)
        assertEquals(420, settings.usedSecondsToday(today))
        assertEquals(13, settings.remainingMinutesCeil(today))
        assertEquals("2026-11-04", settings.budgetDate)

        assertEquals(0, settings.usedSecondsToday(today.plusDays(1)))
        assertEquals(20, settings.remainingMinutesCeil(today.plusDays(1)))
    }

    @Test
    fun `budget entry after midnight starts a fresh counter`() {
        val settings = SettingsRepository(InMemoryPrefs())
        settings.recordBudgetSeconds(600, noon())
        settings.recordBudgetSeconds(120, noon(day = 5))
        assertEquals(120, settings.usedSecondsToday(noon(day = 5)))
        assertEquals("2026-11-05", settings.budgetDate)
    }

    @Test
    fun `budget limit is clamped and drives the remaining minutes`() {
        val settings = SettingsRepository(InMemoryPrefs())
        settings.updateBudgetLimit(500)
        assertEquals(SettingsRepository.MAX_BUDGET, settings.budgetLimitMinutes)
        settings.updateBudgetLimit(-10)
        assertEquals(SettingsRepository.MIN_BUDGET, settings.budgetLimitMinutes)
        assertEquals(0, settings.remainingMinutesCeil(noon()))
    }

    @Test
    fun `default nightly window is exactly 22 30 to 07 00`() {
        val settings = SettingsRepository(InMemoryPrefs())
        assertEquals(LockWindow(22 * 60 + 30, 7 * 60), settings.tonightLockWindow(noon()))
        // During the lock (23:00) the active window is tonight's.
        assertEquals(LockWindow(1350, 420), settings.tonightLockWindow(noon(hour = 23)))
        // Early morning still belongs to last night's window.
        assertEquals(LockWindow(1350, 420), settings.tonightLockWindow(noon(day = 5, hour = 2)))
        // Midday after waking previews the next window that starts tonight.
        assertEquals(LockWindow(1350, 420), settings.tonightLockWindow(noon(day = 5, hour = 9)))
    }

    @Test
    fun `friday override changes the previewed window`() {
        val settings = SettingsRepository(InMemoryPrefs())
        assertTrue(settings.setBedtimeMinutes(5, 23 * 60 + 30))
        assertTrue(settings.setWakeMinutes(5, 8 * 60))

        // 23:30 -> 08:00, no expansion on either end.
        assertEquals(LockWindow(23 * 60 + 30, 8 * 60), settings.tonightLockWindow(noon(day = 6)))
    }

    @Test
    fun `schedule state flips locked at the computed window`() {
        val settings = SettingsRepository(InMemoryPrefs())
        assertFalse(settings.isLocked(noon(hour = 21)))
        assertTrue(settings.isLocked(noon(hour = 22, minute = 30)))
        assertTrue(settings.isLocked(noon(day = 5, hour = 6, minute = 59)))
        assertFalse(settings.isLocked(noon(day = 5, hour = 7, minute = 0)))
    }

    @Test
    fun `a window below six hours cannot be saved`() {
        val store = InMemoryPrefs()
        val settings = SettingsRepository(store)
        val before = settings.daySchedule(1)

        // 22:30 -> 04:29 is 5 h 59 m: rejected, nothing written.
        assertFalse(settings.setWakeMinutes(1, 4 * 60 + 29))
        assertEquals(before, settings.daySchedule(1))
        assertFalse(store.ints.containsKey(SettingsRepository.wakeKey(1)))

        // A bedtime that would leave less than six hours is rejected the same way.
        assertFalse(settings.setBedtimeMinutes(1, 1 * 60 + 1))
        assertEquals(before, settings.daySchedule(1))

        // Exactly six hours is the floor and saves.
        assertTrue(settings.setWakeMinutes(1, 4 * 60 + 30))
        assertEquals(6 * 60, settings.daySchedule(1).windowMinutes)
        assertEquals(4 * 60 + 30, store.ints[SettingsRepository.wakeKey(1)])

        // One minute under the floor stays rejected.
        assertFalse(settings.setWakeMinutes(1, 4 * 60 + 29))
        assertEquals(6 * 60, settings.daySchedule(1).windowMinutes)
    }

    @Test
    fun `per-day override floors are enforced on every weekday`() {
        val settings = SettingsRepository(InMemoryPrefs())
        for (weekday in 1..7) {
            assertFalse(settings.setBedtimeMinutes(weekday, 3 * 60))
            assertFalse(settings.setWakeMinutes(weekday, 23 * 60))
            assertTrue(settings.daySchedule(weekday).meetsMinimum)
            assertEquals(DaySchedule(1350, 420), settings.daySchedule(weekday))
        }
    }

    @Test
    fun `the schedule is always on - no pref can disable it`() {
        val store = InMemoryPrefs()
        // Old installs may still carry these; they must be ignored, not crash.
        store.booleans[SettingsRepository.LEGACY_KEY_SCHEDULE_ENABLED] = false
        store.ints[SettingsRepository.LEGACY_KEY_WIND_DOWN] = 120
        store.ints[SettingsRepository.LEGACY_KEY_MORNING_LOCK] = 120
        val settings = SettingsRepository(store)

        assertTrue(settings.isLocked(noon(hour = 23)))
        assertEquals(LockWindow(1350, 420), settings.tonightLockWindow(noon()))
        assertEquals(510, settings.daySchedule(4).windowMinutes)
    }

    @Test
    fun `stored sub-six-hour day migrates to the default night`() {
        val store = InMemoryPrefs()
        store.ints[SettingsRepository.bedtimeKey(1)] = 22 * 60      // 22:00
        store.ints[SettingsRepository.wakeKey(1)] = 23 * 60         // 23:00 — one hour
        val settings = SettingsRepository(store)

        assertEquals(DaySchedule(1350, 420), settings.daySchedule(1))
        // The other days were never written and still read as the default.
        assertEquals(DaySchedule(1350, 420), settings.daySchedule(2))
    }

    @Test
    fun `clock formatting wraps past midnight`() {
        assertEquals("22:30", SettingsRepository.formatClock(1350))
        assertEquals("07:00", SettingsRepository.formatClock(420))
        assertEquals("00:00", SettingsRepository.formatClock(1440))
        assertEquals("23:00", SettingsRepository.formatClock(-60))
    }

    // --- D10/D11: cross-instance propagation (UI writes must reach the engine) ------------

    @Test
    fun `same-process writes keep a second instance in lock-step`() {
        val store = NotifyingPrefs()
        val ui = SettingsRepository(store)
        val engine = SettingsRepository(store) // e.g. FilterService's instance at connect

        assertEquals(AppMode.FILTER, engine.modeFor(AppTarget.INSTAGRAM))

        ui.setMode(AppTarget.INSTAGRAM, AppMode.ALLOW)
        assertEquals(AppMode.ALLOW, engine.modeFor(AppTarget.INSTAGRAM))

        ui.setMode(AppTarget.INSTAGRAM, AppMode.BLOCK)
        assertEquals(AppMode.BLOCK, engine.modeFor(AppTarget.INSTAGRAM))

        ui.setSurfaceEnabled(AppTarget.YOUTUBE, "hide_shorts_shelf", false)
        assertFalse(engine.surfaceEnabled(AppTarget.YOUTUBE, "hide_shorts_shelf"))

        ui.updateFiltering(false)
        assertFalse(engine.filteringEnabled)

        assertTrue(ui.setBedtimeMinutes(3, 23 * 60))
        assertEquals(23 * 60, engine.daySchedule(3).bedtimeMinutes)
        assertTrue(engine.isLocked(noon(hour = 23)))
        assertFalse(engine.isLocked(noon(hour = 22, minute = 59)))
    }

    @Test
    fun `budget limit written by one instance is seen by another`() {
        val store = NotifyingPrefs()
        val ui = SettingsRepository(store)
        val engine = SettingsRepository(store)
        val now = noon()

        assertEquals(20, engine.remainingMinutesCeil(now))
        ui.updateBudgetLimit(0)
        assertEquals(0, engine.remainingMinutesCeil(now))
        assertEquals(0, engine.budgetLimitMinutes)
    }

    @Test
    fun `budget usage written by the engine is seen by the hub instance`() {
        val store = NotifyingPrefs()
        val ui = SettingsRepository(store)
        val engine = SettingsRepository(store)
        val now = noon()

        engine.recordBudgetSeconds(20 * 60 - 60, now)
        assertEquals(1, ui.remainingMinutesCeil(now))
        engine.recordBudgetSeconds(60, now)
        assertEquals(0, ui.remainingMinutesCeil(now))
    }
}
