package dev.k3vinb5.freesocial.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.core.InstalledVersionSource
import dev.k3vinb5.freesocial.core.PackageManagerVersionSource
import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.core.RuleFreshness
import dev.k3vinb5.freesocial.core.RuleStaleness
import dev.k3vinb5.freesocial.filter.CaptureSurfaces
import dev.k3vinb5.freesocial.filter.GuidedCapture
import dev.k3vinb5.freesocial.filter.RuleCatalog
import dev.k3vinb5.freesocial.settings.AppMode
import dev.k3vinb5.freesocial.settings.AppTarget
import dev.k3vinb5.freesocial.settings.SettingsRepository
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.HDivider
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.InfoBanner
import dev.k3vinb5.freesocial.ui.components.SectionCard
import dev.k3vinb5.freesocial.ui.components.SectionLabel
import dev.k3vinb5.freesocial.ui.components.SegmentedControl
import dev.k3vinb5.freesocial.ui.components.SwitchRow
import dev.k3vinb5.freesocial.ui.components.TextButton
import dev.k3vinb5.freesocial.ui.components.TopBar
import dev.k3vinb5.freesocial.ui.theme.FsColors

/** F7–F9 — one app's mode and surface toggles, plus whether its rules still match the installed app. */
@Composable
fun AppDetailScreen(
    app: AppTarget,
    settings: SettingsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versions: InstalledVersionSource = remember(context) { PackageManagerVersionSource(context) }

    var installedVersion by remember(app) { mutableStateOf(versions.versionName(app.packageName)) }
    var rules by remember(app) { mutableStateOf(enabledRulesFor(context, app)) }
    var captureMessage by remember { mutableStateOf<String?>(null) }

    // An update can land while this screen (or the target app) is in the background.
    LifecycleResumeEffect(app) {
        installedVersion = versions.versionName(app.packageName)
        rules = enabledRulesFor(context, app)
        onPauseOrDispose { }
    }

    val freshness = RuleStaleness.app(rules.map { it.verifiedAgainstAppVersion }, installedVersion)
    val verifiedStamp = rules.firstNotNullOfOrNull { it.verifiedAgainstAppVersion?.takeIf(String::isNotBlank) }

    /**
     * "Capture now" is the existing guided-capture entry: arm the session for this app's first
     * rule-bearing surface, then launch the target. The notification guides the rest; the dump
     * lands in Surfaces & rules, exactly as a capture started there would.
     */
    fun startCapture() {
        val surface = CaptureSurfaces.forApp(app, rules).firstOrNull { it.markers.isNotEmpty() }
        if (surface == null) {
            captureMessage = context.getString(R.string.staleness_capture_unavailable, context.getString(app.nameRes))
            return
        }
        captureMessage = null
        GuidedCapture.start(context, app, surface.id)
        if (!GuidedCapture.launchTarget(context, app)) {
            captureMessage = context.getString(R.string.inspector_capture_launch_failed, context.getString(app.nameRes))
            GuidedCapture.stopByUser()
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { startCapture() }

    fun captureNow() {
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            startCapture()
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FsColors.Background),
    ) {
        TopBar(title = stringResource(app.nameRes), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            if (RuleStaleness.showsBanner(freshness)) {
                HorizontalGap(16.dp)
                InfoBanner(
                    text = stringResource(
                        R.string.staleness_banner,
                        stringResource(app.nameRes),
                        RuleStaleness.majorVersion(installedVersion) ?: (installedVersion ?: ""),
                        RuleStaleness.majorVersion(verifiedStamp) ?: (verifiedStamp ?: ""),
                    ),
                )
                TextButton(
                    text = stringResource(R.string.staleness_capture_now),
                    onClick = { captureNow() },
                )
            }

            captureMessage?.let { text ->
                HorizontalGap(12.dp)
                InfoBanner(text = text, onClick = { captureMessage = null })
            }

            HorizontalGap(16.dp)
            SectionLabel(stringResource(R.string.section_mode))
            SegmentedControl(
                labels = listOf(
                    stringResource(R.string.mode_filter),
                    stringResource(R.string.mode_block),
                    stringResource(R.string.mode_allow),
                ),
                selectedIndex = settings.modeFor(app).ordinal,
                onSelect = { index -> settings.setMode(app, AppMode.entries[index]) },
            )

            HorizontalGap(16.dp)
            SectionLabel(stringResource(R.string.section_surfaces))
            SectionCard {
                app.surfaces.forEachIndexed { index, surface ->
                    SwitchRow(
                        title = stringResource(surface.labelRes),
                        checked = settings.surfaceEnabled(app, surface.key),
                        onCheckedChange = { enabled -> settings.setSurfaceEnabled(app, surface.key, enabled) },
                    )
                    if (index != app.surfaces.lastIndex) HDivider()
                }
            }

            HorizontalGap(16.dp)
            SectionCard {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(FsColors.Warning),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(app.infoRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FsColors.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            Caption(
                when {
                    freshness == RuleFreshness.FRESH ->
                        stringResource(R.string.rules_verified_for, app.rulesVersion)
                    freshness == RuleFreshness.STALE ->
                        stringResource(R.string.rules_may_be_stale, stringResource(app.nameRes))
                    installedVersion == null ->
                        stringResource(R.string.rules_app_not_installed, stringResource(app.nameRes))
                    rules.isEmpty() ->
                        stringResource(R.string.rules_none_for, stringResource(app.nameRes))
                    else ->
                        stringResource(R.string.rules_unstamped, stringResource(app.nameRes))
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The enabled catalog rules for [app] — the ones whose anchors a staleness warning is about. */
private fun enabledRulesFor(context: Context, app: AppTarget): List<Rule> =
    RuleCatalog.current(context).filter { it.enabled && it.appPackage == app.packageName }
