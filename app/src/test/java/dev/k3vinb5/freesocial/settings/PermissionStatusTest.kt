package dev.k3vinb5.freesocial.settings

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the wizard/permissions plumbing (waves 2). */
class PermissionStatusTest {

    private val zone = ZoneId.of("Europe/Amsterdam")

    /** Wed 4 Nov 2026, 12:00 local unless overridden. */
    private fun noon(day: Int = 4, hour: Int = 12): ZonedDateTime =
        ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone)

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

    private val pkg = "dev.k3vinb5.freesocial"

    @Test
    fun `first run starts false and finishing it persists`() {
        val store = InMemoryPrefs()
        val first = SettingsRepository(store)
        assertFalse(first.firstRunCompleted)

        first.completeFirstRun()
        assertTrue(first.firstRunCompleted)
        assertTrue(SettingsRepository(store).firstRunCompleted)
        assertEquals(true, store.booleans[SettingsRepository.KEY_FIRST_RUN])
    }

    @Test
    fun `accessibility lookup handles empty and foreign service lists`() {
        assertFalse(SettingsRepository.accessibilityServiceEnabled(null, pkg))
        assertFalse(SettingsRepository.accessibilityServiceEnabled("", pkg))
        assertFalse(SettingsRepository.accessibilityServiceEnabled("com.other/com.other.Svc", pkg))
        // Prefix collision must not count as our package.
        assertFalse(SettingsRepository.accessibilityServiceEnabled("$pkg.evil/com.x.Svc", pkg))
    }

    @Test
    fun `accessibility lookup matches any of our services or one exact component`() {
        val enabled = "com.other/com.other.Svc:$pkg/${SettingsRepository.FILTER_SERVICE_CLASS}"
        assertTrue(SettingsRepository.accessibilityServiceEnabled(enabled, pkg))
        assertTrue(
            SettingsRepository.accessibilityServiceEnabled(
                enabled,
                pkg,
                SettingsRepository.FILTER_SERVICE_CLASS,
            ),
        )
        assertFalse(
            SettingsRepository.accessibilityServiceEnabled(
                enabled,
                pkg,
                SettingsRepository.BLOCK_SERVICE_CLASS,
            ),
        )
        assertTrue(
            SettingsRepository.accessibilityServiceEnabled(
                "$pkg/${SettingsRepository.BLOCK_SERVICE_CLASS}",
                pkg,
                SettingsRepository.BLOCK_SERVICE_CLASS,
            ),
        )
    }

    @Test
    fun `service class constants point at real declared services`() {
        // Pins the constant to the actual class so it cannot drift again
        // (it did: service.FilterService vs filter.FilterService).
        Class.forName(SettingsRepository.FILTER_SERVICE_CLASS)
        // TODO: Class.forName(SettingsRepository.BLOCK_SERVICE_CLASS) once BlockService is declared.
    }

    @Test
    fun `required grants ignore the optional block service`() {
        assertEquals(0, PermissionStatus().grantedRequired)
        assertFalse(PermissionStatus().allRequiredGranted)

        val blockOnly = PermissionStatus(blockService = true)
        assertEquals(0, blockOnly.grantedRequired)
        assertFalse(blockOnly.allRequiredGranted)

        assertEquals(2, PermissionStatus(filterService = true, overlay = true).grantedRequired)
        val full = PermissionStatus(filterService = true, overlay = true, batteryExempt = true)
        assertEquals(3, full.grantedRequired)
        assertTrue(full.allRequiredGranted)
        assertTrue(full.copy(blockService = true).allRequiredGranted)
        // The hub counts all four rows, including the optional block service.
        assertEquals(2, full.copy(blockService = false, batteryExempt = false).grantedCount)
        assertEquals(4, full.copy(blockService = true).grantedCount)
    }

    @Test
    fun `block intent extras resolve to a target app`() {
        assertEquals(AppTarget.INSTAGRAM, AppTarget.fromPlatform("instagram", null))
        assertEquals(AppTarget.YOUTUBE, AppTarget.fromPlatform("YouTube", null))
        assertEquals(AppTarget.TIKTOK, AppTarget.fromPlatform("tt", null))
        assertEquals(AppTarget.TIKTOK, AppTarget.fromPlatform(null, "com.zhiliaoapp.musically"))
        // A package name wins over a stale platform slug.
        assertEquals(
            AppTarget.YOUTUBE,
            AppTarget.fromPlatform("instagram", "com.google.android.youtube"),
        )
        assertNull(AppTarget.fromPlatform(null, null))
        assertNull(AppTarget.fromPlatform("snapchat", "com.snapchat.android"))
    }

    @Test
    fun `disabled nightly lock never locks and offers no preview window`() {
        val settings = SettingsRepository(InMemoryPrefs())
        // 23:00 sits inside the default 22:00–07:30 window.
        assertTrue(settings.isLocked(noon(hour = 23)))
        assertNotNull(settings.tonightLockWindow(noon()))

        settings.updateScheduleEnabled(false)
        assertFalse(settings.isLocked(noon(hour = 23)))
        assertFalse(settings.isLocked(noon(day = 5, hour = 2)))
        assertNull(settings.tonightLockWindow(noon()))

        settings.updateScheduleEnabled(true)
        assertTrue(settings.isLocked(noon(hour = 23)))
        assertEquals(LockWindow(1320, 450), settings.tonightLockWindow(noon()))
    }
}
