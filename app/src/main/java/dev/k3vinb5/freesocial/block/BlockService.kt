package dev.k3vinb5.freesocial.block

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.k3vinb5.freesocial.filter.DumpArm
import dev.k3vinb5.freesocial.settings.SettingsRepository
import dev.k3vinb5.freesocial.ui.BlockActivity
import java.time.ZonedDateTime

/**
 * Opt-in whole-app blocking (SPEC §2.3, §4).
 *
 * Listens for window-state changes only, resolves the foreground package, runs the shared
 * [BlockGate], and shows [BlockActivity] with `NEW_TASK|CLEAR_TASK`. Every failure is swallowed:
 * this service is a convenience layer and must never crash.
 */
class BlockService : AccessibilityService() {

    private val gate = BlockGate()
    private var settings: SettingsRepository? = null
    private var receiverRegistered = false
    private var lastSeenPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())

    /** D16: expiry is not an accessibility event, so the gate needs its own wake-up. */
    private val recheck = Runnable {
        Log.i(TAG, "capture pause re-check: evaluating $lastSeenPackage")
        handlePackage(lastSeenPackage)
    }

    private val capturePause = CapturePauseSchedule(
        remainingMillis = { now -> DumpArm.remainingMillis(now) },
        now = { DumpArm.nowMillis() },
    )

    private val scheduleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // The boundary flipped: the app we last saw may still be in front.
            handlePackage(lastSeenPackage)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            settings = SettingsRepository.from(this)
            registerScheduleReceiver()
            // Best-effort v1 wiring: keep the boundary alarm armed whenever this service comes up.
            ScheduleAlarmScheduler.scheduleNow(this)
        } catch (t: Throwable) {
            Log.w(TAG, "onServiceConnected failed", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val pkg = event.packageName?.toString() ?: return
            lastSeenPackage = pkg
            handlePackage(pkg)
        } catch (t: Throwable) {
            Log.w(TAG, "onAccessibilityEvent failed", t)
        }
    }

    private fun handlePackage(packageName: String?) {
        try {
            // Capture pause: a pending arm or a running guided capture needs the target app on
            // screen and un-blocked, otherwise the dump records the block screen instead of the
            // surface. D16: the pause must end at the arm's own deadline even with zero further
            // events, so instead of returning silently we schedule one re-evaluation then.
            when (val step = capturePause.step()) {
                is CapturePauseSchedule.Step.RecheckIn -> {
                    scheduleRecheck(step.delayMs)
                    return
                }
                CapturePauseSchedule.Step.Evaluate -> handler.removeCallbacks(recheck)
            }
            val repo = settings ?: return
            val target = BlockGate.resolveTarget(packageName) ?: return
            val decision = gate.decide(
                packageName = packageName,
                target = target,
                mode = repo.modeFor(target),
                deviceInteractive = isInteractive(),
                keyguardLocked = isKeyguardLocked(),
                scheduleLocked = repo.isLocked(ZonedDateTime.now()),
                nowMillis = SystemClock.elapsedRealtime(),
                allowedPackages = launcherPackages,
            )
            if (decision is BlockDecision.Launch) {
                launchBlockScreen(decision)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "gate evaluation failed", t)
        }
    }

    private fun scheduleRecheck(delayMs: Long) {
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, delayMs)
    }

    private fun launchBlockScreen(decision: BlockDecision.Launch) {
        val intent = Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(BlockActivity.EXTRA_PLATFORM, decision.platform)
            // D14: the kind, not pre-rendered text — BlockActivity picks the body from it.
            .putExtra(BlockActivity.EXTRA_REASON, decision.reason.name)
            .putExtra(BlockActivity.EXTRA_PACKAGE_NAME, decision.packageName)
        startActivity(intent)
    }

    private fun isInteractive(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    private fun isKeyguardLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked ?: false

    /** Home activity package(s); resolved once per service process. */
    private val launcherPackages: Set<String> by lazy {
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
                ?.let { setOf(it) }
                ?: emptySet()
        }.getOrDefault(emptySet())
    }

    private fun registerScheduleReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ScheduleAlarmScheduler.ACTION_SCHEDULE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scheduleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(scheduleReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onInterrupt() {
        // Nothing to stop: every evaluation is synchronous.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        handler.removeCallbacks(recheck)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(scheduleReceiver) }
            receiverRegistered = false
        }
        gate.reset()
        settings = null
        lastSeenPackage = null
    }

    companion object {
        private const val TAG = "BlockService"
    }
}

/**
 * D16 — what [BlockService] does on an event that lands while a capture owns the device.
 *
 * [Step.Evaluate] means nothing is armed (or the arm just expired): run the gate normally.
 * [Step.RecheckIn] means wait exactly until the arm's deadline and run the gate then, so the block
 * screen comes back for the app the user is sitting on even when no further accessibility event
 * arrives. Pure and clock-injected; `CapturePauseScheduleTest` drives the zero-event expiry case.
 */
internal class CapturePauseSchedule(
    private val remainingMillis: (Long) -> Long?,
    private val now: () -> Long,
) {
    sealed interface Step {
        data object Evaluate : Step

        data class RecheckIn(val delayMs: Long) : Step
    }

    fun step(): Step {
        val remaining = remainingMillis(now()) ?: return Step.Evaluate
        return Step.RecheckIn(remaining.coerceAtLeast(MIN_RECHECK_MS))
    }

    companion object {
        /** A zero-delay post would spin if the deadline equals the current millisecond. */
        const val MIN_RECHECK_MS = 1L
    }
}
