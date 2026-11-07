package dev.k3vinb5.freesocial.core

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The UI must follow the device locale's clock and date conventions, not a hardcoded 24-hour one. */
class LocaleFormatTest {

    private val lisbon = ZoneId.of("Europe/Lisbon")

    private fun normalized(value: String): String = value.replace('\u202F', ' ')

    @Test
    fun `a 12-hour locale gets an AM-PM clock`() {
        assertEquals("10:30 PM", normalized(LocaleFormat.clock(22 * 60 + 30, Locale.US)))
        assertEquals("7:00 AM", normalized(LocaleFormat.clock(7 * 60, Locale.US)))
    }

    @Test
    fun `24-hour locales keep 24-hour clocks`() {
        assertEquals("22:30", LocaleFormat.clock(22 * 60 + 30, Locale.forLanguageTag("pt-PT")))
        assertEquals("22:30", LocaleFormat.clock(22 * 60 + 30, Locale.forLanguageTag("es-ES")))
        assertEquals("07:00", LocaleFormat.clock(7 * 60, Locale.forLanguageTag("pt-PT")))
    }

    @Test
    fun `clock formatting wraps past midnight`() {
        assertEquals("00:00", LocaleFormat.clock(24 * 60, Locale.forLanguageTag("pt-PT")))
        assertEquals("23:00", LocaleFormat.clock(-60, Locale.forLanguageTag("pt-PT")))
    }

    @Test
    fun `imported timestamps follow the locale time convention`() {
        val instant = ZonedDateTime.of(2026, 11, 7, 19, 30, 0, 0, lisbon).toInstant().toEpochMilli()

        val portuguese = LocaleFormat.dateTime(instant, lisbon, Locale.forLanguageTag("pt-PT"))
        assertTrue("pt-PT is a 24-hour locale: $portuguese", portuguese.contains("19:30"))
        assertFalse("pt-PT must not use AM/PM: $portuguese", portuguese.contains("PM"))

        val english = LocaleFormat.dateTime(instant, lisbon, Locale.US)
        assertTrue("en-US is a 12-hour locale: $english", english.contains("PM"))
        assertTrue("en-US shows the month name: $english", english.contains("Nov"))
    }
}
