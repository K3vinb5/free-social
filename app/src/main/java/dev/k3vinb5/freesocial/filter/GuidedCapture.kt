package dev.k3vinb5.freesocial.filter

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import dev.k3vinb5.freesocial.MainActivity
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.settings.AppTarget
import java.time.ZonedDateTime

/** Phases of a guided capture: wait for the surface, record it, done. */
enum class CapturePhase { WAITING, RECORDING, FINISHED }

/** Why a guided capture ended. */
enum class CaptureOutcome { NONE, STARTED, COMPLETE, TIMEOUT, SURFACE_LOST, CANCELLED, FAILED }

/**
 * The guided-capture clock and stop rules, pure and JVM-testable.
 *
 * Recording only starts once the surface markers are seen; it stops after [recordMs] of recording,
 * when the surface disappears for [lossPolls] consecutive polls, or when the user taps "Stop now".
 * Detection is bounded by [detectTimeoutMs]; nothing is written in that case.
 */
class GuidedCaptureMachine(
    private val detectTimeoutMs: Long = GuidedCapture.DETECT_TIMEOUT_MS,
    private val recordMs: Long = GuidedCapture.RECORD_MS,
    private val lossPolls: Int = 2,
) {
    var phase: CapturePhase = CapturePhase.WAITING
        private set
    var outcome: CaptureOutcome = CaptureOutcome.NONE
        private set

    private var startedAt = 0L
    private var recordingStartedAt = 0L
    private var misses = 0

    fun start(now: Long) {
        phase = CapturePhase.WAITING
        outcome = CaptureOutcome.NONE
        startedAt = now
        recordingStartedAt = 0L
        misses = 0
    }

    /** One poll of the live tree; returns [CaptureOutcome.STARTED] on detection, the stop reason otherwise. */
    fun poll(now: Long, surfaceVerified: Boolean): CaptureOutcome {
        if (phase == CapturePhase.FINISHED) return outcome
        if (phase == CapturePhase.WAITING) {
            if (surfaceVerified) {
                phase = CapturePhase.RECORDING
                recordingStartedAt = now
                misses = 0
                outcome = CaptureOutcome.STARTED
                return CaptureOutcome.STARTED
            }
            if (now - startedAt >= detectTimeoutMs) {
                finish(CaptureOutcome.TIMEOUT)
                return outcome
            }
            return CaptureOutcome.NONE
        }
        // Recording: only a stop is an event; ordinary polls report NONE.
        if (now - recordingStartedAt >= recordMs) {
            finish(CaptureOutcome.COMPLETE)
            return outcome
        }
        if (surfaceVerified) {
            misses = 0
        } else {
            misses++
            if (misses >= lossPolls) finish(CaptureOutcome.SURFACE_LOST)
        }
        return outcome.takeIf { phase == CapturePhase.FINISHED } ?: CaptureOutcome.NONE
    }

    /** "Stop now": recording captures what it has; detection is cancelled without a dump. */
    fun stopByUser(): CaptureOutcome {
        if (phase == CapturePhase.FINISHED) return outcome
        return if (phase == CapturePhase.RECORDING) {
            finish(CaptureOutcome.COMPLETE)
            outcome
        } else {
            finish(CaptureOutcome.CANCELLED)
            outcome
        }
    }

    private fun finish(reason: CaptureOutcome) {
        phase = CapturePhase.FINISHED
        outcome = reason
    }
}

/**
 * Guided, surface-verified node capture.
 *
 * The inspector starts a session, the target app is launched for the user, and the session polls
 * the live accessibility windows through the already-connected [FilterService] (same process, so
 * the service object is public via [FilterService.instance]). Recording begins only when the
 * surface's catalog markers match, so a Reels dump cannot be polluted by the home screen. Filtering
 * is paused for the whole flow by holding the [DumpArm] guided arm; the service delegates the dump
 * to this object and never consumes it itself.
 */
object GuidedCapture {
    const val DETECT_TIMEOUT_MS = 30_000L
    const val RECORD_MS = 15_000L

    private const val TAG = "GuidedCapture"
    private const val POLL_MS = 500L
    private const val CHANNEL_ID = "node_capture"
    private const val NOTIFICATION_ID = 4101
    const val ACTION_STOP = "dev.k3vinb5.freesocial.action.STOP_CAPTURE"

    /** What the inspector renders; [phase] and [outcome] mirror the machine. */
    data class Session(
        val app: AppTarget,
        val surface: String,
        val surfaceName: String,
        val phase: CapturePhase,
        val outcome: CaptureOutcome = CaptureOutcome.NONE,
        val nodeCount: Int? = null,
        val dumpName: String? = null,
    )

