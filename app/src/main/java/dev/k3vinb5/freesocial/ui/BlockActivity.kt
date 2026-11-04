package dev.k3vinb5.freesocial.ui

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
import dev.k3vinb5.freesocial.settings.AppTarget
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.PrimaryButton
import dev.k3vinb5.freesocial.ui.components.StatusDot
import dev.k3vinb5.freesocial.ui.theme.FreeSocialTheme
import dev.k3vinb5.freesocial.ui.theme.FsColors

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
        val reason = intent.getStringExtra(EXTRA_REASON)?.takeIf { it.isNotBlank() }

        setContent {
            FreeSocialTheme {
                BlockScreen(
                    appLabel = stringResource(target?.nameRes ?: R.string.block_app_fallback),
                    reason = reason,
                    onGoHome = ::goHome,
                )
            }
        }
    }

    private fun goHome() {
        moveTaskToBack(true)
        finish()
    }

    companion object {
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_REASON = "reason"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
}

/** Centered block card; the body falls back to the schedule copy when no reason is supplied. */
@Composable
fun BlockScreen(
    appLabel: String,
    reason: String?,
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
                text = reason ?: stringResource(R.string.block_screen_body),
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
