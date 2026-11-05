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
        val root = activeRoot() ?: run {
            stopBudget()
            return
        }
        val appPackage = root.packageName?.toString()
        val app = appPackage?.let(::targetFor)
        if (app == null || appPackage == null) {
            stopBudget()
            return
        }
        if (repo.modeFor(app) != AppMode.FILTER) {
            stopBudget()
            return
        }

        val now = ZonedDateTime.now()
        val elapsed = SystemClock.elapsedRealtime()
        val captured = NodeTreeReader.capture(root)
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
        Log.i(TAG, "neutralising ${appPackage}/${hit.rule.surface} via ${hit.rule.action}")
        perform(hit, captured)
    }

    // --- Actions -------------------------------------------------------------------------

    private fun perform(hit: RuleHit, captured: CapturedTree) {
        when (hit.rule.action) {
            Action.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            Action.REDIRECT_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            Action.SCROLL_PAST -> scrollPast(hit.node)
            // Never the matched node: that is the surface being hidden (e.g. the Reels tab).
            Action.TAP_HOME_TAB -> tapHomeTab(captured)
            Action.CLICK_VIEW_ID -> {
                val targetId = hit.rule.viewIdTarget ?: return
                val node = captured.firstByViewId(targetId) ?: captured.live(hit.node) ?: return
                if (!clickNode(node)) tapNode(node)
            }
            Action.SKIP -> Unit
        }
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
    private fun tapHomeTab(captured: CapturedTree) {
        val tab = HomeTabResolver.homeTab(captured.root, captured.packageName)?.let(captured::live)
        if (tab != null && (clickNode(tab) || tapNode(tab))) return
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** In-place removal: swipe the matched cell/shelf upward out of the viewport. */
    private fun scrollPast(node: NodeSnapshot) {
        val swipe = ScrollPastGesture.swipePast(node.bounds) ?: return
        val path = Path().apply {
            moveTo(swipe.startX.toFloat(), swipe.startY.toFloat())
            lineTo(swipe.endX.toFloat(), swipe.endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
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
        val root = windows
            .asReversed()
            .firstOrNull { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.root?.packageName?.toString() == appPackage
            }
            ?.root
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == appPackage }
        if (root == null) {
            Log.w(TAG, "armed dump: no readable $appPackage window")
            return
        }
        val screen = DumpArm.consume() ?: return
        val captured = NodeTreeReader.capture(root)
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