    @Volatile
    var session: Session? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var machine: GuidedCaptureMachine? = null
    private var appContext: Context? = null
    private var markers: List<MatchSpec> = emptyList()
    private var lookupIds: List<String> = emptyList()
    private var lookupTexts: List<String> = emptyList()
    private var latestTree: CapturedTree? = null

    private val poll = object : Runnable {
        override fun run() = tick()
    }

    /** Arms the arm, launches nothing (the caller launches), and posts the guiding notification. */
    @Synchronized
    fun start(context: Context, app: AppTarget, surface: String) {
        dispose()
        val ctx = context.applicationContext
        appContext = ctx
        val rules: List<Rule> = RuleCatalog.current(ctx)
        markers = CaptureSurfaces.markers(app, surface, rules)
        cacheLookups(app, rules)
        val name = CaptureSurfaces.displayName(surface)
        machine = GuidedCaptureMachine().also { it.start(SystemClock.elapsedRealtime()) }
        latestTree = null
        session = Session(app, surface, name, CapturePhase.WAITING)
        DumpArm.armGuided(surface)
        ensureChannel(ctx)
        notify(
            ctx,
            ctx.getString(
                R.string.capture_notif_recording,
                ctx.getString(app.nameRes),
                surfaceLabel(ctx, surface),
            ),
            ongoing = true,
            withStop = true,
        )
        handler.postDelayed(poll, POLL_MS)
    }

    /** "Stop now" from the notification / in-app control. */
    @Synchronized
    fun stopByUser() {
        val m = machine ?: return
        if (m.phase == CapturePhase.FINISHED) return
        when (m.stopByUser()) {
            CaptureOutcome.COMPLETE -> finish(CaptureOutcome.COMPLETE)
            CaptureOutcome.CANCELLED -> finish(CaptureOutcome.CANCELLED)
            else -> Unit
        }
    }

    /** Localized surface label for notifications; custom surfaces fall back to their slug. */
    private fun surfaceLabel(context: Context, surface: String): String =
        CaptureSurfaces.nameRes(surface)?.let(context::getString) ?: CaptureSurfaces.displayName(surface)

    /** Launches the target app's main activity; false when the app has no launcher. */
    fun launchTarget(context: Context, app: AppTarget): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun tick() {
        val m = machine ?: return
        val active = session ?: return
        val now = SystemClock.elapsedRealtime()
        val tree = FilterService.instance?.let { service ->
            captureTree(service, active.app.packageName)
        }
        if (tree != null) latestTree = tree
        val verified = tree != null &&
            CaptureSurfaces.verify(active.surface, markers, NodeTree.flatten(tree.root)).verified
        when (m.poll(now, verified)) {
            CaptureOutcome.STARTED -> {
                DumpArm.refreshGuided(now)
                session = active.copy(phase = CapturePhase.RECORDING)
                appContext?.let { ctx ->
                    notify(
                        ctx,
                        ctx.getString(
                            R.string.capture_notif_detected,
                            surfaceLabel(ctx, active.surface),
                            RECORD_MS / 1000,
                        ),
                        ongoing = true,
                        withStop = true,
                    )
                }
            }
            CaptureOutcome.COMPLETE, CaptureOutcome.SURFACE_LOST, CaptureOutcome.TIMEOUT ->
                finish(m.outcome)
            else -> Unit
        }
        if (session?.phase != CapturePhase.FINISHED) handler.postDelayed(poll, POLL_MS)
    }

