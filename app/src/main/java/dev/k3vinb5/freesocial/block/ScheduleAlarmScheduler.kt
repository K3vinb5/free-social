package dev.k3vinb5.freesocial.block

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.k3vinb5.freesocial.settings.SettingsRepository
import java.time.ZonedDateTime

/**
 * Arms the single exact alarm that flips the nightly-lock state at the next boundary (SPEC §2.3).
 *
 * The window itself is always computed on demand by [dev.k3vinb5.freesocial.core.ScheduleState];
 * the alarm only exists so the state change happens "even if nothing else happens". Exact alarms
 * need `SCHEDULE_EXACT_ALARM` on API 31+, which this app does not declare (deliberate, opt-in
 * v1), so the exact call falls back to an inexact allow-while-idle alarm when the system refuses
 * it. Both services also re-evaluate on every accessibility event, so the fallback is a
 * timing-only degradation.
 */
object ScheduleAlarmScheduler {

    /** Local broadcast telling the services a boundary passed. */
    const val ACTION_SCHEDULE_CHANGED = "dev.k3vinb5.freesocial.action.SCHEDULE_CHANGED"

    private const val TAG = "ScheduleAlarm"
    private const val REQUEST_CODE = 7301

    /**
     * Recomputes and persists the next wake-up. Safe to call from anywhere, any number of times;
     * cancels the alarm when the nightly lock is switched off.
     */
    fun scheduleNow(context: Context) {
        try {
            val app = context.applicationContext ?: context
            val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pending = pendingIntent(app)
            val repo = SettingsRepository.from(app)
            if (!repo.scheduleEnabled) {
                manager.cancel(pending)
                return
            }
            val now = ZonedDateTime.now()
            val atMillis = repo.scheduleState().nextBoundary(now).toInstant().toEpochMilli()
            runCatching {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }.onFailure { t ->
                Log.w(TAG, "exact alarm refused; falling back to inexact", t)
                runCatching {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
                }.onFailure { Log.w(TAG, "inexact alarm failed", it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scheduleNow failed", t)
        }
    }

    fun cancel(context: Context) {
        try {
            val app = context.applicationContext ?: context
            val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            manager.cancel(pendingIntent(app))
        } catch (t: Throwable) {
            Log.w(TAG, "cancel failed", t)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ScheduleAlarmReceiver::class.java).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
