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
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private const val TAG = "SurfacesRules"
private const val PREFS = "capture_guide"
private const val KEY_NOTIF_ASKED = "notif_requested"

/**
 * The import picker's request. The **base type**, and only the base type, decides what this
 * ColorOS DocumentsUI lists: with `Intent.EXTRA_MIME_TYPES` present it applies a filter that hides
 * the `application/json` rows (the exported `rules-override.json` and every edited copy), while
 * `am start -a OPEN_DOCUMENT -t application/json` on the same device lists them immediately
 * (`reverse/runtime-reno/heal/`, 2026-11-07). So the intent asks for JSON and passes no extra list —
 * exactly the document Export writes. The picker is not the validator: [RuleOverride.parse] rejects
 * anything that is not a valid rule document.
 */
internal const val IMPORT_BASE_TYPE = "application/json"

/** `ACTION_OPEN_DOCUMENT` for one readable rule document (see [IMPORT_BASE_TYPE]). */
private fun importDocumentIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    .addCategory(Intent.CATEGORY_OPENABLE)
    .setType(IMPORT_BASE_TYPE)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

/** Which banner the Export button leaves on screen. */
internal enum class ExportMessage { EXPORTED, REJECTED }

/**
 * What pressing **Export rules** must do after publishing the effective rules to the override
 * path, decided from the catalog's result alone so it can be tested without a screen.
 *
 * [adopted] gates the catalog refresh: only an adopted document may make the screen read
 * *Your override* and offer *Use built-in rules*. [openPicker] gates the "save a copy elsewhere"
 * picker — a document that was **not** adopted must never be offered for saving as if it had been,
 * and must never leave an "exported" banner behind (device regression, 2026-11-07: the failure
 * banner was overwritten by the picker callback's success text).
 */
internal data class ExportEffect(
    val adopted: Boolean,
    val openPicker: Boolean,
    val message: ExportMessage,
)

internal fun exportEffect(result: RuleCatalog.ApplyResult): ExportEffect = when (result) {
    is RuleCatalog.ApplyResult.Ok ->
        ExportEffect(adopted = true, openPicker = true, message = ExportMessage.EXPORTED)
    is RuleCatalog.ApplyResult.Invalid ->
        ExportEffect(adopted = false, openPicker = false, message = ExportMessage.REJECTED)
    is RuleCatalog.ApplyResult.Failed ->
        ExportEffect(adopted = false, openPicker = false, message = ExportMessage.REJECTED)
}

/** The validator's / file system's reason for a rejected document, or null on success. */
internal fun RuleCatalog.ApplyResult.errorOrNull(): String? = when (this) {
    is RuleCatalog.ApplyResult.Ok -> null
    is RuleCatalog.ApplyResult.Invalid -> error
    is RuleCatalog.ApplyResult.Failed -> error
}

/** Which banner the Import picker leaves on screen; only [IMPORTED] may claim the rules changed. */
internal enum class RuleApplyMessage { IMPORTED, REJECTED }

