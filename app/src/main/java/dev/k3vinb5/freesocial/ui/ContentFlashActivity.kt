package dev.k3vinb5.freesocial.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.StatusDot
import dev.k3vinb5.freesocial.ui.theme.FreeSocialTheme
import dev.k3vinb5.freesocial.ui.theme.FsColors

/**
 * The escape flash (2026-11-05): a glanceable "Free Social blocked this" screen shown for 2.2 s
 * right after the filter engine confirmed an escape action.
 *
 * Deliberately NOT the block screen: it has no button, no task clearing and no way to get stuck.
 * It finishes itself (or on tap/Back/onStop), and FilterService launches it `NEW_TASK`-only so the
 * escape that just landed the user on the feed/Home is never stomped. Declared singleInstance,
 * excluded from recents, empty task affinity — same isolation as [BlockActivity], minus CLEAR_TASK.
 *
 * D12 (2026-11-07): the 2.2 s is the time the user can actually *see*, so the auto-dismiss is armed
 * on the first window focus, not in `onCreate` (a cold Compose launch measured 1.537 s on the
 * Reno4, which left ~0.65 s of the promised flash). A [HARD_STOP_MS] cap armed at create keeps a
 * flash that somehow never gains focus from lingering, and `onNewIntent` re-arms the window so a
 * reused singleInstance instance cannot swallow a second escape's flash.
 */
class ContentFlashActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() }
    private val hardStop = Runnable { finish() }

    /** Newest copy; a second escape delivered to the live instance updates it instead of being lost. */
    private val copy = mutableStateOf(ContentFlash.copy(ContentFlash.Reason.BUDGET))
    private var focused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        copy.value = copyFrom(intent)
        setContent {
            FreeSocialTheme {
                ContentFlashScreen(copy = copy.value, onDismiss = ::dismissNow)
            }
        }
        // Hard cap: even a flash that never receives focus (e.g. the launch is dropped and the
        // activity comes up behind) cannot outlive this.
        handler.postDelayed(hardStop, FlashTiming.onCreateDelayMs())
    }

    /** The first frame is on screen: the promised 2.2 s starts now. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = hasFocus
        if (hasFocus) armVisibleWindow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        copy.value = copyFrom(intent)
        // singleInstance reuses the live window: refresh the copy and restart the visible 2.2 s.
        if (focused) {
            armVisibleWindow()
        } else {
            handler.removeCallbacks(hardStop)
            handler.postDelayed(hardStop, FlashTiming.rearmDelayMs(focused = false))
        }
    }

    private fun armVisibleWindow() {
        handler.removeCallbacks(autoDismiss)
        handler.removeCallbacks(hardStop)
        handler.postDelayed(autoDismiss, FlashTiming.onFirstFocusDelayMs())
    }

    private fun copyFrom(intent: Intent?) =
        ContentFlash.copy(ContentFlash.reasonFrom(intent?.getStringExtra(EXTRA_REASON)))

    private fun dismissNow() {
        handler.removeCallbacks(autoDismiss)
        handler.removeCallbacks(hardStop)
        finish()
    }

    /** Leaving the flash (app switch, screen off) must never leave it behind. */
    override fun onStop() {
        super.onStop()
        dismissNow()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REASON = "flash_reason"

        /** Short enough to read in passing, long enough to be noticed — measured from first focus. */
        const val FLASH_DURATION_MS = FlashTiming.VISIBLE_MS

        /** Absolute cap from create, in case the window never gains focus. */
        const val HARD_STOP_MS = FlashTiming.HARD_STOP_MS
    }
}

/** Centered, card-free copy on the solid dark background; tapping anywhere dismisses. */
@Composable
fun ContentFlashScreen(
    copy: ContentFlash.Copy,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FsColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
            .padding(horizontal = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusDot(FsColors.Danger, diameter = 10.dp)
            HorizontalGap(14.dp)
            Text(
                text = stringResource(copy.title),
                style = MaterialTheme.typography.titleLarge,
                color = FsColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            HorizontalGap(10.dp)
            Text(
                text = stringResource(copy.reason),
                style = MaterialTheme.typography.bodyLarge,
                color = FsColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            HorizontalGap(10.dp)
            Caption(text = stringResource(copy.caption), align = TextAlign.Center)
        }
    }
}

/**
 * D12 timing, extracted as a pure seam: the visible window starts at first focus, while onCreate
 * only arms the absolute cap. The activity asks this object for every delay, so the contract is
 * unit-testable without Robolectric instead of being pinned to activity source text.
 */
object FlashTiming {
    /** How long the user can actually see the flash, measured from first window focus. */
    const val VISIBLE_MS = 2_200L

    /** Absolute cap from create, in case the window never gains focus (cold launch ~1.5 s). */
    const val HARD_STOP_MS = 4_000L

    /** onCreate must schedule the cap, never the visible window. */
    fun onCreateDelayMs(): Long = HARD_STOP_MS

    /** First focus starts the promised visible window. */
    fun onFirstFocusDelayMs(): Long = VISIBLE_MS

    /** A reused instance re-arms the visible window when focused, the cap otherwise. */
    fun rearmDelayMs(focused: Boolean): Long = if (focused) VISIBLE_MS else HARD_STOP_MS
}

/**
 * The flash contract: which copy an escape shows and how it is launched. Everything except
 * [intent] is pure, so the reason mapping is testable without Android.
 */
object ContentFlash {

    /** Why the escape happened, as far as the FilterService scan already knows it. */
    enum class Reason {
        /** Short-video allowance spent (resets at midnight). */
        BUDGET,

        /** The nightly schedule/sleep window is active. */
        SLEEP,
    }

    /** Copy for one flash: three string resource ids, nothing else. */
    data class Copy(
        val title: Int,
        val reason: Int,
        val caption: Int,
    )

    /**
     * The escape gate only acts when the allowance is gone or the schedule is locked, so these two
     * flags are always enough. Sleep wins: a locked schedule explains the escape the same way as an
     * exhausted budget, and it is the more specific reason.
     */
    fun reasonFor(budgetExhausted: Boolean, scheduleLocked: Boolean): Reason = when {
        scheduleLocked -> Reason.SLEEP
        budgetExhausted -> Reason.BUDGET
        // The gate cannot escape otherwise; keep the budget line as the safe default.
        else -> Reason.BUDGET
    }

    fun copy(reason: Reason): Copy = when (reason) {
        Reason.BUDGET -> Copy(
            title = R.string.flash_title,
            reason = R.string.flash_reason_budget,
            caption = R.string.flash_caption_budget,
        )
        Reason.SLEEP -> Copy(
            title = R.string.flash_title,
            reason = R.string.flash_reason_sleep,
            caption = R.string.flash_caption_sleep,
        )
    }

    /** The reason carried in [ContentFlashActivity.EXTRA_REASON]; unknown/absent → budget. */
    fun reasonFrom(raw: String?): Reason =
        Reason.entries.firstOrNull { it.name == raw } ?: Reason.BUDGET

    /**
     * Launch intent: `NEW_TASK` only — never `CLEAR_TASK`. The escape already put the user on the
     * feed/Home in their own task; clearing it would turn an explanation into a second ejection.
     */
    fun intent(context: Context, reason: Reason): Intent =
        Intent(context, ContentFlashActivity::class.java)
            .putExtra(ContentFlashActivity.EXTRA_REASON, reason.name)
            .addFlags(launchFlags())

    /** NEW_TASK only: clearing the task would turn the explanation into a second ejection. */
    fun launchFlags(): Int = Intent.FLAG_ACTIVITY_NEW_TASK
}
