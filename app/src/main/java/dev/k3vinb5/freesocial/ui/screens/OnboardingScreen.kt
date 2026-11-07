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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.k3vinb5.freesocial.settings.PermissionStatus
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
import kotlinx.coroutines.delay

/**
 * Step indices of the first-run wizard (SPEC §2.4). Kept next to [WizardSteps.condition] so a
 * step and the system state that completes it never drift apart.
 */
private const val STEP_WELCOME = WizardSteps.WELCOME
private const val STEP_RESTRICTED = WizardSteps.RESTRICTED
private const val STEP_FILTER = WizardSteps.FILTER
private const val STEP_BLOCK = WizardSteps.BLOCK
private const val STEP_PERMISSIONS = WizardSteps.PERMISSIONS

/**
 * F1–F5 first-run wizard, shown only while [SettingsRepository.firstRunCompleted] is false.
 * Every permission step re-reads the live system state when the app resumes; when a step's own
 * condition flips to satisfied the step shows a ✓ for a short beat and then moves on by itself,
 * so returning from a system settings page never needs a manual Next. A return without a grant
 * leaves the step exactly where it was.
 */
@Composable
fun OnboardingScreen(
    settings: SettingsRepository,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }
    val autoAdvance = remember { WizardAutoAdvance() }

    LifecycleResumeEffect(settings) {
        val status = settings.refreshPermissions(context)
        autoAdvance.onResume(step, WizardSteps.condition(step, status))
        onPauseOrDispose { }
    }

    // Each step records the state it was entered with: a permission that was already granted
    // before the user saw the page never auto-skips a page they have not read.
    LaunchedEffect(step) {
        autoAdvance.onStepEntered(step, WizardSteps.condition(step, settings.permissionStatus))
    }

    // The ✓ beat: the step shows its granted label for a moment so the user sees *why* it moved,
    // then the wizard advances (the last step finishes the flow instead).
    LaunchedEffect(autoAdvance.celebratingStep) {
        val target = autoAdvance.celebratingStep ?: return@LaunchedEffect
        delay(WizardAutoAdvance.BEAT_MS)
        if (!autoAdvance.onBeatElapsed(target)) return@LaunchedEffect
        if (WizardProgress.isLast(target)) {
            settings.completeFirstRun()
            onFinish()
        } else {
            step = WizardProgress.next(target)
        }
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
                STEP_FILTER -> FilterStep(
                    settings = settings,
                    celebrating = autoAdvance.celebratingStep == STEP_FILTER,
                    onNext = goNext,
                )
                STEP_BLOCK -> BlockStep(
                    settings = settings,
                    celebrating = autoAdvance.celebratingStep == STEP_BLOCK,
                    onNext = goNext,
                )
                else -> LastPermissionsStep(
                    settings = settings,
                    celebrating = autoAdvance.celebratingStep == STEP_PERMISSIONS,
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
private fun ColumnScope.FilterStep(
    settings: SettingsRepository,
    celebrating: Boolean,
    onNext: () -> Unit,
) {
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
        status = when {
            celebrating -> stringResource(R.string.status_granted)
            enabled -> stringResource(R.string.status_on)
            else -> stringResource(R.string.status_waiting)
        },
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
private fun ColumnScope.BlockStep(
    settings: SettingsRepository,
    celebrating: Boolean,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    Spacer(Modifier.height(48.dp))
    WizardTitle(stringResource(R.string.block_optional_title))
    HorizontalGap(16.dp)
    WizardBody(stringResource(R.string.block_optional_body))

    HorizontalGap(24.dp)
    val enabled = settings.permissionStatus.blockService
    StatusListRow(
        title = stringResource(R.string.block_service),
        status = when {
            celebrating -> stringResource(R.string.status_granted)
            enabled -> stringResource(R.string.status_on)
            else -> stringResource(R.string.status_not_enabled)
        },
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
private fun ColumnScope.LastPermissionsStep(
    settings: SettingsRepository,
    celebrating: Boolean,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    Spacer(Modifier.height(48.dp))
    WizardTitle(stringResource(R.string.last_permissions_title))

    HorizontalGap(24.dp)
    PermissionCard(
        title = stringResource(R.string.perm_overlay_title),
        hint = stringResource(R.string.perm_overlay_hint),
        granted = settings.permissionStatus.overlay,
        celebrating = celebrating,
        onAllow = { openSystemSettings(context, SystemSettingsTarget.OVERLAY) },
    )
    HorizontalGap(12.dp)
    PermissionCard(
        title = stringResource(R.string.perm_battery_title),
        hint = stringResource(R.string.perm_battery_hint),
        granted = settings.permissionStatus.batteryExempt,
        celebrating = celebrating,
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
    celebrating: Boolean,
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
                        text = when {
                            granted && celebrating -> stringResource(R.string.status_granted)
                            granted -> stringResource(R.string.status_allowed)
                            else -> hint
                        },
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
    const val STEP_COUNT = WizardSteps.COUNT
    const val LAST_STEP = STEP_COUNT - 1

    fun next(step: Int): Int = (step + 1).coerceIn(0, LAST_STEP)

    fun back(step: Int): Int = (step - 1).coerceIn(0, LAST_STEP)

    fun isLast(step: Int): Boolean = step.coerceIn(0, LAST_STEP) == LAST_STEP
}

/**
 * Which live condition completes each wizard step, read from the same [PermissionStatus]
 * snapshot the step already renders — no second permission layer.
 *
 * WELCOME has nothing to grant and RESTRICTED is Android's "Allow restricted settings" menu
 * toggle, which has no queryable API, so both stay manual on purpose.
 */
internal object WizardSteps {
    const val WELCOME = 0
    const val RESTRICTED = 1
    const val FILTER = 2
    const val BLOCK = 3
    const val PERMISSIONS = 4
    const val COUNT = 5

    fun condition(step: Int, status: PermissionStatus): Boolean = when (step) {
        FILTER -> status.filterService
        BLOCK -> status.blockService
        PERMISSIONS -> status.overlay && status.batteryExempt
        else -> false
    }

    /** True for the steps that can move on by themselves once their condition flips. */
    fun autoAdvances(step: Int): Boolean = step in FILTER..PERMISSIONS
}

/**
 * Auto-advance state machine for one wizard run.
 *
 * Rules, in order:
 * 1. A step is *entered* with a baseline — its condition at the moment the page appeared.
 * 2. A lifecycle resume re-reads the condition: only a flip from false to true celebrates and
 *    advances, so a permission granted before the page was ever seen never skips it, and a
 *    resume without a grant leaves the step (and its manual Next) untouched.
 * 3. While the ✓ beat is armed, further resumes are ignored — double-resume cannot double-advance.
 * 4. Leaving the step any other way (manual Next/Back, rotation) disarms the beat, so a stale
 *    timer can never move a step the user already left.
 */
internal class WizardAutoAdvance {
    companion object {
        /** Long enough to read as "✓ granted", short enough not to feel like a stall. */
        const val BEAT_MS = 650L
    }

    private var step = WizardSteps.WELCOME
    private var baseline: Boolean? = null
    private var armed = false

    /** Step currently showing its ✓ beat, or null when idle. Compose state, so the UI redraws. */
    var celebratingStep by mutableStateOf<Int?>(null)
        private set

    /** Records the condition the step starts with; never arms a step that is already satisfied. */
    fun onStepEntered(newStep: Int, satisfied: Boolean) {
        step = newStep
        baseline = satisfied
        armed = false
        celebratingStep = null
    }

    /**
     * One resume for the current step. Returns true when this step just flipped to satisfied and
     * the caller should show the beat; false while idle, unsatisfied, or already celebrating.
     */
    fun onResume(currentStep: Int, satisfied: Boolean): Boolean {
        if (currentStep != step) {
            // Step moved without us seeing the entry (e.g. restored state): re-baseline, don't guess.
            onStepEntered(currentStep, satisfied)
            return false
        }
        if (armed) return false
        val was = baseline ?: return false
        baseline = satisfied
        if (was || !satisfied || !WizardSteps.autoAdvances(currentStep)) return false
        armed = true
        celebratingStep = currentStep
        return true
    }

    /** Called when the beat elapsed; true when [currentStep] should now be left. */
    fun onBeatElapsed(currentStep: Int): Boolean {
        if (!armed || currentStep != step) return false
        armed = false
        celebratingStep = null
        return true
    }
}
