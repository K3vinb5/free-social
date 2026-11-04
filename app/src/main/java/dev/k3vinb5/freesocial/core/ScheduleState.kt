package dev.k3vinb5.freesocial.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Bedtime/wake for one weekday, as minutes from local midnight.
 * A window opens at [bedtimeMinutes] on its weekday and closes at [wakeMinutes]
 * on that day (when wake is later than bedtime) or on the next day (overnight window).
 */
data class DaySchedule(
    val bedtimeMinutes: Int,
    val wakeMinutes: Int,
) {
    init {
        require(bedtimeMinutes in 0 until MINUTES_PER_DAY) { "bedtimeMinutes out of range: $bedtimeMinutes" }
        require(wakeMinutes in 0 until MINUTES_PER_DAY) { "wakeMinutes out of range: $wakeMinutes" }
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/**
 * Nightly lock window, computed on demand in the zone carried by the caller's [ZonedDateTime].
 *
 * The window for weekday D runs from `bedtime(D) - windDownMinutes` to
 * `wake(D) + morningLockMinutes`. Weekdays are 1 = Monday .. 7 = Sunday; missing days fall back to
 * the defaults (22:30 / 07:00). Whether the feature is enabled at all is the caller's concern.
 */
class ScheduleState(
    private val days: Map<Int, DaySchedule> = emptyMap(),
    val windDownMinutes: Int = DEFAULT_WIND_DOWN_MINUTES,
    val morningLockMinutes: Int = DEFAULT_MORNING_LOCK_MINUTES,
) {
    init {
        require(windDownMinutes >= 0) { "windDownMinutes must be >= 0" }
        require(morningLockMinutes >= 0) { "morningLockMinutes must be >= 0" }
    }

    fun daySchedule(weekday: Int): DaySchedule =
        days[weekday] ?: DaySchedule(DEFAULT_BEDTIME_MINUTES, DEFAULT_WAKE_MINUTES)

    fun isLocked(now: ZonedDateTime): Boolean {
        val today = now.toLocalDate()
        return isInside(now, today.minusDays(1)) || isInside(now, today)
    }

    /** The next instant at which [isLocked] flips, always strictly after [now]. */
    fun nextBoundary(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate()
        var next: ZonedDateTime? = null
        for (offset in -1L..1L) {
            val day = today.plusDays(offset)
            for (candidate in listOf(windowStart(day, now.zone), windowEnd(day, now.zone))) {
                if (candidate.isAfter(now) && (next == null || candidate.isBefore(next))) next = candidate
            }
        }
        return next ?: error("no lock boundary found")
    }

    private fun isInside(now: ZonedDateTime, lockDay: LocalDate): Boolean =
        !now.isBefore(windowStart(lockDay, now.zone)) && now.isBefore(windowEnd(lockDay, now.zone))

    private fun windowStart(lockDay: LocalDate, zone: ZoneId): ZonedDateTime {
        val startMinutes = daySchedule(lockDay.dayOfWeek.value).bedtimeMinutes - windDownMinutes
        return wallClock(lockDay, startMinutes, zone)
    }

    private fun windowEnd(lockDay: LocalDate, zone: ZoneId): ZonedDateTime {
        val schedule = daySchedule(lockDay.dayOfWeek.value)
        val endDay = if (schedule.wakeMinutes > schedule.bedtimeMinutes) lockDay else lockDay.plusDays(1)
        return wallClock(endDay, schedule.wakeMinutes + morningLockMinutes, zone)
    }

    /** Wall-clock arithmetic: minutes from local midnight on [day], resolved in [zone] across DST gaps. */
    private fun wallClock(day: LocalDate, minutes: Int, zone: ZoneId): ZonedDateTime =
        day.atStartOfDay().plusMinutes(minutes.toLong()).atZone(zone)

    companion object {
        const val DEFAULT_BEDTIME_MINUTES = 22 * 60 + 30
        const val DEFAULT_WAKE_MINUTES = 7 * 60
        const val DEFAULT_WIND_DOWN_MINUTES = 30
        const val DEFAULT_MORNING_LOCK_MINUTES = 30
    }
}
