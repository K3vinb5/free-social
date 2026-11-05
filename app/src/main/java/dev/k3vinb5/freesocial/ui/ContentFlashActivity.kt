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
 */
class ContentFlashActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val copy = ContentFlash.copy(ContentFlash.reasonFrom(intent.getStringExtra(EXTRA_REASON)))
        setContent {
            FreeSocialTheme {
                ContentFlashScreen(copy = copy, onDismiss = ::dismissNow)
            }
        }
        handler.postDelayed(autoDismiss, FLASH_DURATION_MS)
    }

    private fun dismissNow() {
        handler.removeCallbacks(autoDismiss)
        finish()
    }

    /** Leaving the flash (app switch, screen off) must never leave it behind. */
    override fun onStop() {
        super.onStop()
        dismissNow()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoDismiss)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REASON = "flash_reason"

        /** Short enough to read in passing, long enough to be noticed. */
        const val FLASH_DURATION_MS = 2_200L
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
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
