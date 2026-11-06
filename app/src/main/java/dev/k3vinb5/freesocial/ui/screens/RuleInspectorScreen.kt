package dev.k3vinb5.freesocial.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.filter.CaptureOutcome
import dev.k3vinb5.freesocial.filter.CapturePhase
import dev.k3vinb5.freesocial.filter.CaptureSurfaces
import dev.k3vinb5.freesocial.filter.DumpAnalysis
import dev.k3vinb5.freesocial.filter.DumpExporter
import dev.k3vinb5.freesocial.filter.DumpFile
import dev.k3vinb5.freesocial.filter.GuidedCapture
import dev.k3vinb5.freesocial.filter.RuleCatalog
import dev.k3vinb5.freesocial.filter.RuleOverride
import dev.k3vinb5.freesocial.filter.RuleProvenance
import dev.k3vinb5.freesocial.settings.AppTarget
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.ChoiceChip
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.InfoBanner
import dev.k3vinb5.freesocial.ui.components.ListRow
import dev.k3vinb5.freesocial.ui.components.PrimaryButton
import dev.k3vinb5.freesocial.ui.components.SecondaryButton
import dev.k3vinb5.freesocial.ui.components.SectionCard
import dev.k3vinb5.freesocial.ui.components.SectionLabel
import dev.k3vinb5.freesocial.ui.components.TextButton
import dev.k3vinb5.freesocial.ui.components.TopBar
import dev.k3vinb5.freesocial.ui.theme.FsColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private const val TAG = "SurfacesRules"
private const val PREFS = "capture_guide"
private const val KEY_NOTIF_ASKED = "notif_requested"

/**
 * F14 — surfaces & rules.
 *
 * Plain-language, normal feature. "Capture nodes" launches the target app, guides the user with a
 * notification (or an in-app step card when notifications are off), and records only once the
 * surface's own markers are detected. Dumps are surface-labelled; expanding one shows exactly the
 * enabled rules that match it. Rules can be exported, edited in any text app and imported back at
 * runtime, so a moved anchor heals without a new APK.
 */
