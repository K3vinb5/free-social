package dev.k3vinb5.freesocial.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.block.BlockReason
import dev.k3vinb5.freesocial.settings.AppTarget
import dev.k3vinb5.freesocial.settings.SettingsRepository
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.PrimaryButton
import dev.k3vinb5.freesocial.ui.components.StatusDot
import dev.k3vinb5.freesocial.ui.theme.FreeSocialTheme
import dev.k3vinb5.freesocial.ui.theme.FsColors
import java.time.ZonedDateTime

/**
 * F13 — the block screen. Started by BlockService with `NEW_TASK|CLEAR_TASK`; declared
 * singleInstance, excluded from recents and with an empty task affinity so it never carries
 * the blocked app's task around. Back always leaves the phone at the launcher.
 */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = AppTarget.fromPlatform(
            platform = intent.getStringExtra(EXTRA_PLATFORM),
            packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME),
        )
        // D14: the extra carries the gate's reason *kind*, not pre-rendered copy. The wake-time
        // body has to be interpolated here, from the live schedule, or a per-day override drifts.
        val reason = reasonFrom(intent.getStringExtra(EXTRA_REASON))
        val wakeClock = SettingsRepository.formatClock(
            SettingsRepository.from(this).scheduleState().nextWakeMinutes(ZonedDateTime.now()),
        )

        setContent {
            FreeSocialTheme {
                BlockScreen(
                    appLabel = stringResource(target?.nameRes ?: R.string.block_app_fallback),
                    body = BlockCopy.body(
                        reason = reason,
                        wakeBody = stringResource(R.string.block_screen_body, wakeClock),
                        appBody = stringResource(R.string.block_reason_app),
                    ),
                    onGoHome = ::goHome,
                )
            }
        }
    }

    /** Unknown or missing extra -> null, i.e. the generic body (never a crash on a bad intent). */
    private fun reasonFrom(raw: String?): BlockReason? {
        val name = raw?.takeIf { it.isNotBlank() } ?: return null
        return BlockReason.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Leaves for the launcher instead of finishing in place: this task is singleInstance with
     * an empty affinity, so moveTaskToBack would resurface the blocked app underneath.
     */
    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    companion object {
        const val EXTRA_PLATFORM = "platform"

        /** A [BlockReason] name (`schedule` / `app_block`); mapped to copy by [BlockCopy]. */
        const val EXTRA_REASON = "reason"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
}

/**
 * D14 — the gate reason chooses the block-screen body.
 *
 * [BlockReason.SCHEDULE] proves the nightly window is what blocked the app, so the screen must carry
 * the interpolated wake time ("Focus time — come back at 07:00."), read from the live schedule so a
 * per-day override is honoured. Every other reason — and a missing/garbled extra — keeps the generic
 * app-block text. Pure so the mapping is unit-tested on the JVM.
 */
object BlockCopy {
    fun body(reason: BlockReason?, wakeBody: String, appBody: String): String = when (reason) {
        BlockReason.SCHEDULE -> wakeBody
        BlockReason.APP_BLOCK, null -> appBody
    }
}

/** Centered block card. [body] carries the copy chosen for the gate reason (see [BlockCopy]). */
@Composable
fun BlockScreen(
    appLabel: String,
    body: String,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onGoHome)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FsColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(FsColors.Card)
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusDot(FsColors.Danger, diameter = 10.dp)
            HorizontalGap(14.dp)
            Text(
                text = stringResource(R.string.block_screen_title, appLabel),
                style = MaterialTheme.typography.titleLarge,
                color = FsColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            HorizontalGap(10.dp)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = FsColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            HorizontalGap(24.dp)
            PrimaryButton(text = stringResource(R.string.block_go_home), onClick = onGoHome)
            HorizontalGap(12.dp)
            Caption(text = stringResource(R.string.block_caption), align = TextAlign.Center)
        }
    }
}
