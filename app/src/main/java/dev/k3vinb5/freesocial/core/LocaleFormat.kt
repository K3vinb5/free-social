package dev.k3vinb5.freesocial.core

import java.text.DateFormat
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Locale-aware wall-clock and date formatting, kept out of Android so it is JVM-testable.
 *
 * Every user-facing time goes through here: 12-hour locales get "10:30 PM", 24-hour locales get
 * "22:30", and no screen hardcodes a 24-hour assumption or an English month name.
 */
object LocaleFormat {

    /** Wall-clock label for [minutesPastMidnight], in the locale's own 12/24-hour style. */
    fun clock(minutesPastMidnight: Int, locale: Locale = Locale.getDefault()): String {
        val normalized = Math.floorMod(minutesPastMidnight, MINUTES_PER_DAY)
        val time = LocalTime.of(normalized / 60, normalized % 60)
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .format(time)
    }

    /**
     * Date + time for an epoch instant in [zone], using the locale's medium date and short time
     * (e.g. en-US "Nov 7, 2026, 7:30 PM"; pt-PT "07/11/2026, 19:30").
     */
    fun dateTime(
        millis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        format.timeZone = TimeZone.getTimeZone(zone)
        return format.format(Date(millis))
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
