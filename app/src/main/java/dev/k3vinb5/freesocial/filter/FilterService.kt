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
    private val arbiter = ActionArbiter()
    private val stopwatch = SurfaceStopwatch()

    /**
     * D5: connect-time init is retried lazily on every event (at most every 5 s). A race or a throw
     * during `onServiceConnected` can no longer leave the engine permanently dead and silent.
     */
    private val bootstrap = EngineBootstrap(
        load = ::loadEngine,
        clock = { SystemClock.elapsedRealtime() },
    )

    /** D5(c): one warning per reason per 30 s; scan diagnostics once per 10 s (D5(d)). */
    private val warnLimiter = LogLimiter(WARN_WINDOW_MS)
    private val diagLimiter = LogLimiter(DIAG_WINDOW_MS)

    /** D8: one ghost-tree probe line per package per 10 s, every package included. */
    private val probeLimiter = LogLimiter(PROBE_WINDOW_MS)

    private var lastBeatElapsed = 0L
    private var scanQueued = false
    private var tickQueued = false
    private var receiverRegistered = false

    /** The engine's two dependencies, loaded together so a half-initialised engine is impossible. */
    private class EngineDeps(
        val settings: SettingsRepository,
        val matcher: RuleSetMatcher,
        val ruleCount: Int,
    )

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
        // D8 layer (a): re-assert the capture flags at bind time. The XML declaration was compiled
        // but evidently not in effect on the runtime child links.
        safe { reassertCaptureFlags() }
        val deps = engine(force = true)
        if (deps == null) {
            val error = bootstrap.lastError
            Log.e(TAG, "init failed — filtering is DOWN and will retry on events", error)
            return
        }
        beat(force = true)
        safe { registerScreenReceiver() }
        Log.i(TAG, "connected, rules=${deps.ruleCount}, targets=${TARGET_PACKAGES.joinToString(",")}")
        if (deps.ruleCount == 0) Log.w(TAG, "catalog empty — no rules loaded, nothing can match")
        requestScan()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) {
                warnOnce("null event", "accessibility event was null")
                return
            }
            val pkg = event.packageName?.toString() ?: run {
                warnOnce("no package", "accessibility event without a package name")
                return
            }
            // D8: the ghost-tree probe runs for EVERY package — including our own
            // (dev.k3vinb5.freesocial) and system packages — and before the engine/target gates,
            // because "foreign content stripped but own windows rich" is the discrimination.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) safe { probeActiveRoot(pkg, event) }
            // Diagnosis must not depend on the engine being healthy: an armed dump still captures.
            if (captureArmedDumpIfRequested(event, pkg)) return
            // D5(b): lazy self-heal runs on EVERY event, whatever the package. D7: the heartbeat
            // is only written once the engine is actually initialised, so a dead engine stays dead
            // in the hub too.
            val deps = engine()
            if (deps == null) {
                warnOnce(
                    "repo null",
                    "engine unavailable for $pkg (${bootstrap.lastError ?: "init failed"}) — " +
                        "retrying every ${EngineBootstrap.RETRY_AFTER_MS / 1000}s",
                )
                stopBudget()
                return
            }
            beat()
            if (!receiverRegistered) safe { registerScreenReceiver() }
            val app = targetFor(pkg)
            if (app == null) {
                // Foreign package: only worth a re-check if we currently believe a surface is up.
                if (stopwatch.activeSurface != null) requestScan()
                return
            }
            if (!interactive()) {
                warnOnce("not interactive", "ignoring $pkg: screen off or keyguard locked")
                stopBudget()
                return
            }
            if (deps.ruleCount == 0) {
                warnOnce("catalog empty", "rules.json loaded 0 rules — nothing can match")
                stopBudget()
                return
            }
            if (!deps.settings.filteringEnabled || deps.settings.modeFor(app) != AppMode.FILTER) {
                infoOnce("filtering off", "$pkg is not filtered (master off or monitor mode)")
                stopBudget()
                return
            }
            requestScan()
        } catch (t: Throwable) {
            Log.w(TAG, "onAccessibilityEvent failed", t)
        }
    }

    /** Loads both dependencies together; throws/returns null so [EngineBootstrap] records the failure. */
    private fun loadEngine(): EngineDeps {
        val repo = SettingsRepository.from(this)
        val rules = RuleCatalog.load(this)
        if (rules.isEmpty()) error("rule catalog is empty")
        return EngineDeps(repo, RuleSetMatcher(rules, toggles), rules.size)
    }

    /**
     * Current dependencies, lazily (re)initialised. The fields other methods read are only set
     * after a successful load, so they are either both valid or both untouched.
     */
    private fun engine(force: Boolean = false): EngineDeps? {
        val deps = if (force) bootstrap.attempt(force = true) else bootstrap.current()
        if (deps == null) return null
        settings = deps.settings
        ruleMatcher = deps.matcher
        return deps
    }

    /** D7 heartbeat, at most once per 60 s of event activity; connect forces one. */
    private fun beat(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastBeatElapsed != 0L && now - lastBeatElapsed < EngineHealth.BEAT_INTERVAL_MS) return
        lastBeatElapsed = now
        runCatching { EngineHealth.recordBeat(this) }
            .onFailure { Log.w(TAG, "could not write engine heartbeat", it) }
    }

    private fun warnOnce(reason: String, message: String) {
        if (warnLimiter.allow(reason, SystemClock.elapsedRealtime())) Log.w(TAG, message)
    }

    private fun infoOnce(reason: String, message: String) {
        if (diagLimiter.allow(reason, SystemClock.elapsedRealtime())) Log.i(TAG, message)
    }

    // --- D8 ghost-tree probe -------------------------------------------------------------

    /**
     * D8 layer (a): OR the capture flags into the live service info and hand it back via
     * `setServiceInfo`, leaving event types, feedback and timeout untouched. Logged once at connect.
     */
    private fun reassertCaptureFlags() {
        val info = serviceInfo
        if (info == null) {
            Log.w(TAG, "service info unavailable — capture flags could not be re-asserted")
            return
        }
        val before = info.flags
        info.flags = ServiceFlags.withCapture(before)
        serviceInfo = info
        Log.i(
            TAG,
            "capture flags: $before -> ${info.flags} (${ServiceFlags.describe(info.flags)}), " +
                "added=${ServiceFlags.missing(before)}",
        )
    }

    /**
     * D8: one `probe <pkg> …` line per package per 10 s on every window-state change. `root` is
     * always `rootInActiveWindow`; the id lookup asks the same root the walk starts from, so:
     * `rootChildCount=3 && idProbe …>=1` = our walk/input is the truncation (H-OURS), while
     * `rootChildCount=3 && idProbe all 0` (with our own package's root rich) = vendor stripping
     * (H-VENDOR). Diagnostics only — nothing here feeds matching, actions or budget.
     *
     * D8 round 2 adds the mechanism probes: `eventSourceChildCount` (does the event carry a tree
     * richer than the window root?) and `refreshChildCount` (does `refresh()` un-stick the root?).
     */
    private fun probeActiveRoot(pkg: String, event: AccessibilityEvent) {
        if (!probeLimiter.allow("probe:$pkg", SystemClock.elapsedRealtime())) return
        val root = rootInActiveWindow
        val ids = GhostTreeProbe.idsFor(pkg)
        val line = GhostTreeProbe.format(
            pkg,
            rootShape(root, event, refresh = true),
            GhostTreeProbe.idProbeText(ids, viewIdCounts(root, ids)),
        )
        Log.i(TAG, line)
    }

    /**
     * The live root's shape for the probe; unreadable fields stay `-1`/null instead of throwing.
     * [refresh] asks the root to re-read its own links first (D8 layer (d): distinguishes a stale
     * snapshot from a permanently pruned one). The event source is read straight off the event.
     */
    private fun rootShape(
        root: AccessibilityNodeInfo?,
        event: AccessibilityEvent? = null,
        refresh: Boolean = false,
    ): GhostTreeProbe.RootShape {
        if (root == null) {
            return GhostTreeProbe.RootShape(eventSourceChildCount = eventSourceChildCount(event))
        }
        val first = runCatching { if (root.childCount > 0) root.getChild(0) else null }.getOrNull()
        val before = runCatching { root.childCount }.getOrDefault(-1)
        val refreshed = if (!refresh) null else runCatching {
            root.refresh()
            root.childCount
        }.getOrNull()
        return GhostTreeProbe.RootShape(
            packageName = runCatching { root.packageName?.toString() }.getOrNull(),
            viewId = runCatching { root.viewIdResourceName }.getOrNull(),
            childCount = before,
            grandchildCount = first?.let { runCatching { it.childCount }.getOrNull() },
            eventSourceChildCount = eventSourceChildCount(event),
            refreshChildCount = refreshed,
        )
    }

    /** `event.source?.childCount`, or null when the event carries no readable source. */
    private fun eventSourceChildCount(event: AccessibilityEvent?): Int? =
        event?.let { runCatching { it.source?.childCount }.getOrNull() }

    /** `findAccessibilityNodeInfosByViewId` hit counts, parallel to [ids]; 0 when unreadable. */
    private fun viewIdCounts(root: AccessibilityNodeInfo?, ids: List<String>): List<Int> =
        ids.map { id ->
            if (root == null) 0
            else runCatching { root.findAccessibilityNodeInfosByViewId(id)?.size ?: 0 }.getOrDefault(0)
        }

    /** D8: the scan-line fields, measured on the top-most captured app window root. */
    private fun scanProbe(windows: List<WindowRoot>, appPackage: String): String {
        val root = windows.firstOrNull()?.node
        val ids = GhostTreeProbe.idsFor(appPackage)
        return GhostTreeProbe.scanSuffix(rootShape(root), GhostTreeProbe.idProbeText(ids, viewIdCounts(root, ids)))
    }

    /**
     * The lookup lists both the scan and the armed dump feed into
     * [NodeTreeReader.captureAll]: every id and every literal text/desc the applicable rules
     * declare, the engine's known Home-tab ids, and the container anchors ([CaptureAnchors]).
     *
     * D8 round 3 (R4): the armed dump used to call `captureAll` without these lists, so the
     * inspector wrote the 4-node ghost while the scan on the same window saw grafted content.
     */
    private fun captureLookups(app: AppTarget?, appPackage: String): Pair<List<String>, List<String>> {
        val ruleIds = if (app == null) emptyList() else ruleMatcher.lookupViewIds(app, appPackage)
        val texts = if (app == null) emptyList() else ruleMatcher.lookupTexts(app, appPackage)
        val ids = (ruleIds + HomeTabResolver.idsFor(appPackage) + CaptureAnchors.idsFor(appPackage)).distinct()
        return ids to texts.distinct()
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
        val deps = engine() ?: run {
            warnOnce("repo null", "scan skipped: engine unavailable (${bootstrap.lastError ?: "init failed"})")
            stopBudget()
            return
        }
        val repo = deps.settings
        if (!repo.filteringEnabled) {
            infoOnce("filtering off", "scan skipped: master switch is off")
            stopBudget()
            return
        }
        val focused = activeRoot() ?: run {
            infoOnce("no root", "scan skipped: no active window")
            stopBudget()
            return
        }
        val appPackage = focused.packageName?.toString() ?: run {
            warnOnce("no package", "scan skipped: active window has no package name")
            stopBudget()
            return
        }
        val app = targetFor(appPackage) ?: run {
            infoOnce("not a target", "$appPackage is not a filtered app")
            stopBudget()
            return
        }
        if (repo.modeFor(app) != AppMode.FILTER) {
            infoOnce("mode not filter", "$appPackage is in monitor mode")
            stopBudget()
            return
        }
        // Every window the foreground package owns: a focused sheet/overlay must not hide the
        // surface (TikTok's FYP lives in a second application window in the observed dumps).
        val windows = appWindows(appPackage)
        if (windows.isEmpty()) {
            warnOnce("no windows", "scan skipped: no readable $appPackage window")
            stopBudget()
            return
        }

        val now = ZonedDateTime.now()
        val elapsed = SystemClock.elapsedRealtime()
        // D8 layers (b)/(c): graft every id and every literal text/desc value an applicable rule
        // actually declares, so text-anchored rules survive a platform that prunes child links.
        // R1: the extra container anchors put id-less content (the Shorts shelf) back in reach.
        val (lookupIds, lookupTexts) = captureLookups(app, appPackage)
        val captured = NodeTreeReader.captureAll(
            windows,
            appPackage,
            viewIdLookups = lookupIds,
            textLookups = lookupTexts,
            subtreeLookups = CaptureAnchors.idsFor(appPackage),
        )
        val hits = ruleMatcher.hits(app, appPackage, captured.root)
        diagnose(appPackage, captured, app, deps.ruleCount, hits.size, scanProbe(windows, appPackage))
        // Policy 5: while the rule inspector has a capture armed/active the engine touches nothing
        // (no escapes, no scroll, no taps), so the user can navigate freely. DumpArm clears itself
        // when the capture completes or the 60 s arm expires, which restores enforcement.
        if (DumpArm.isArmed()) {
            infoOnce("dump armed", "$appPackage: interaction paused while a rule-inspector capture is armed")
            stopBudget()
            return
        }
        // Episode tracking uses every surface the capture shows, before any gating: a surface that
        // disappeared ends its escape episode, so a re-entry is always a fresh, deterministic entry.
        arbiter.observe(hits.map { "$appPackage/${it.rule.surface}" }.toSet())
        val exhausted = isBudgetExhausted(repo, now)
        val locked = repo.isLocked(now)
        val outcome = RuleGate.decide(hits, exhausted, locked)

        // Budget accounting runs before the gate acts so the last second before an exit counts.
        val countingHit = hits.firstOrNull {
            SurfacePolicy.countsAgainstBudget(app, appPackage, it.rule.surface, toggles)
        }
        when {
            outcome == GateOutcome.SKIP -> stopBudget()
            outcome == GateOutcome.PAY && countingHit != null ->
                trackBudget(countingHit.rule.surface, appPackage, repo, now)
            else -> stopBudget()
        }

        if (outcome != GateOutcome.ESCAPE) {
            infoOnce("gate blocked", "$appPackage: ${hits.size} hit(s) but gate=$outcome (budget=$exhausted, locked=$locked)")
            return
        }
        // Exactly one action per scan, most escape-like first (RuleGate.ACTION_PRIORITY).
        val hit = RuleGate.chosen(hits, exhausted, locked) ?: run {
            warnOnce("no chosen hit", "$appPackage: gate said ESCAPE but no action was chosen")
            return
        }
        // R3: a confirmed escape is asynchronous — scans on the pre-navigation tree must not re-fire
        // that same surface episode. A re-entry (surface gone from a later capture) is never blocked.
        val surfaceKey = hit.rule.surface
        if (!arbiter.tryAcquire(surfaceKey, appPackage, elapsed)) {
            infoOnce("throttled", "$appPackage/$surfaceKey: duplicate action for the current surface episode")
            return
        }
        val escapedAction = hit.rule.action in ESCAPE_ACTIONS
        val acted = perform(hit, captured, appPackage)
        arbiter.confirm(surfaceKey, appPackage, escapedAction, acted)
    }

    /**
     * D5(d): once per 10 s, per target package — how big the tree was and how much of it carries
     * view ids, plus how many rules matched. `nodes>0 && viewIds==0` is the H2 signature (the OS
     * stripped view ids); `nodes==0` is a capture problem; both are stated explicitly.
     *
     * D8: the same line carries `rootChildCount` and `idProbe`, the same root shape the ghost-tree
     * probe reports, so the scan path and the window-state probe agree.
     */
    private fun diagnose(
        appPackage: String,
        captured: CapturedTree,
        app: AppTarget,
        rules: Int,
        hits: Int,
        probe: String,
    ) {
        if (!diagLimiter.allow("scan:$appPackage", SystemClock.elapsedRealtime())) return
        val nodes = NodeTree.flatten(captured.root, NodeTree.MAX_SCAN_NODES)
        val withIds = nodes.count { it.viewId != null }
        Log.i(
            TAG,
            ScanDiagnostics.line(
                appPackage = appPackage,
                appName = app.name,
                nodes = nodes.size,
                withViewIds = withIds,
                matchedRules = ruleMatcher.rulesFor(app, appPackage).size,
                totalRules = rules,
                matched = hits,
                grafted = captured.grafts,
                windows = captured.windows,
                probe = probe,
            ),
        )
        if (nodes.size <= 1) {
            warnOnce("empty tree:$appPackage", "scan saw an EMPTY $appPackage tree — capture or window access is broken")
        } else if (withIds == 0) {
            warnOnce(
                "no view ids:$appPackage",
                "$appPackage tree has 0 view ids — rules keyed on viewId cannot match " +
                    "(H2: FLAG_REPORT_VIEW_IDS / allow restricted settings)",
            )
        }
        if (captured.capped) {
            warnOnce("capped:$appPackage", "capture hit the ${NodeTree.MAX_SCAN_NODES}-node cap for $appPackage — tree truncated")
        }
    }

    // --- Actions -------------------------------------------------------------------------

    /**
     * Runs the chosen action and reports whether its own API confirmed it. Success is logged
     * **after** the mechanism reported it (defect D1c): a hit used to be logged as "neutralising"
     * *before* the dispatch, so a rejected gesture and a real removal looked identical in logcat.
     *
     * R3: BACK / TAP_HOME_TAB / REDIRECT_HOME are escape actions that change the screen
     * asynchronously; their confirmed result starts a content-based escape episode in
     * [ActionArbiter] (no time window).
     */
    private fun perform(hit: RuleHit, captured: CapturedTree, appPackage: String): Boolean {
        val label = "$appPackage/${hit.rule.surface}"
        return when (hit.rule.action) {
            Action.BACK -> performGlobalAction(GLOBAL_ACTION_BACK).also { report(label, Action.BACK, it) }
            Action.REDIRECT_HOME ->
                performGlobalAction(GLOBAL_ACTION_HOME).also { report(label, Action.REDIRECT_HOME, it) }
            Action.SCROLL_PAST -> {
                scrollPast(label, hit, captured)
                false
            }
            // Never the matched node: that is the surface being hidden (e.g. the Reels tab).
            Action.TAP_HOME_TAB -> tapHomeTab(label, captured)
            Action.CLICK_VIEW_ID -> {
                val targetId = hit.rule.viewIdTarget ?: run {
                    warnOnce("no viewIdTarget", "$label: CLICK_VIEW_ID rule has no viewIdTarget")
                    return false
                }
                val node = captured.firstByViewId(targetId) ?: captured.live(hit.node) ?: run {
                    warnOnce("click target missing", "$label: neither $targetId nor the matched node is readable")
                    return false
                }
                if (clickNode(node)) report(label, Action.CLICK_VIEW_ID, true)
                else report(label, Action.CLICK_VIEW_ID, tapNode(node))
            }
            Action.SKIP -> false
        }
    }

    /** The only success line in the log: it is emitted once the action's own API said so. */
    private fun report(label: String, action: Action, ok: Boolean): Boolean {
        if (ok) Log.i(TAG, "neutralising $label via $action")
        else Log.w(TAG, "action failed $label via $action")
        return ok
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
    private fun tapHomeTab(label: String, captured: CapturedTree): Boolean {
        val tab = HomeTabResolver.homeTab(captured.root, captured.packageName)?.let(captured::live)
        if (tab != null && (clickNode(tab) || tapNode(tab))) {
            return report(label, Action.TAP_HOME_TAB, true)
        }
        return report("$label (fallback home)", Action.TAP_HOME_TAB, performGlobalAction(GLOBAL_ACTION_HOME))
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
    private fun appWindows(appPackage: String): List<WindowRoot> {
        // D6: every application window of the package, with its title/class/layer for the dump
        // header. Ordering is the builder's job (top-most layer first).
        val fromWindows = windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { window ->
                val root = try {
                    window.root
                } catch (t: Throwable) {
                    warnOnce("window root:$appPackage", "could not read a $appPackage window root: $t")
                    null
                }
                if (root == null) return@mapNotNull null
                if (root.packageName?.toString() != appPackage) return@mapNotNull null
                WindowRoot(
                    node = root,
                    title = window.title?.toString(),
                    className = root.className?.toString(),
                    layer = window.layer,
                    packageName = appPackage,
                )
            }
        if (fromWindows.isNotEmpty()) return fromWindows
        val active = rootInActiveWindow
        if (active == null) {
            warnOnce("no root", "no readable active window for $appPackage")
            return emptyList()
        }
        if (active.packageName?.toString() != appPackage) return emptyList()
        return listOf(
            WindowRoot(
                node = active,
                className = active.className?.toString(),
                packageName = appPackage,
            ),
        )
    }

    /** 1 s heartbeat: charge the second, and re-evaluate so an exhausted budget exits promptly. */
    private fun flushBudget() {
        if (!interactive()) {
            stopBudget()
            return
        }
        val seconds = stopwatch.tick(SystemClock.elapsedRealtime())
        if (seconds > 0) recordSeconds(seconds)
        if (stopwatch.activeSurface != null) requestScan()
    }

    private fun stopBudget() {
        val seconds = stopwatch.leave(SystemClock.elapsedRealtime())
        if (seconds > 0) recordSeconds(seconds)
        handler.removeCallbacks(budgetTick)
        tickQueued = false
    }

    private fun recordSeconds(seconds: Long) {
        val repo = settings ?: run {
            warnOnce("repo null", "dropping $seconds s of budget: settings unavailable")
            return
        }
        repo.recordBudgetSeconds(seconds, ZonedDateTime.now())
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
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (power == null || keyguard == null) return true
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
        // A launch transition can fire the target's state change while the previous app's window
        // is still the active root (Wave A A5 / retry R4: a 4-node decor tree labelled "YouTube").
        // Keep the one-shot armed; the next state change captures the real target window.
        val active = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (!DumpArm.readyFor(appPackage, active)) {
            infoOnce(
                "dump wait",
                "armed dump: waiting for $appPackage (active window is $active)",
            )
            return true
        }
        safe { captureArmedDump(appPackage) }
        return true
    }

    private fun captureArmedDump(appPackage: String, attempt: Int = 0) {
        val started = SystemClock.uptimeMillis()
        // D6: every window the target package owns, top-most first — a sheet/second activity
        // window must not reduce the dump to the focused ghost tree.
        val windows = appWindows(appPackage)
        if (windows.isEmpty()) {
            Log.w(TAG, "armed dump: no readable $appPackage window")
            return
        }
        // R4: the dump must show what the engine sees — same id/text lookups as the scan path.
        // The matcher may not be loaded yet on this event, so ask the bootstrap once (cheap).
        engine()
        val (lookupIds, lookupTexts) = captureLookups(targetFor(appPackage), appPackage)
        val captured = NodeTreeReader.captureAll(
            windows,
            appPackage,
            viewIdLookups = lookupIds,
            textLookups = lookupTexts,
            subtreeLookups = CaptureAnchors.idsFor(appPackage),
        )
        val nodes = NodeTree.flatten(captured.root, NodeTree.MAX_SCAN_NODES)
        // The launch transition can be captured before YouTube fills its window: retry briefly
        // instead of consuming the one-shot and writing the 4-node decor tree (Wave A A5/R4).
        if (DumpArm.looksGhost(nodes.size, captured.grafts) && attempt < DUMP_RETRY_MAX && DumpArm.isArmed()) {
            handler.postDelayed(
                { safe { captureArmedDump(appPackage, attempt + 1) } },
                DUMP_RETRY_DELAY_MS,
            )
            return
        }
        val screen = DumpArm.consume()
        if (screen == null) {
            warnOnce("dump expired", "armed dump expired before the target app opened")
            return
        }
        val withIds = nodes.count { it.viewId != null }
        val windowText = captured.windows.joinToString("; ") { window ->
            "${window.title ?: window.className ?: "?"}(layer ${window.layer})=${window.nodes} nodes/${window.viewIds} ids"
        }
        if (captured.capped) {
            Log.w(TAG, "armed dump: ${NodeTree.MAX_SCAN_NODES}-node cap reached — $appPackage tree truncated")
        }
        if (withIds == 0) {
            Log.w(
                TAG,
                "armed dump $appPackage: 0/${nodes.size} nodes carry a viewId — " +
                    "rules keyed on viewId cannot match (FLAG_REPORT_VIEW_IDS / restricted settings)",
            )
        }
        when (
            val result = DumpExporter.export(
                this,
                appPackage,
                screen,
                captured.root,
                windowStats = captured.windows,
                capped = captured.capped,
                grafted = captured.grafts,
            )
        ) {
            is DumpResult.Success -> Log.i(
                TAG,
                "armed dump $appPackage/\"$screen\": ${result.document.nodeCount} nodes " +
                    "(${result.document.nodesWithViewId} with viewId, ${captured.grafts} grafted, " +
                    "${captured.windows.size} windows: " +
                    "$windowText) in ${SystemClock.uptimeMillis() - started} ms -> ${result.file?.name}",
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

        /** D5(c): one warning per reason per 30 s; D5(d): one scan diagnostic per package per 10 s. */
        private const val WARN_WINDOW_MS = 30_000L
        private const val DIAG_WINDOW_MS = 10_000L

        /** D8: one ghost-tree probe line per package per 10 s. */
        private const val PROBE_WINDOW_MS = 10_000L

        /** R3: actions that change the screen asynchronously; a confirmed one starts an escape episode. */
        private val ESCAPE_ACTIONS = setOf(Action.BACK, Action.TAP_HOME_TAB, Action.REDIRECT_HOME)

        /** R4: retry a ghost armed dump within the 60 s arm (4 × 600 ms covers the launch fill). */
        private const val DUMP_RETRY_MAX = 4
        private const val DUMP_RETRY_DELAY_MS = 600L

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
