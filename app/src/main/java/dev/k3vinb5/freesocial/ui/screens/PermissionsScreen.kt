package dev.k3vinb5.freesocial.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.settings.SettingsRepository
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.InfoBanner
import dev.k3vinb5.freesocial.ui.components.StatusListRow
import dev.k3vinb5.freesocial.ui.components.TopBar
import dev.k3vinb5.freesocial.ui.theme.FsColors

/** The system screens this app deep-links into (permissions page F12 and wizard F2/F3/F5). */
internal enum class SystemSettingsTarget {
    /** App info — where Android 13+'s "Allow restricted settings" lives. */
    APP_INFO,
    ACCESSIBILITY,
    OVERLAY,
    BATTERY,
}

/**
 * Opens the system settings screen for [target]. Best-effort: OEM builds occasionally lack one
 * of these activities, in which case nothing happens rather than crashing mid-setup.
 */
internal fun openSystemSettings(context: Context, target: SystemSettingsTarget) {
    val packageUri = Uri.fromParts("package", context.packageName, null)
    val intent = when (target) {
        SystemSettingsTarget.APP_INFO ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        SystemSettingsTarget.ACCESSIBILITY ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        SystemSettingsTarget.OVERLAY ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
        SystemSettingsTarget.BATTERY ->
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
    }
    runCatching { context.startActivity(intent) }
}

/**
 * F12 — live permission state with one-tap jumps into the system screens. State is re-read on
 * every resume, because the user grants permissions in other activities.
 */
@Composable
fun PermissionsScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LifecycleResumeEffect(settings) {
        settings.refreshPermissions(context)
        onPauseOrDispose { }
    }

    val status = settings.permissionStatus
    val openSettings = remember(context) {
        { target: SystemSettingsTarget -> openSystemSettings(context, target) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FsColors.Background),
    ) {
        TopBar(title = stringResource(R.string.permissions_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            HorizontalGap(16.dp)
            PermissionRow(
                title = stringResource(R.string.permissions_service),
                status = status.filterService,
                statusLabel = if (status.filterService) R.string.status_on else R.string.status_waiting,
                onOpen = { openSettings(SystemSettingsTarget.ACCESSIBILITY) },
            )
            HorizontalGap(8.dp)
            PermissionRow(
                title = stringResource(R.string.block_service),
                status = status.blockService,
                statusLabel = if (status.blockService) R.string.status_on_optional else R.string.status_off_optional,
                optional = true,
                onOpen = { openSettings(SystemSettingsTarget.ACCESSIBILITY) },
            )
            HorizontalGap(8.dp)
            PermissionRow(
                title = stringResource(R.string.perm_overlay_title),
                status = status.overlay,
                statusLabel = if (status.overlay) R.string.status_allowed else R.string.status_needed,
                onOpen = { openSettings(SystemSettingsTarget.OVERLAY) },
            )
            HorizontalGap(8.dp)
            PermissionRow(
                title = stringResource(R.string.perm_battery_title),
                status = status.batteryExempt,
                statusLabel = if (status.batteryExempt) R.string.status_allowed else R.string.status_needed,
                onOpen = { openSettings(SystemSettingsTarget.BATTERY) },
            )

            HorizontalGap(16.dp)
            InfoBanner(text = stringResource(R.string.permissions_banner))
            HorizontalGap(24.dp)
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    status: Boolean,
    statusLabel: Int,
    onOpen: () -> Unit,
    optional: Boolean = false,
) {
    val color = when {
        status -> FsColors.Success
        optional -> FsColors.TextTertiary
        else -> FsColors.Warning
    }
    StatusListRow(
        title = title,
        status = stringResource(statusLabel),
        statusColor = color,
        actionLabel = stringResource(R.string.permissions_open_settings),
        onAction = onOpen,
    )
}