internal fun RuleCatalog.ApplyResult.importMessage(): RuleApplyMessage = when (this) {
    is RuleCatalog.ApplyResult.Ok -> RuleApplyMessage.IMPORTED
    is RuleCatalog.ApplyResult.Invalid -> RuleApplyMessage.REJECTED
    is RuleCatalog.ApplyResult.Failed -> RuleApplyMessage.REJECTED
}

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
    var pendingDelete by remember { mutableStateOf<DumpDelete?>(null) }

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
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            message = if (text == null) {
                context.getString(R.string.inspector_import_failed)
            } else {
                val applied = RuleCatalog.applyOverride(context, text)
                when (applied.importMessage()) {
                    RuleApplyMessage.IMPORTED -> {
                        refreshCatalog()
                        context.getString(
                            R.string.inspector_import_ok,
                            (applied as RuleCatalog.ApplyResult.Ok).rules.size,
                        )
                    }
                    RuleApplyMessage.REJECTED ->
                        context.getString(
                            R.string.inspector_import_rejected,
                            applied.errorOrNull().orEmpty(),
                        )
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
                        // Three equal columns on a 1080 px screen leave ~101 dp each; the default
                        // 16 dp padding + labelLarge wrapped "Instagram" to "Instagra / m".
                        horizontalPadding = 8.dp,
                        labelStyle = MaterialTheme.typography.labelMedium,
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
                Caption(stringResource(R.string.inspector_dumps_delete_hint))
                dumps.forEach { dump ->
                    val key = dump.file.name
                    val open = expanded == key
                    DumpRow(
                        dump = dump,
                        expanded = open,
                        rules = rules,
                        onToggle = { expanded = if (open) null else key },
                        onShare = { shareDump(context, dump) },
                        onDelete = { pendingDelete = DumpDelete.One(dump) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                SecondaryButton(
                    text = stringResource(R.string.inspector_dumps_delete_all),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { pendingDelete = DumpDelete.All },
                )
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
                        // Publish the effective rules to the override path *through the catalog*,
                        // so the screen state matches the disk (source=OVERRIDE, import time, the
                        // "Use built-in rules" button) instead of claiming "Built-in rules" while
                        // a file is on disk that shadows them at the next process start.
                        val applied = RuleCatalog.applyOverride(
                            context,
                            RuleCatalog.exportText(context),
                        )
                        val effect = exportEffect(applied)
                        if (effect.adopted) refreshCatalog()
                        message = when (effect.message) {
                            ExportMessage.EXPORTED -> context.getString(
                                R.string.inspector_exported,
                                RuleCatalog.overridePath(context),
                            )
                            ExportMessage.REJECTED -> context.getString(
                                R.string.inspector_import_rejected,
                                applied.errorOrNull().orEmpty(),
                            )
                        }
                        // A document that was not adopted is not offered for saving as if it
                        // had been: the failure banner stays the last thing the user sees.
                        if (effect.openPicker) exportLauncher.launch(RuleOverride.FILE_NAME)
                    },
                )
                SecondaryButton(
                    text = stringResource(R.string.inspector_import),
                    modifier = Modifier.weight(1f),
                    onClick = { importLauncher.launch(importDocumentIntent()) },
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

            pendingDelete?.let { target ->
                AlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    title = {
                        Text(
                            text = stringResource(R.string.inspector_delete_dialog_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = FsColors.TextPrimary,
                        )
                    },
                    text = {
                        Text(
                            text = when (target) {
                                is DumpDelete.One -> stringResource(R.string.inspector_dump_delete_confirm)
                                DumpDelete.All -> stringResource(
                                    R.string.inspector_dumps_delete_all_confirm,
                                    dumps.size,
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = FsColors.TextSecondary,
                        )
                    },
                    // Destructive: only this button deletes. A row or "Delete all" merely arms the
                    // prompt (see DumpDelete), so a stray tap can never lose a capture.
                    confirmButton = {
                        TextButton(
                            text = stringResource(R.string.inspector_delete_confirm),
                            onClick = {
                                pendingDelete = null
                                val removed = performDumpDelete(
                                    target = target,
                                    deleteOne = { DumpExporter.deleteOne(context, it) },
                                    deleteAll = { DumpExporter.deleteAll(context) },
                                )
                                dumps = DumpExporter.list(context)
                                expanded = null
                                message = if (removed > 0) {
                                    context.getString(R.string.inspector_dumps_deleted, removed)
                                } else {
                                    context.getString(R.string.inspector_dumps_delete_failed)
                                }
                            },
                        )
                    },
                    dismissButton = {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { pendingDelete = null },
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
    onDelete: () -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                text = stringResource(R.string.inspector_dump_share),
                onClick = onShare,
            )
            TextButton(
                text = stringResource(R.string.inspector_dump_delete),
                onClick = onDelete,
            )
        }
    }
}

/** What a pending destructive tap targets; the confirm dialog is the only path that deletes. */
internal sealed interface DumpDelete {
    /** One listed dump, including the newest-per-surface file auto-prune exempts. */
    data class One(val dump: DumpFile) : DumpDelete

    /** Every dump on disk. */
    data object All : DumpDelete
}

/**
 * Runs a *confirmed* delete and reports how many files went. Extracted from the composable so the
 * JVM test can prove the repository call pattern: [DumpDelete.One] calls [deleteOne] exactly once
 * with that file and never [deleteAll]; [DumpDelete.All] calls [deleteAll] once and never
 * [deleteOne]. Nothing here is called until the confirm button is tapped.
 */
internal fun performDumpDelete(
    target: DumpDelete,
    deleteOne: (File) -> Boolean,
    deleteAll: () -> Int,
): Int = when (target) {
    is DumpDelete.One -> if (deleteOne(target.dump.file)) 1 else 0
    DumpDelete.All -> deleteAll()
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
