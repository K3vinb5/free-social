package dev.k3vinb5.freesocial.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.settings.AppTarget
import dev.k3vinb5.freesocial.settings.SettingsRepository
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.ListRow
import dev.k3vinb5.freesocial.ui.components.SectionCard
import dev.k3vinb5.freesocial.ui.components.SectionLabel
import dev.k3vinb5.freesocial.ui.components.StateChip
import dev.k3vinb5.freesocial.ui.components.StatusPill
import dev.k3vinb5.freesocial.ui.components.SwitchRow
import dev.k3vinb5.freesocial.ui.components.TopBar
import dev.k3vinb5.freesocial.ui.theme.FsColors
import java.time.ZonedDateTime

/** F6 — settings home. */
@Composable
fun SettingsHubScreen(
    settings: SettingsRepository,
    now: ZonedDateTime,
    onOpenApp: (AppTarget) -> Unit,
    onOpenBudget: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenRuleInspector: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Permission grants happen in system screens; re-read them whenever we come back.
    val context = LocalContext.current
    LifecycleResumeEffect(settings) {
        settings.refreshPermissions(context)
        onPauseOrDispose { }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FsColors.Background),
    ) {
        TopBar(
            title = stringResource(R.string.app_name),
            actions = {
                StatusPill(
                    text = stringResource(
                        if (settings.filteringEnabled) R.string.hub_status_on else R.string.hub_status_off,
                    ),
                    color = if (settings.filteringEnabled) FsColors.Success else FsColors.TextTertiary,
                )
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            HorizontalGap(16.dp)
            SectionCard {
                SwitchRow(
                    title = stringResource(R.string.hub_master_title),
                    subtitle = stringResource(R.string.hub_master_subtitle),
                    checked = settings.filteringEnabled,
                    onCheckedChange = settings::updateFiltering,
                )
            }

            SectionLabel(stringResource(R.string.hub_apps_section))

            AppTarget.entries.forEach { app ->
                ListRow(
                    title = stringResource(app.nameRes),
                    chevron = true,
                    onClick = { onOpenApp(app) },
                    trailing = { StateChip(settings.modeFor(app)) },
                )
                Spacer(Modifier.height(8.dp))
            }

            HorizontalGap(8.dp)

            ListRow(
                title = stringResource(R.string.hub_budget_row),
                value = stringResource(R.string.hub_min_left, settings.remainingMinutesCeil(now)),
                chevron = true,
                onClick = onOpenBudget,
            )
            Spacer(Modifier.height(8.dp))
            ListRow(
                title = stringResource(R.string.hub_schedule_row),
                value = scheduleSummary(settings, now),
                chevron = true,
                onClick = onOpenSchedule,
            )
            Spacer(Modifier.height(8.dp))
            ListRow(
                title = stringResource(R.string.hub_permissions_row),
                value = stringResource(
                    R.string.hub_permissions_value,
                    settings.permissionStatus.grantedCount,
                ),
                chevron = true,
                onClick = onOpenPermissions,
            )

            HorizontalGap(16.dp)
            Caption(stringResource(R.string.hub_caption))

            HorizontalGap(16.dp)
            ListRow(
                title = stringResource(R.string.hub_rule_inspector),
                subtitle = stringResource(R.string.hub_rule_inspector_dev),
                chevron = true,
                onClick = onOpenRuleInspector,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** "On · 22:00–07:30" — the effective window (wind-down/morning-lock applied) or "Off". */
@Composable
private fun scheduleSummary(settings: SettingsRepository, now: ZonedDateTime): String {
    val window = settings.tonightLockWindow(now) ?: return stringResource(R.string.schedule_off)
    return stringResource(
        R.string.hub_schedule_value,
        SettingsRepository.formatClock(window.startMinutes),
        SettingsRepository.formatClock(window.endMinutes),
    )
}
