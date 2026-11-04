package dev.k3vinb5.freesocial.core

import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.ceil

/**
 * One combined daily short-video allowance shared by Instagram, YouTube and TikTok.
 *
 * The counter is a single `{date, usedSeconds}` pair: every surface that counts (IG clips viewer,
 * YT Shorts player, TikTok playback) ticks the same number, and the counter resets when the local
 * date of the supplied clock changes — i.e. at local midnight.
 */
class ShortVideoBudget(
    private val limitMinutes: Int = DEFAULT_LIMIT_MINUTES,
) {
    private var date: LocalDate? = null
    private var usedSeconds: Long = 0L

    init {
        require(limitMinutes >= 0) { "limitMinutes must be >= 0" }
    }

    /** Adds [seconds] of short-video time to today's counter. Non-positive values are ignored. */
    @Synchronized
    fun record(seconds: Long, now: ZonedDateTime) {
        if (seconds <= 0) return
        rollOver(now)
        usedSeconds += seconds
    }

    @Synchronized
    fun usedToday(now: ZonedDateTime): Long {
        rollOver(now)
        return usedSeconds
    }

    @Synchronized
    fun remainingSeconds(now: ZonedDateTime, limitMinutes: Int = this.limitMinutes): Long {
        rollOver(now)
        return (limitMinutes.toLong() * SECONDS_PER_MINUTE - usedSeconds).coerceAtLeast(0L)
    }

    /** True once the allowance is used up — at exactly the limit, not only past it. */
    @Synchronized
    fun isExhausted(now: ZonedDateTime, limitMinutes: Int = this.limitMinutes): Boolean =
        remainingSeconds(now, limitMinutes) == 0L

    /** Remaining allowance rounded up to whole minutes, for "X min left today" copy. */
    @Synchronized
    fun remainingMinutesCeil(now: ZonedDateTime, limitMinutes: Int = this.limitMinutes): Int {
        val seconds = remainingSeconds(now, limitMinutes)
        return ceil(seconds.toDouble() / SECONDS_PER_MINUTE).toInt()
    }

    private fun rollOver(now: ZonedDateTime) {
        val today = now.toLocalDate()
        if (date != today) {
            date = today
            usedSeconds = 0L
        }
    }

    companion object {
        const val DEFAULT_LIMIT_MINUTES = 20
        private const val SECONDS_PER_MINUTE = 60L
    }
}
