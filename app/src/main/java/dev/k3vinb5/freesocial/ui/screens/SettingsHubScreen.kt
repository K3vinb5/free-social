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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.filter.EngineHealth
import dev.k3vinb5.freesocial.settings.AppMode
import dev.k3vinb5.freesocial.settings.AppTarget
import dev.k3vinb5.freesocial.settings.SettingsRepository
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.InfoBanner
import dev.k3vinb5.freesocial.ui.components.ListRow
import dev.k3vinb5.freesocial.ui.components.SectionCard
import dev.k3vinb5.freesocial.ui.components.SectionLabel
import dev.k3vinb5.freesocial.ui.components.StateChip
import dev.k3vinb5.freesocial.ui.components.StatusPill
import dev.k3vinb5.freesocial.ui.components.SwitchRow
import dev.k3vinb5.freesocial.ui.components.TopBar
import dev.k3vinb5.freesocial.ui.theme.FsColors
import java.time.ZonedDateTime

/** What the hub's top pill is allowed to claim, given the master switch and the live service state. */
internal enum class HubEnforcement { ON, PAUSED, OFF }

/** Which persistent banner the hub shows, if any (SPEC §2.4). */
internal enum class HubBanner { NONE, SERVICE_OFF, ENGINE_STALE }

/**
 * The pill/banner decision, pure so the truth table is testable without Android (SPEC §2.4:
 * the pill reflects LIVE enforcement, not just the master switch).
 */
internal fun hubEnforcement(masterEnabled: Boolean, serviceEnabled: Boolean): HubEnforcement = when {
    !masterEnabled -> HubEnforcement.OFF
    serviceEnabled -> HubEnforcement.ON
    else -> HubEnforcement.PAUSED
}

/** A degraded state must show the persistent banner: master on but nothing is being filtered. */
internal fun hubEnforcementDegraded(state: HubEnforcement): Boolean = state == HubEnforcement.PAUSED

/**
 * D7: the banner decision. Master off means the user turned filtering off, so no banner. A disabled
 * service wins over a dead engine (the fix is the same Permissions screen). Master + service on but
 * no recent heartbeat is the silent-death case this banner exists for.
 */
internal fun hubBanner(
    masterEnabled: Boolean,
    serviceEnabled: Boolean,
    engineStale: Boolean,
): HubBanner = when {
    !masterEnabled -> HubBanner.NONE
    !serviceEnabled -> HubBanner.SERVICE_OFF
    engineStale -> HubBanner.ENGINE_STALE
    else -> HubBanner.NONE
}

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
    var engineStale by remember { mutableStateOf(EngineHealth.isStale(context)) }
    LifecycleResumeEffect(settings) {
        settings.refreshPermissions(context)
        engineStale = EngineHealth.isStale(context)
        onPauseOrDispose { }
    }

    // Live truth: the master switch alone does not mean anything is actually filtered.
    val enforcement = hubEnforcement(
        masterEnabled = settings.filteringEnabled,
        serviceEnabled = settings.permissionStatus.filterService,
    )
    val pill = when (enforcement) {
        HubEnforcement.ON -> R.string.hub_status_on to FsColors.Success
        HubEnforcement.PAUSED -> R.string.hub_status_paused to FsColors.Warning
        HubEnforcement.OFF -> R.string.hub_status_off to FsColors.TextTertiary
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
                    text = stringResource(pill.first),
                    color = pill.second,
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

            val banner = hubBanner(
                masterEnabled = settings.filteringEnabled,
                serviceEnabled = settings.permissionStatus.filterService,
                engineStale = engineStale,
            )
            if (banner != HubBanner.NONE) {
                HorizontalGap(12.dp)
                InfoBanner(
                    text = stringResource(
                        when (banner) {
                            HubBanner.SERVICE_OFF -> R.string.hub_banner_service_off
                            HubBanner.ENGINE_STALE -> R.string.hub_banner_engine_stale
                            HubBanner.NONE -> R.string.hub_banner_service_off
                        },
                    ),
                    onClick = onOpenPermissions,
                )
            }

            SectionLabel(stringResource(R.string.hub_apps_section))

            AppTarget.entries.forEach { app ->
                ListRow(
                    title = stringResource(app.nameRes),
                    chevron = true,
                    onClick = { onOpenApp(app) },
                    trailing = {
                        val mode = settings.modeFor(app)
                        if (enforcement != HubEnforcement.ON && mode == AppMode.FILTER) {
                            // Configured to filter, but enforcement is not running right now.
                            StatusPill(
                                text = stringResource(R.string.chip_filter_paused),
                                color = FsColors.Warning,
                            )
                        } else {
                            StateChip(mode)
                        }
                    },
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
