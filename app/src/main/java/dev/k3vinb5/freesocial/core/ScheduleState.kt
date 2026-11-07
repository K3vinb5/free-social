package dev.k3vinb5.freesocial.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Bedtime/wake for one weekday, as minutes from local midnight.
 * A window opens at [bedtimeMinutes] on its weekday and closes at [wakeMinutes]
 * on that day (when wake is later than bedtime) or on the next day (overnight window).
 *
 * The window is exactly bedtime -> wake: no wind-down or morning-lock margins exist any more.
 * A day whose window is shorter than [MIN_WINDOW_MINUTES] is rejected by [ScheduleState], and the
 * editor/repository refuse to save one — the schedule is meant to force a real night's sleep.
 */
data class DaySchedule(
    val bedtimeMinutes: Int,
    val wakeMinutes: Int,
) {
    init {
        require(bedtimeMinutes in 0 until MINUTES_PER_DAY) { "bedtimeMinutes out of range: $bedtimeMinutes" }
        require(wakeMinutes in 0 until MINUTES_PER_DAY) { "wakeMinutes out of range: $wakeMinutes" }
    }

    /**
     * Locked minutes from bedtime to wake, wrapping past midnight. Equal times mean a full day
     * (the window would otherwise be empty), matching the overnight rule below.
     */
    val windowMinutes: Int
        get() = if (wakeMinutes == bedtimeMinutes) {
            MINUTES_PER_DAY
        } else {
            (wakeMinutes - bedtimeMinutes).mod(MINUTES_PER_DAY)
        }

    /** False for a window shorter than [MIN_WINDOW_MINUTES]; callers must not persist those. */
    val meetsMinimum: Boolean get() = windowMinutes >= MIN_WINDOW_MINUTES

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        /** The floor the user asked for: under six hours is not a night's sleep. */
        const val MIN_WINDOW_MINUTES = 6 * 60
    }
}

private val DEFAULT_DAY = DaySchedule(
    ScheduleState.DEFAULT_BEDTIME_MINUTES,
    ScheduleState.DEFAULT_WAKE_MINUTES,
)

/**
 * Nightly sleep window, computed on demand in the zone carried by the caller's [ZonedDateTime].
 *
 * The window for weekday D runs from `bedtime(D)` to `wake(D)` (next day on wrap). Weekdays are
 * 1 = Monday .. 7 = Sunday; missing days fall back to the defaults (22:30 / 07:00, 8 h 30 m).
 * There is no enable flag: the schedule always applies, and every day must satisfy the
 * [DaySchedule.MIN_WINDOW_MINUTES] floor.
 */
class ScheduleState(
    private val days: Map<Int, DaySchedule> = emptyMap(),
) {
    init {
        days.forEach { (weekday, day) ->
            require(day.meetsMinimum) {
                "weekday $weekday window ${day.windowMinutes} min is below the " +
                    "${DaySchedule.MIN_WINDOW_MINUTES} min minimum"
            }
        }
    }

    fun daySchedule(weekday: Int): DaySchedule = days[weekday] ?: DEFAULT_DAY

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

    /**
     * Wall-clock minutes of the wake that ends the window covering [now], or of the next window to
     * start when none covers it. Lets UI copy ("come back at %1$s") follow the per-day schedule
     * instead of hardcoding a clock time.
     */
    fun nextWakeMinutes(now: ZonedDateTime): Int {
        val today = now.toLocalDate()
        val candidates = listOf(today.minusDays(1), today, today.plusDays(1))
        val lockDay = candidates.firstOrNull { isInside(now, it) }
            ?: candidates.first { windowStart(it, now.zone).isAfter(now) }
        return daySchedule(lockDay.dayOfWeek.value).wakeMinutes
    }

    private fun isInside(now: ZonedDateTime, lockDay: LocalDate): Boolean =
        !now.isBefore(windowStart(lockDay, now.zone)) && now.isBefore(windowEnd(lockDay, now.zone))

    private fun windowStart(lockDay: LocalDate, zone: ZoneId): ZonedDateTime {
        val schedule = daySchedule(lockDay.dayOfWeek.value)
        return wallClock(lockDay, schedule.bedtimeMinutes, zone)
    }

    private fun windowEnd(lockDay: LocalDate, zone: ZoneId): ZonedDateTime {
        val schedule = daySchedule(lockDay.dayOfWeek.value)
        val endDay = if (schedule.wakeMinutes > schedule.bedtimeMinutes) lockDay else lockDay.plusDays(1)
        return wallClock(endDay, schedule.wakeMinutes, zone)
    }

    /** Wall-clock arithmetic: minutes from local midnight on [day], resolved in [zone] across DST gaps. */
    private fun wallClock(day: LocalDate, minutes: Int, zone: ZoneId): ZonedDateTime =
        day.atStartOfDay().plusMinutes(minutes.toLong()).atZone(zone)

    companion object {
        const val DEFAULT_BEDTIME_MINUTES = 22 * 60 + 30
        const val DEFAULT_WAKE_MINUTES = 7 * 60
    }
}
