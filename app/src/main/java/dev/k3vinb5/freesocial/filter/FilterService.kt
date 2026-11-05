package dev.k3vinb5.freesocial.filter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.k3vinb5.freesocial.core.Action
import dev.k3vinb5.freesocial.core.ShortVideoBudget
import dev.k3vinb5.freesocial.settings.AppMode
import dev.k3vinb5.freesocial.settings.AppTarget
import dev.k3vinb5.freesocial.settings.SettingsRepository
import java.time.ZonedDateTime

/**
 * The content-filtering engine (SPEC §2.1–2.2, mechanics from `docs/analysis/03a`).
 *
 * One single-flight scan per event burst on the main looper (0-delay, coalesced), a 1200 ms action
 * debounce per surface plus a per-app quota, a 400-node walk cap, and a stopwatch that charges
 * budgeted short-video surfaces to the shared daily allowance. Every path swallows and logs its
 * own errors: a broken rule or a dead node must never crash the service that the user relies on.
 */
class FilterService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var settings: SettingsRepository? = null
    private var ruleMatcher: RuleSetMatcher = RuleSetMatcher(emptyList())
    private val throttle = ActionThrottle()
    private val stopwatch = SurfaceStopwatch()

    private var scanQueued = false
    private var tickQueued = false
    private var receiverRegistered = false

    private val toggles = SurfaceToggleReader { app, key ->
        settings?.surfaceEnabled(app, key) ?: true
    }

    private val budgetTick = object : Runnable {
        override fun run() {
            tickQueued = false
            if (!safe { flushBudget() }) return
            if (stopwatch.activeSurface != null) scheduleBudgetTick()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> safe { stopBudget() }
                Intent.ACTION_SCREEN_ON -> safe { requestScan() }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val repo = SettingsRepository.from(this)
            settings = repo
            ruleMatcher = RuleSetMatcher(RuleCatalog.load(this), toggles)
            registerScreenReceiver()
            requestScan()
        } catch (t: Throwable) {
            Log.w(TAG, "onServiceConnected failed", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val pkg = event.packageName?.toString() ?: return
            val app = targetFor(pkg)
            if (app == null) {
                // Foreign package: only worth a re-check if we currently believe a surface is up.
                if (stopwatch.activeSurface != null) requestScan()
                return
            }
            if (captureArmedDumpIfRequested(event, pkg)) return
            if (!interactive()) {
                stopBudget()
                return
            }
            val repo = settings
            if (repo == null || !repo.filteringEnabled || repo.modeFor(app) != AppMode.FILTER) {
                stopBudget()
                return
            }
            requestScan()
        } catch (t: Throwable) {
            Log.w(TAG, "onAccessibilityEvent failed", t)
        }
    }

    // --- Scan pipeline -------------------------------------------------------------------

    /** Single-flight: one scan per burst, coalesced on the main looper with zero delay. */
    private fun requestScan() {
        if (scanQueued) return
        scanQueued = true
        handler.post {
            scanQueued = false
            safe { scan() }
        }
    }

    private fun scan() {
        val repo = settings ?: return
        if (!repo.filteringEnabled) {
            stopBudget()
            return
        }
        val focused = activeRoot() ?: run {
            stopBudget()
            return
        }
        val appPackage = focused.packageName?.toString() ?: run {
            stopBudget()
            return
        }
        val app = targetFor(appPackage) ?: run {
            stopBudget()
            return
        }
        if (repo.modeFor(app) != AppMode.FILTER) {
            stopBudget()
            return
        }
        // Every window the foreground package owns: a focused sheet/overlay must not hide the
        // surface (TikTok's FYP lives in a second application window in the observed dumps).
        val roots = windowRoots(appPackage)
        if (roots.isEmpty()) {
            stopBudget()
            return
        }

        val now = ZonedDateTime.now()
        val elapsed = SystemClock.elapsedRealtime()
        val captured = NodeTreeReader.captureWindows(roots, appPackage)
        val hits = ruleMatcher.hits(app, appPackage, captured.root)
        val exhausted = isBudgetExhausted(repo, now)
        val locked = repo.isLocked(now)
        val outcome = RuleGate.decide(hits, exhausted, locked)

        // Budget accounting runs before the gate acts so the last second before an exit counts.
        val countingHit = hits.firstOrNull {
            SurfacePolicy.countsAgainstBudget(app, appPackage, it.rule.surface, toggles)
        }
        when {
            outcome == GateOutcome.SKIP -> stopBudget()
            countingHit != null && !(exhausted || locked) -> trackBudget(countingHit.rule.surface, appPackage, repo, now)
            else -> stopBudget()
        }

        if (outcome != GateOutcome.NEUTRALISE) return
        // Exactly one action per scan, most escape-like first (RuleGate.ACTION_PRIORITY).
        val hit = RuleGate.chosen(hits, exhausted, locked) ?: return
        if (!throttle.tryAcquire(hit.rule.surface, appPackage, elapsed)) return
        perform(hit, captured, appPackage)
    }

    // --- Actions -------------------------------------------------------------------------

    /**
     * Runs the chosen action. Success is logged **after** the mechanism reported it (defect D1c):
     * a hit used to be logged as "neutralising" *before* the dispatch, so a rejected gesture and a
     * real removal looked identical in logcat.
     */
    private fun perform(hit: RuleHit, captured: CapturedTree, appPackage: String) {
        val label = "$appPackage/${hit.rule.surface}"
        when (hit.rule.action) {
            Action.BACK -> report(label, Action.BACK, performGlobalAction(GLOBAL_ACTION_BACK))
            Action.REDIRECT_HOME -> report(label, Action.REDIRECT_HOME, performGlobalAction(GLOBAL_ACTION_HOME))
            Action.SCROLL_PAST -> scrollPast(label, hit, captured)
            // Never the matched node: that is the surface being hidden (e.g. the Reels tab).
            Action.TAP_HOME_TAB -> tapHomeTab(label, captured)
            Action.CLICK_VIEW_ID -> {
                val targetId = hit.rule.viewIdTarget ?: return
                val node = captured.firstByViewId(targetId) ?: captured.live(hit.node) ?: return
                if (clickNode(node)) report(label, Action.CLICK_VIEW_ID, true)
                else report(label, Action.CLICK_VIEW_ID, tapNode(node))
            }
            Action.SKIP -> Unit
        }
    }

    /** The only success line in the log: it is emitted once the action's own API said so. */
    private fun report(label: String, action: Action, ok: Boolean) {
        if (ok) Log.i(TAG, "neutralising $label via $action")
        else Log.w(TAG, "action failed $label via $action")
    }

    /** `ACTION_CLICK` on the node or the nearest clickable ancestor (03a `Q`, ≤ 6 levels). */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= CLICK_ANCESTOR_DEPTH) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
            depth++
        }
        return false
    }

    /** Gesture fallback: a 60 ms tap on the node centre. */
    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, GESTURE_STROKE_MS)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /**
     * Tap the real Home tab of [captured]'s package. The matched node is never used: it is the
     * surface being hidden. No identifiable tab → the global Home action is the last resort.
     */
    private fun tapHomeTab(label: String, captured: CapturedTree) {
        val tab = HomeTabResolver.homeTab(captured.root, captured.packageName)?.let(captured::live)
        if (tab != null && (clickNode(tab) || tapNode(tab))) {
            report(label, Action.TAP_HOME_TAB, true)
            return
        }
        report("$label (fallback home)", Action.TAP_HOME_TAB, performGlobalAction(GLOBAL_ACTION_HOME))
    }

    /**
     * In-place removal: `ACTION_SCROLL_FORWARD` on the matched node's nearest scrollable ancestor
     * first, and only when that reports `false` the synthetic swipe framed on that ancestor.
     */
    private fun scrollPast(label: String, hit: RuleHit, captured: CapturedTree) {
        val plan = ScrollPastGesture.plan(captured.root, hit.node.index)
        val target = plan.frameNode?.let(captured::live)
        val outcome = ScrollPastGesture.execute(
            plan,
            forward = { target != null && target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) },
            gesture = { plan.swipe?.let { dispatchSwipe(it, label) } ?: false },
        )
        when (outcome) {
            ScrollOutcome.FORWARD_ACCEPTED ->
                Log.i(TAG, "neutralising $label via SCROLL_PAST (ACTION_SCROLL_FORWARD ${plan.frame})")
            ScrollOutcome.GESTURE_DISPATCHED ->
                Log.i(TAG, "neutralising $label via SCROLL_PAST (gesture dispatched, awaiting completion on ${plan.frame})")
            ScrollOutcome.GESTURE_REJECTED ->
                Log.w(TAG, "action failed $label via SCROLL_PAST (gesture not dispatched)")
            ScrollOutcome.NO_PLAN ->
                Log.w(TAG, "action failed $label via SCROLL_PAST (${plan.reason})")
        }
    }

    /** Dispatches the swipe and reports its asynchronous completion instead of guessing. */
    private fun dispatchSwipe(swipe: SwipePath, label: String): Boolean {
        val path = Path().apply {
            moveTo(swipe.startX.toFloat(), swipe.startY.toFloat())
            lineTo(swipe.endX.toFloat(), swipe.endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "gesture completed $label")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "gesture failed $label (cancelled)")
            }
        }
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, null)
    }


    // --- Short-video budget --------------------------------------------------------------

    private fun isBudgetExhausted(repo: SettingsRepository, now: ZonedDateTime): Boolean {
        if (repo.isLocked(now)) return true
        val budget = ShortVideoBudget(repo.budgetLimitMinutes)
        budget.record(repo.usedSecondsToday(now), now)
        return budget.isExhausted(now)
    }

    private fun trackBudget(surface: String, appPackage: String, repo: SettingsRepository, now: ZonedDateTime) {
        val key = "$appPackage/$surface"
        val flushed = stopwatch.enter(key, SystemClock.elapsedRealtime())
        if (flushed > 0) repo.recordBudgetSeconds(flushed, now)
        scheduleBudgetTick()
    }

    private fun scheduleBudgetTick() {
        if (tickQueued) return
        tickQueued = true
        handler.postDelayed(budgetTick, SurfaceStopwatch.TICK_INTERVAL_MS)
    }

    /** The focused application window, so system UI / IME windows do not mask the app behind. */
    private fun activeRoot(): AccessibilityNodeInfo? {
        val focused = windows
            .firstOrNull { it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.root
        return focused ?: rootInActiveWindow
    }

    /**
     * Every application window owned by [appPackage], topmost first — the same package can expose
     * its surface from more than one window (TikTok's FYP behind a sheet, IG behind a dialog).
     */
    private fun windowRoots(appPackage: String): List<AccessibilityNodeInfo> {
        val roots = windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { window -> window.root?.takeIf { it.packageName?.toString() == appPackage } }
            .distinct()
        if (roots.isNotEmpty()) return roots
        val active = rootInActiveWindow ?: return emptyList()
        return listOf(active).filter { it.packageName?.toString() == appPackage }
    }

    /** 1 s heartbeat: charge the second, and re-evaluate so an exhausted budget exits promptly. */
    private fun flushBudget() {
        if (!interactive()) {
            stopBudget()
            return
        }
        val seconds = stopwatch.tick(SystemClock.elapsedRealtime())
        if (seconds > 0) settings?.recordBudgetSeconds(seconds, ZonedDateTime.now())
        if (stopwatch.activeSurface != null) requestScan()
    }

    private fun stopBudget() {
        val seconds = stopwatch.leave(SystemClock.elapsedRealtime())
        if (seconds > 0) settings?.recordBudgetSeconds(seconds, ZonedDateTime.now())
        handler.removeCallbacks(budgetTick)
        tickQueued = false
    }

    // --- Lifecycle -----------------------------------------------------------------------

    override fun onInterrupt() {
        safe { stopBudget() }
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
        safe { stopBudget() }
        handler.removeCallbacksAndMessages(null)
        tickQueued = false
        scanQueued = false
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        instance = null
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun interactive(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return true
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    // --- Rule inspector seam -------------------------------------------------------------

    /**
     * ARMED-DUMP seam (SPEC §2.5, acceptance item 8): the inspector arms one capture, the user
     * switches to a target app, and this method writes that app's tree. Before this, dumps only
     * ever saw our own focused inspector window.
     *
     * Returns true when the event belonged to an armed capture (handled, regardless of outcome).
     * Never throws: the whole path runs under [safe].
     */
    private fun captureArmedDumpIfRequested(event: AccessibilityEvent, appPackage: String): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        if (appPackage !in TARGET_PACKAGES) return false
        if (!DumpArm.isArmed()) return false
        safe { captureArmedDump(appPackage) }
        return true
    }

    private fun captureArmedDump(appPackage: String) {
        val started = SystemClock.uptimeMillis()
        // Only the event's own package: our inspector window is never a candidate.
        val roots = windowRoots(appPackage)
        if (roots.isEmpty()) {
            Log.w(TAG, "armed dump: no readable $appPackage window")
            return
        }
        val screen = DumpArm.consume() ?: return
        // All of the package's windows, capped at 400 and de-duplicated across windows.
        val captured = NodeTreeReader.captureWindows(roots, appPackage)
        when (val result = DumpExporter.export(this, appPackage, screen, captured.root)) {
            is DumpResult.Success -> Log.i(
                TAG,
                "armed dump $appPackage/\"$screen\": ${result.document.nodes.size} nodes in " +
                    "${SystemClock.uptimeMillis() - started} ms -> ${result.file?.name}",
            )
            is DumpResult.Unavailable -> Log.w(TAG, "armed dump unavailable: ${result.reason}")
        }
    }

    private inline fun safe(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (t: Throwable) {
        Log.w(TAG, "filter engine error", t)
        false
    }

    companion object {
        private const val TAG = "FilterService"
        private const val CLICK_ANCESTOR_DEPTH = 6
        private const val GESTURE_STROKE_MS = 60L
        private const val SWIPE_DURATION_MS = 220L

        /** TikTok's global build behaves like the musically package. */
        const val PACKAGE_TIKTOK_GLOBAL = "com.ss.android.ugc.trill"

        val TARGET_PACKAGES: Set<String> = setOf(
            AppTarget.INSTAGRAM.packageName,
            AppTarget.YOUTUBE.packageName,
            AppTarget.TIKTOK.packageName,
            PACKAGE_TIKTOK_GLOBAL,
        )

        @Volatile
        var instance: FilterService? = null
            private set

        /** Resolves a package to its settings target, including TikTok's global build. */
        fun targetFor(packageName: String): AppTarget? = when (packageName) {
            PACKAGE_TIKTOK_GLOBAL -> AppTarget.TIKTOK
            else -> AppTarget.entries.firstOrNull { it.packageName == packageName }
        }

        val isConnected: Boolean get() = instance != null
    }
}