    private fun finish(reason: CaptureOutcome) {
        handler.removeCallbacks(poll)
        val ctx = appContext
        val active = session
        machine = null
        DumpArm.cancel()
        if (ctx == null || active == null) {
            session = null
            return
        }
        if (reason == CaptureOutcome.TIMEOUT || reason == CaptureOutcome.CANCELLED) {
            session = active.copy(phase = CapturePhase.FINISHED, outcome = reason)
            val text = if (reason == CaptureOutcome.TIMEOUT) {
                ctx.getString(
                    R.string.capture_notif_not_detected,
                    surfaceLabel(ctx, active.surface),
                )
            } else {
                ctx.getString(R.string.capture_notif_cancelled)
            }
            notify(ctx, text, ongoing = false, withStop = false)
            return
        }

        val tree = latestTree ?: FilterService.instance?.let { captureTree(it, active.app.packageName) }
        if (tree == null) {
            session = active.copy(phase = CapturePhase.FINISHED, outcome = CaptureOutcome.FAILED)
            notify(ctx, ctx.getString(R.string.capture_notif_failed), ongoing = false, withStop = false)
            return
        }
        val nodes = NodeTree.flatten(tree.root)
        val verdict = CaptureSurfaces.verify(active.surface, markers, nodes)
        val result = runCatching {
            DumpExporter.export(
                ctx,
                active.app.packageName,
                active.surfaceName,
                tree.root,
                ZonedDateTime.now(),
                tree.windows,
                tree.capped,
                tree.grafts,
                verdict,
            )
        }.getOrNull()
        val document = (result as? DumpResult.Success)?.document
        if (document == null) {
            session = active.copy(phase = CapturePhase.FINISHED, outcome = CaptureOutcome.FAILED)
            notify(ctx, ctx.getString(R.string.capture_notif_failed), ongoing = false, withStop = false)
            return
        }
        session = active.copy(
            phase = CapturePhase.FINISHED,
            outcome = reason,
            nodeCount = document.nodeCount,
            dumpName = (result as DumpResult.Success).file?.name,
        )
        Log.i(
            TAG,
            "guided capture ${active.app.packageName}/${document.screen}: " +
                "${document.nodeCount} nodes, verified=${document.surfaceVerified}, file=${session?.dumpName}",
        )
        val text = if (reason == CaptureOutcome.SURFACE_LOST) {
            ctx.resources.getQuantityString(
                R.plurals.capture_notif_surface_lost,
                document.nodeCount,
                document.nodeCount,
            )
        } else {
            ctx.resources.getQuantityString(
                R.plurals.capture_notif_complete,
                document.nodeCount,
                document.nodeCount,
            )
        }
        notify(ctx, text, ongoing = false, withStop = false)
    }

    /** Stops polling and gives the arm back; used when a new session starts. */
    private fun dispose() {
        handler.removeCallbacks(poll)
        machine = null
        DumpArm.cancel()
    }

    private fun cacheLookups(app: AppTarget, rules: List<Rule>) {
        val matcher = RuleSetMatcher(rules)
        val packageName = app.packageName
        lookupIds = (
            matcher.lookupViewIds(app, packageName) +
                HomeTabResolver.idsFor(packageName) +
                CaptureAnchors.idsFor(packageName) +
                markers.mapNotNull { it.viewId }
            ).filter { it.isNotBlank() }.distinct()
        lookupTexts = (
            matcher.lookupTexts(app, packageName) +
                markers.flatMap { RuleLookup.literalQueries(it.textRegex, it.contentDescriptionRegex) }
            ).filter { it.isNotBlank() }.distinct()
    }

    /** The live tree of every application window the target package owns, top-most first. */
    private fun captureTree(service: AccessibilityService, appPackage: String): CapturedTree? {
        val fromWindows = runCatching {
            service.windows.orEmpty()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { window ->
                    val root = runCatching { window.root }.getOrNull() ?: return@mapNotNull null
                    if (root.packageName?.toString() != appPackage) return@mapNotNull null
                    WindowRoot(
                        node = root,
                        title = window.title?.toString(),
                        className = runCatching { root.className?.toString() }.getOrNull(),
                        layer = window.layer,
                        packageName = appPackage,
                    )
                }
        }.getOrDefault(emptyList())
        val windows = fromWindows.ifEmpty {
            val active = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
            if (active.packageName?.toString() != appPackage) return null
            listOf(WindowRoot(active, packageName = appPackage))
        }
        return runCatching {
            NodeTreeReader.captureAll(
                windows,
                appPackage,
                viewIdLookups = lookupIds,
                textLookups = lookupTexts,
                subtreeLookups = CaptureAnchors.idsFor(appPackage),
            )
        }.getOrNull()
    }

    private fun ensureChannel(ctx: Context) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.capture_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = ctx.getString(R.string.capture_notif_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun notify(ctx: Context, text: String, ongoing: Boolean, withStop: Boolean) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(ctx.getString(R.string.capture_notif_channel_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
        if (withStop) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel),
                    ctx.getString(R.string.capture_notif_stop),
                    stopIntent(ctx),
                ).build(),
            )
        }
        // Missing POST_NOTIFICATIONS is a silent no-op on Android 13+; the in-app step card covers it.
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        1,
        Intent(ctx, CaptureStopReceiver::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Notification action target for "Stop now". */
class CaptureStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == GuidedCapture.ACTION_STOP) GuidedCapture.stopByUser()
    }
}
