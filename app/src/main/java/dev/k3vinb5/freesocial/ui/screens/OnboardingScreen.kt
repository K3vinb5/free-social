package dev.k3vinb5.freesocial.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.settings.AppTarget
import dev.k3vinb5.freesocial.settings.SettingsRepository
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.PrimaryButton
import dev.k3vinb5.freesocial.ui.components.PrimaryContainerButton
import dev.k3vinb5.freesocial.ui.components.SectionCard
import dev.k3vinb5.freesocial.ui.components.SmallTintedButton
import dev.k3vinb5.freesocial.ui.components.StatusDot
import dev.k3vinb5.freesocial.ui.components.StatusListRow
import dev.k3vinb5.freesocial.ui.components.StepBadge
import dev.k3vinb5.freesocial.ui.components.TextButton
import dev.k3vinb5.freesocial.ui.components.TopBar
import dev.k3vinb5.freesocial.ui.theme.FsColors

/** Step indices of the first-run wizard (SPEC §2.4). */
private const val STEP_WELCOME = 0
private const val STEP_RESTRICTED = 1
private const val STEP_FILTER = 2
private const val STEP_BLOCK = 3
private const val STEP_PERMISSIONS = 4
private const val STEP_COUNT = 5

/**
 * F1–F5 first-run wizard, shown only while [SettingsRepository.firstRunCompleted] is false.
 * Every permission step re-reads the live system state when the app resumes, so the status
 * lines update as the user comes back from the system settings screens.
 */
@Composable
fun OnboardingScreen(
    settings: SettingsRepository,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }

    LifecycleResumeEffect(settings) {
        settings.refreshPermissions(context)
        onPauseOrDispose { }
    }

    BackHandler(enabled = step > STEP_WELCOME) { step = WizardProgress.back(step) }

    val goNext: () -> Unit = { step = WizardProgress.next(step) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FsColors.Background),
    ) {
        if (step > STEP_WELCOME) {
            TopBar(
                title = "",
                onBack = { step = WizardProgress.back(step) },
                actions = {
                    Text(
                        text = stringResource(R.string.onboarding_step, step),
                        style = MaterialTheme.typography.labelLarge,
                        color = FsColors.TextTertiary,
                    )
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            when (step) {
                STEP_WELCOME -> WelcomeStep(onStart = goNext)
                STEP_RESTRICTED -> RestrictedStep(onNext = goNext)
                STEP_FILTER -> FilterStep(settings = settings, onNext = goNext)
                STEP_BLOCK -> BlockStep(settings = settings, onNext = goNext)
                else -> LastPermissionsStep(
                    settings = settings,
                    onFinish = {
                        settings.completeFirstRun()
                        onFinish()
                    },
                )
            }
        }
    }
}

// --- F1 Welcome ---------------------------------------------------------------------------

@Composable
private fun ColumnScope.WelcomeStep(onStart: () -> Unit) {
    Spacer(Modifier.height(72.dp))
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(FsColors.Primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.welcome_monogram),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = FsColors.OnPrimary,
        )
    }
    HorizontalGap(32.dp)
    WizardTitle(stringResource(R.string.app_name))
    HorizontalGap(6.dp)
    Text(
        text = stringResource(R.string.welcome_subtitle),
        style = MaterialTheme.typography.titleSmall,
        color = FsColors.Primary,
    )
    HorizontalGap(14.dp)
    WizardBody(stringResource(R.string.welcome_body))

    HorizontalGap(24.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTarget.entries.forEach { app -> AppPill(stringResource(app.nameRes)) }
    }

    Spacer(Modifier.weight(1f))
    PrimaryButton(text = stringResource(R.string.welcome_start), onClick = onStart)
    HorizontalGap(12.dp)
    Caption(
        text = stringResource(R.string.welcome_caption),
        align = TextAlign.Center,
    )
    HorizontalGap(20.dp)
}

// --- F2 Restricted settings (1/5) ---------------------------------------------------------

@Composable
private fun ColumnScope.RestrictedStep(onNext: () -> Unit) {
    val context = LocalContext.current
    Spacer(Modifier.height(48.dp))
    WizardTitle(stringResource(R.string.restricted_title))
    HorizontalGap(16.dp)
    WizardBody(stringResource(R.string.restricted_body))

    HorizontalGap(24.dp)
    SectionCard {
        StepLine(1, stringResource(R.string.restricted_step_open_app_info))
        HorizontalGap(14.dp)
        StepLine(2, stringResource(R.string.restricted_step_menu))
        HorizontalGap(14.dp)
        StepLine(3, stringResource(R.string.restricted_step_allow))
    }

    Spacer(Modifier.weight(1f))
    PrimaryButton(
        text = stringResource(R.string.restricted_open),
        onClick = { openSystemSettings(context, SystemSettingsTarget.APP_INFO) },
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(text = stringResource(R.string.restricted_done), onClick = onNext)
    }
    HorizontalGap(8.dp)
}

// --- F3 Filter service (2/5) --------------------------------------------------------------

