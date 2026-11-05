package dev.k3vinb5.freesocial.block

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires at the nightly-lock boundary (SPEC §2.3).
 *
 * Re-arms the next boundary, then tells the accessibility services to re-check the app currently
 * in front. The services also recompute on every event; this is the "even if nothing happens" path.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        try {
            ScheduleAlarmScheduler.scheduleNow(app)
            app.sendBroadcast(
                Intent(ScheduleAlarmScheduler.ACTION_SCHEDULE_CHANGED).setPackage(app.packageName),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "boundary handling failed", t)
        }
    }

    private companion object {
        const val TAG = "ScheduleAlarmReceiver"
    }
}
