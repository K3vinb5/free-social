package dev.k3vinb5.freesocial.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.filter.DumpArm
import dev.k3vinb5.freesocial.filter.DumpExporter
import dev.k3vinb5.freesocial.filter.DumpFile
import dev.k3vinb5.freesocial.settings.AppTarget
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.ChoiceChip
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.InfoBanner
import dev.k3vinb5.freesocial.ui.components.ListRow
import dev.k3vinb5.freesocial.ui.components.SectionLabel
import dev.k3vinb5.freesocial.ui.components.TopBar
import dev.k3vinb5.freesocial.ui.theme.FsColors
import kotlinx.coroutines.delay

private const val TAG = "RuleInspector"

/** One expected hierarchy in the inspector list; the target app fills it in. */
private data class CapturedScreen(val titleRes: Int)

/** The screens we expect to dump per app (SPEC §2.5 / DESIGN.md F14). */
private fun screensFor(app: AppTarget): List<CapturedScreen> = when (app) {
    AppTarget.INSTAGRAM -> listOf(
        CapturedScreen(R.string.inspector_screen_ig_feed),
        CapturedScreen(R.string.inspector_screen_ig_reels),
    )
    AppTarget.YOUTUBE -> listOf(
        CapturedScreen(R.string.inspector_screen_yt_shorts),
    )
    AppTarget.TIKTOK -> listOf(
        CapturedScreen(R.string.inspector_screen_tt_playback),
    )
}

/**
 * F14 — rule inspector.
 *
 * Capturing another app's tree is a two-step handshake (see [DumpArm]): arm here, switch to the
 * target app, and [dev.k3vinb5.freesocial.filter.FilterService] writes the next window it sees.
 * The list shows the dumps already on disk; tapping one opens the share sheet.
 */
@Composable
fun RuleInspectorScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val apps = AppTarget.entries
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val app = apps[selected]
    val context = LocalContext.current

    var dumps by remember { mutableStateOf(DumpExporter.list(context)) }
    var armedScreen by remember { mutableStateOf(DumpArm.armedScreen()) }

    fun arm(label: String) {
        DumpArm.arm(label)
        armedScreen = label
    }

    // Coming back from the target app: pick up the dump the service wrote (or the dead arm).
    LifecycleResumeEffect(Unit) {
        dumps = DumpExporter.list(context)
        armedScreen = DumpArm.armedScreen()
        onPauseOrDispose { }
    }

    // While armed, poll the flag: it auto-clears on capture, and expires after 60 s.
    LaunchedEffect(armedScreen) {
        if (armedScreen == null) return@LaunchedEffect
        while (true) {
            delay(1_000)
            if (DumpArm.armedScreen() == null) {
                Log.i(TAG, "dump arm expired or was consumed")
                armedScreen = null
                dumps = DumpExporter.list(context)
                break
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

            val armed = armedScreen
            if (armed != null) {
                HorizontalGap(12.dp)
                InfoBanner(
                    text = stringResource(R.string.inspector_armed_status, armed),
                    color = FsColors.Primary,
                )
            }

            SectionLabel(stringResource(R.string.inspector_captured))
            screensFor(app).forEach { screen ->
                val label = stringResource(screen.titleRes)
                ListRow(
                    title = label,
                    value = stringResource(R.string.inspector_dump),
                    chevron = true,
                    onClick = { arm(label) },
                )
                Spacer(Modifier.height(8.dp))
            }

            SectionLabel(stringResource(R.string.inspector_dumps))
            if (dumps.isEmpty()) {
                Caption(stringResource(R.string.inspector_no_dumps))
            } else {
                dumps.forEach { dump ->
                    ListRow(
                        title = dump.document.appPackage,
                        subtitle = stringResource(
                            R.string.inspector_dump_subtitle,
                            dump.file.name,
                            dump.document.capturedAt,
                        ),
                        value = stringResource(R.string.inspector_nodes_count, dump.nodeCount),
                        chevron = true,
                        onClick = { shareDump(context, dump) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            HorizontalGap(16.dp)
            Caption(stringResource(R.string.inspector_caption))
            HorizontalGap(12.dp)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                val armLabel = stringResource(screensFor(app).first().titleRes)
                DumpButton(armed = armed != null, onClick = { arm(armLabel) })
            }
            HorizontalGap(24.dp)
        }
    }
}

private fun shareDump(context: Context, dump: DumpFile) {
    val text = try {
        dump.file.readText()
    } catch (t: Throwable) {
        Log.w(TAG, "cannot read dump ${dump.file.name}", t)
        return
    }
    val chooser = Intent.createChooser(
        DumpExporter.shareIntent(dump.document, text),
        context.getString(R.string.inspector_dump_share),
    )
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // A headless dev image may have no share target; the file is still written.
    }
}

/** The primary circular dump FAB: arms one capture, then turns green while armed. */
@Composable
private fun DumpButton(armed: Boolean, onClick: () -> Unit) {
    val description = stringResource(if (armed) R.string.inspector_dump_armed else R.string.inspector_dump)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (armed) FsColors.Success else FsColors.Primary)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (armed) "…" else "↓",
            fontSize = 22.sp,
            color = FsColors.OnPrimary,
        )
    }
}