@Composable
private fun ColumnScope.FilterStep(settings: SettingsRepository, onNext: () -> Unit) {
    val context = LocalContext.current
    Spacer(Modifier.height(48.dp))
    WizardTitle(stringResource(R.string.filter_title))
    HorizontalGap(16.dp)
    WizardBody(stringResource(R.string.filter_body))

    HorizontalGap(24.dp)
    RecommendedChip()
    HorizontalGap(12.dp)
    val enabled = settings.permissionStatus.filterService
    StatusListRow(
        title = stringResource(R.string.filter_service),
        status = stringResource(if (enabled) R.string.status_on else R.string.status_waiting),
        statusColor = if (enabled) FsColors.Success else FsColors.Warning,
    )

    Spacer(Modifier.weight(1f))
    PrimaryButton(
        text = stringResource(R.string.filter_open),
        onClick = { openSystemSettings(context, SystemSettingsTarget.ACCESSIBILITY) },
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(text = stringResource(R.string.filter_done), onClick = onNext)
    }
    HorizontalGap(8.dp)
}

// --- F4 Block service (3/5, optional) -----------------------------------------------------

@Composable
private fun ColumnScope.BlockStep(settings: SettingsRepository, onNext: () -> Unit) {
    val context = LocalContext.current
    Spacer(Modifier.height(48.dp))
    WizardTitle(stringResource(R.string.block_optional_title))
    HorizontalGap(16.dp)
    WizardBody(stringResource(R.string.block_optional_body))

    HorizontalGap(24.dp)
    val enabled = settings.permissionStatus.blockService
    StatusListRow(
        title = stringResource(R.string.block_service),
        status = stringResource(if (enabled) R.string.status_on else R.string.status_not_enabled),
        statusColor = if (enabled) FsColors.Success else FsColors.TextTertiary,
    )

    Spacer(Modifier.weight(1f))
    PrimaryContainerButton(
        text = stringResource(R.string.block_enable),
        onClick = { openSystemSettings(context, SystemSettingsTarget.ACCESSIBILITY) },
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(text = stringResource(R.string.block_skip), onClick = onNext)
    }
    HorizontalGap(8.dp)
}

// --- F5 Last two permissions (4/5) --------------------------------------------------------

@Composable
private fun ColumnScope.LastPermissionsStep(settings: SettingsRepository, onFinish: () -> Unit) {
    val context = LocalContext.current
    Spacer(Modifier.height(48.dp))
    WizardTitle(stringResource(R.string.last_permissions_title))

    HorizontalGap(24.dp)
    PermissionCard(
        title = stringResource(R.string.perm_overlay_title),
        hint = stringResource(R.string.perm_overlay_hint),
        granted = settings.permissionStatus.overlay,
        onAllow = { openSystemSettings(context, SystemSettingsTarget.OVERLAY) },
    )
    HorizontalGap(12.dp)
    PermissionCard(
        title = stringResource(R.string.perm_battery_title),
        hint = stringResource(R.string.perm_battery_hint),
        granted = settings.permissionStatus.batteryExempt,
        onAllow = { openSystemSettings(context, SystemSettingsTarget.BATTERY) },
    )

    Spacer(Modifier.weight(1f))
    PrimaryButton(text = stringResource(R.string.perm_finish), onClick = onFinish)
    HorizontalGap(20.dp)
}

/** Card with an "Allow" pill on the right; swaps to a success line once granted. */
@Composable
private fun PermissionCard(
    title: String,
    hint: String,
    granted: Boolean,
    onAllow: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = FsColors.TextPrimary,
                )
                HorizontalGap(2.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (granted) {
                        StatusDot(FsColors.Success, diameter = 6.dp)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(
                        text = if (granted) stringResource(R.string.status_allowed) else hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (granted) FsColors.Success else FsColors.TextTertiary,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            if (!granted) {
                SmallTintedButton(text = stringResource(R.string.perm_allow), onClick = onAllow)
            }
        }
    }
}

// --- Shared bits --------------------------------------------------------------------------

@Composable
private fun WizardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = FsColors.TextPrimary,
    )
}

@Composable
private fun WizardBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = FsColors.TextSecondary,
    )
}

@Composable
private fun StepLine(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepBadge(number)
        Spacer(Modifier.size(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = FsColors.TextPrimary,
        )
    }
}

@Composable
private fun RecommendedChip() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(FsColors.PrimaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(FsColors.Primary, diameter = 6.dp)
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.filter_chip),
            style = MaterialTheme.typography.labelLarge,
            color = FsColors.Primary,
        )
    }
}

@Composable
private fun AppPill(label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(FsColors.Card)
            .height(36.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(FsColors.Primary, diameter = 6.dp)
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = FsColors.TextSecondary,
        )
    }
}

/**
 * Pure wizard progress model, kept out of Compose so the step chain is unit-testable. Every
 * step must be able to move forward exactly once; the last step finishes the flow.
 */
internal object WizardProgress {
    /** Welcome + 4 permission steps (SPEC §2.4). */
    const val STEP_COUNT = 5
    const val LAST_STEP = STEP_COUNT - 1

    fun next(step: Int): Int = (step + 1).coerceIn(0, LAST_STEP)

    fun back(step: Int): Int = (step - 1).coerceIn(0, LAST_STEP)

    fun isLast(step: Int): Boolean = step.coerceIn(0, LAST_STEP) == LAST_STEP
}