@Composable
fun RuleInspectorScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val apps = AppTarget.entries
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val app = apps[selected]
    val context = LocalContext.current

    var dumps by remember { mutableStateOf(DumpExporter.list(context)) }
    var rules by remember { mutableStateOf(RuleCatalog.current(context)) }
    var rulesSource by remember { mutableStateOf(RuleCatalog.source) }
    var rulesError by remember { mutableStateOf(RuleCatalog.lastError) }
    var overrideImportedAt by remember { mutableStateOf(RuleCatalog.overrideImportedAt(context)) }
    var confirmRevert by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf(GuidedCapture.session) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var stepCard by remember { mutableStateOf<CaptureSurfaces.Surface?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refreshCatalog() {
        rules = RuleCatalog.current(context)
        rulesSource = RuleCatalog.source
        rulesError = RuleCatalog.lastError
        overrideImportedAt = RuleCatalog.overrideImportedAt(context)
    }

    val surfaces = remember(app, rules) { CaptureSurfaces.forApp(app, rules) }

    fun startCapture(surface: CaptureSurfaces.Surface) {
        GuidedCapture.start(context, app, surface.id)
        session = GuidedCapture.session
        if (!GuidedCapture.launchTarget(context, app)) {
            message = context.getString(R.string.inspector_capture_launch_failed, context.getString(app.nameRes))
            GuidedCapture.stopByUser()
        }
        stepCard = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = stepCard
        stepCard = null
        if (pending != null) {
            if (granted) startCapture(pending) else stepCard = pending
        }
    }

    fun beginCapture(surface: CaptureSurfaces.Surface) {
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        when {
            granted -> startCapture(surface)
            notificationsRequested(context) -> stepCard = surface
            else -> {
                markNotificationsRequested(context)
                stepCard = surface
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Coming back from the target app: pick up the dump / notification result.
    LifecycleResumeEffect(Unit) {
        dumps = DumpExporter.list(context)
        refreshCatalog()
        session = GuidedCapture.session
        onPauseOrDispose { }
    }

    // While a capture runs, mirror its phase; on the finish edge reload the dump list.
    LaunchedEffect(session?.phase) {
        var current = session
        while (current != null && current.phase != CapturePhase.FINISHED) {
            delay(500)
            current = GuidedCapture.session
            session = current
        }
        dumps = DumpExporter.list(context)
        refreshCatalog()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val text = RuleCatalog.exportText(context)
            val wrote = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }.isSuccess
            message = if (wrote) {
                context.getString(R.string.inspector_exported, uri.lastPathSegment ?: uri.toString())
            } else {
                context.getString(R.string.inspector_import_failed)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            message = if (text == null) {
                context.getString(R.string.inspector_import_failed)
            } else {
                when (val result = RuleCatalog.applyOverride(context, text)) {
                    is RuleCatalog.ApplyResult.Ok -> {
                        refreshCatalog()
                        context.getString(R.string.inspector_import_ok, result.rules.size)
                    }
                    is RuleCatalog.ApplyResult.Invalid ->
                        context.getString(R.string.inspector_import_rejected, result.error)
                    is RuleCatalog.ApplyResult.Failed ->
                        context.getString(R.string.inspector_import_rejected, result.error)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FsColors.Background),
    ) {
        TopBar(title = stringResource(R.string.inspector_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            HorizontalGap(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                apps.forEachIndexed { index, target ->
                    ChoiceChip(
                        label = stringResource(target.nameRes),
                        selected = index == selected,
                        onClick = { selected = index },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            CaptureStatus(session = session, onStop = { GuidedCapture.stopByUser() })

            message?.let { text ->
                HorizontalGap(12.dp)
                InfoBanner(text = text, onClick = { message = null })
            }

            SectionLabel(stringResource(R.string.inspector_captured))
            surfaces.forEach { surface ->
                ListRow(
                    title = surface.displayName,
                    subtitle = if (surface.markers.isEmpty()) {
                        stringResource(R.string.inspector_no_markers)
                    } else {
                        stringResource(R.string.inspector_markers, surface.markers.size)
                    },
                    value = stringResource(R.string.inspector_capture_nodes),
                    onClick = if (surface.markers.isEmpty()) null else ({ beginCapture(surface) }),
                )
                Spacer(Modifier.height(8.dp))
            }

            stepCard?.let { surface ->
                HorizontalGap(4.dp)
                StepCard(
                    app = app,
                    surface = surface,
                    onStart = { startCapture(surface) },
                    onCancel = { stepCard = null },
                )
                HorizontalGap(8.dp)
            }

            SectionLabel(stringResource(R.string.inspector_dumps))
            Caption(stringResource(R.string.inspector_dumps_housekeeping))
            if (dumps.isEmpty()) {
                Caption(stringResource(R.string.inspector_no_dumps))
            } else {
                dumps.forEach { dump ->
                    val key = dump.file.name
                    val open = expanded == key
                    DumpRow(
                        dump = dump,
                        expanded = open,
                        rules = rules,
                        onToggle = { expanded = if (open) null else key },
                        onShare = { shareDump(context, dump) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            SectionLabel(stringResource(R.string.inspector_rules))
            RulesSection(
                rules = rules,
                source = rulesSource,
                error = rulesError,
                overridePath = RuleCatalog.overridePath(context),
                importedAt = overrideImportedAt,
                onReviewStale = {
                    message = context.getString(
                        R.string.inspector_override_path,
                        RuleCatalog.overridePath(context),
                    )
                },
            )
            HorizontalGap(12.dp)
            if (rulesSource == RuleOverride.Source.OVERRIDE) {
                SecondaryButton(
                    text = stringResource(R.string.inspector_rules_use_builtin),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmRevert = true },
                )
                HorizontalGap(10.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.inspector_export),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // Always write the effective rules to the override path first (it is a
                        // valid document), then let the user pick a text app to edit a copy.
                        val text = RuleCatalog.exportText(context)
                        val path = RuleCatalog.overrideFile(context).absolutePath
                        runCatching { RuleCatalog.overrideFile(context).writeText(text) }
                        message = context.getString(R.string.inspector_exported, path)
                        exportLauncher.launch(RuleOverride.FILE_NAME)
                    },
                )
                SecondaryButton(
                    text = stringResource(R.string.inspector_import),
                    modifier = Modifier.weight(1f),
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                )
            }

            if (confirmRevert) {
                AlertDialog(
                    onDismissRequest = { confirmRevert = false },
                    title = {
                        Text(
                            text = stringResource(R.string.inspector_rules_use_builtin),
                            style = MaterialTheme.typography.titleMedium,
                            color = FsColors.TextPrimary,
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.inspector_rules_revert_confirm),
                            style = MaterialTheme.typography.bodyMedium,
                            color = FsColors.TextSecondary,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            text = stringResource(R.string.inspector_rules_use_builtin),
                            onClick = {
                                confirmRevert = false
                                val reverted = RuleCatalog.revertOverride(context)
                                refreshCatalog()
                                message = context.getString(
                                    if (reverted) {
                                        R.string.inspector_rules_reverted
                                    } else {
                                        R.string.inspector_rules_revert_failed
                                    },
                                )
                            },
                        )
                    },
                    dismissButton = {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { confirmRevert = false },
                        )
                    },
                    containerColor = FsColors.Surface,
                )
            }

            HorizontalGap(16.dp)
            Caption(stringResource(R.string.inspector_caption))
            HorizontalGap(24.dp)
        }
    }
}

/** The live capture card: phase, plain-language instruction, and an in-app Stop. */
@Composable
private fun CaptureStatus(session: GuidedCapture.Session?, onStop: () -> Unit) {
    if (session == null) return
    val text = when (session.phase) {
        CapturePhase.WAITING -> stringResource(R.string.inspector_capture_waiting, session.surfaceName)
        CapturePhase.RECORDING -> stringResource(R.string.inspector_capture_recording, session.surfaceName)
        CapturePhase.FINISHED -> when (session.outcome) {
            CaptureOutcome.COMPLETE ->
                stringResource(R.string.inspector_capture_done, session.nodeCount ?: 0)
            CaptureOutcome.SURFACE_LOST ->
                stringResource(R.string.inspector_capture_done, session.nodeCount ?: 0)
            CaptureOutcome.TIMEOUT -> stringResource(R.string.inspector_capture_timeout)
            CaptureOutcome.CANCELLED -> stringResource(R.string.inspector_capture_cancelled)
            CaptureOutcome.FAILED -> stringResource(R.string.inspector_capture_failed)
            else -> stringResource(R.string.inspector_capture_cancelled)
        }
    }
    val color = when {
        session.phase == CapturePhase.FINISHED && session.outcome == CaptureOutcome.COMPLETE -> FsColors.Success
        session.phase == CapturePhase.FINISHED -> FsColors.Warning
        else -> FsColors.Primary
    }
    HorizontalGap(12.dp)
    InfoBanner(
        text = text,
        color = color,
        onClick = if (session.phase != CapturePhase.FINISHED) onStop else null,
    )
}

/** The fallback guidance shown before launch when POST_NOTIFICATIONS is denied. */
@Composable
private fun StepCard(
    app: AppTarget,
    surface: CaptureSurfaces.Surface,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    SectionCard {
        Text(
            text = stringResource(
                R.string.inspector_step_title,
                stringResource(app.nameRes),
                surface.displayName,
            ),
            color = FsColors.TextPrimary,
        )
        HorizontalGap(8.dp)
        Caption(
            stringResource(
                R.string.inspector_step_body,
                stringResource(app.nameRes),
                surface.displayName,
            ),
        )
        HorizontalGap(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(text = stringResource(R.string.inspector_step_start), onClick = onStart)
            TextButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
        }
    }
}

/** One dump row; expanding it shows the enabled rules that match its exported nodes. */
@Composable
private fun DumpRow(
    dump: DumpFile,
    expanded: Boolean,
    rules: List<Rule>,
    onToggle: () -> Unit,
    onShare: () -> Unit,
) {
    val document = dump.document
    val surfaceLabel = when {
        document.surfaceVerified -> CaptureSurfaces.displayName(document.surface ?: document.screen)
        else -> stringResource(R.string.inspector_unknown_surface_empty)
    }
    ListRow(
        title = surfaceLabel,
        subtitle = stringResource(
            R.string.inspector_dump_subtitle,
            document.appPackage,
            document.capturedAt,
        ),
        value = stringResource(R.string.inspector_nodes_count, dump.nodeCount),
        chevron = true,
        onClick = onToggle,
    )
    if (!expanded) return

    val unknownText = if (document.markersSeen.isEmpty()) {
        stringResource(R.string.inspector_unknown_surface_empty)
    } else {
        stringResource(R.string.inspector_unknown_surface, document.markersSeen.joinToString(", "))
    }
    SectionCard {
        Text(
            text = if (document.surfaceVerified) {
                stringResource(R.string.inspector_verified_surface) + " · " + document.surface
            } else {
                unknownText
            },
            color = if (document.surfaceVerified) FsColors.Success else FsColors.Warning,
        )
        HorizontalGap(8.dp)
        val matches = remember(document, rules) {
            DumpAnalysis.matches(document.nodes, rules, document.appPackage)
        }
        Text(
            text = stringResource(R.string.inspector_analysis),
            color = FsColors.TextSecondary,
        )
        if (matches.isEmpty()) {
            HorizontalGap(4.dp)
            Caption(stringResource(R.string.inspector_no_rule_match))
        } else {
            matches.forEach { match ->
                HorizontalGap(8.dp)
                Text(text = "${match.rule.id} · ${match.rule.surface}", color = FsColors.TextPrimary)
                match.anchors.forEach { anchor -> Caption(anchor) }
            }
        }
        HorizontalGap(10.dp)
        TextButton(
            text = stringResource(R.string.inspector_dump_share),
            onClick = onShare,
        )
    }
}

@Composable
private fun RulesSection(
    rules: List<Rule>,
    source: RuleOverride.Source,
    error: String?,
    overridePath: String,
    importedAt: Long?,
    onReviewStale: () -> Unit,
) {
    val sourceText = when (source) {
        RuleOverride.Source.OVERRIDE ->
            stringResource(R.string.inspector_rules_source_override, RuleOverride.FILE_NAME)
        RuleOverride.Source.ASSETS -> stringResource(R.string.inspector_rules_source_assets)
    }
    Caption(sourceText)
    if (source == RuleOverride.Source.OVERRIDE && importedAt != null) {
        Caption(stringResource(R.string.inspector_override_imported, formatImportTime(importedAt)))
        if (RuleOverride.isStale(importedAt, System.currentTimeMillis())) {
            HorizontalGap(8.dp)
            InfoBanner(
                text = stringResource(R.string.inspector_override_stale),
                color = FsColors.Warning,
                onClick = onReviewStale,
            )
        }
    }
    Caption(stringResource(R.string.inspector_override_path, overridePath))
    if (error != null) {
        HorizontalGap(8.dp)
        InfoBanner(text = stringResource(R.string.inspector_override_error, error))
    }
    rules.forEach { rule ->
        HorizontalGap(8.dp)
        SectionCard {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = rule.id, color = FsColors.TextPrimary)
                    val target = AppTarget.entries.firstOrNull { it.packageName == rule.appPackage }
                    Caption(
                        (target?.let { stringResource(it.nameRes) } ?: rule.appPackage) +
                            " · " + rule.surface,
                    )
                }
                Text(
                    text = stringResource(
                        if (rule.enabled) R.string.inspector_rule_enabled else R.string.inspector_rule_disabled,
                    ),
                    color = if (rule.enabled) FsColors.Success else FsColors.TextTertiary,
                )
            }
            HorizontalGap(4.dp)
            Caption(
                RuleProvenance.versionFor(rule)?.let {
                    stringResource(R.string.inspector_last_verified, it)
                } ?: stringResource(R.string.inspector_not_verified),
            )
        }
    }
}

/** "Imported 5 Nov, 16:47" — the override's file time in the device's zone. */
private fun formatImportTime(millis: Long): String =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun notificationsRequested(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTIF_ASKED, false)

private fun markNotificationsRequested(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_NOTIF_ASKED, true)
        .apply()
}

private fun shareDump(context: Context, file: DumpFile) {
    val text = try {
        file.file.readText()
    } catch (t: Throwable) {
        Log.w(TAG, "cannot read dump ${file.file.name}", t)
        return
    }
    val chooser = Intent.createChooser(
        DumpExporter.shareIntent(file.document, text),
        context.getString(R.string.inspector_dump_share),
    )
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // A headless dev image may have no share target; the file is still written.
    }
}
