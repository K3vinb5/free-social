package dev.k3vinb5.freesocial.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.filter.DumpExporter
import dev.k3vinb5.freesocial.filter.DumpResult
import dev.k3vinb5.freesocial.settings.AppTarget
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.ChoiceChip
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.ListRow
import dev.k3vinb5.freesocial.ui.components.SectionLabel
import dev.k3vinb5.freesocial.ui.components.TopBar
import dev.k3vinb5.freesocial.ui.theme.FsColors
import kotlinx.coroutines.launch

/** One captured hierarchy in the inspector list; node counts arrive with the dump engine. */
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
 * F14 — rule inspector shell. The chip picks the app, the list shows the screens we capture for
 * it, and the dump FAB is wired up once FilterService can walk a live node tree.
 */
@Composable
fun RuleInspectorScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val apps = AppTarget.entries
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val app = apps[selected]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appLabel = stringResource(app.nameRes)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FsColors.Background),
    ) {
        TopBar(title = stringResource(R.string.inspector_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
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

            SectionLabel(stringResource(R.string.inspector_captured))
            screensFor(app).forEach { screen ->
                ListRow(
                    title = stringResource(screen.titleRes),
                    value = stringResource(R.string.inspector_nodes),
                    chevron = true,
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.weight(1f))
            Caption(stringResource(R.string.inspector_caption))
            HorizontalGap(12.dp)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                DumpButton(onClick = {
                    scope.launch {
                        when (val result = DumpExporter.dumpCurrent(context, appLabel)) {
                            is DumpResult.Success -> {
                                val where = result.file?.absolutePath ?: context.getString(R.string.inspector_dump_failed)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.inspector_dump_saved, where),
                                    Toast.LENGTH_LONG,
                                ).show()
                                val share = Intent.createChooser(
                                    DumpExporter.shareIntent(result.document, result.json),
                                    context.getString(R.string.inspector_dump_share),
                                )
                                try {
                                    context.startActivity(share)
                                } catch (_: ActivityNotFoundException) {
                                    // A headless dev image may have no share target; the file is still written.
                                }
                            }
                            is DumpResult.Unavailable -> Toast.makeText(
                                context,
                                R.string.inspector_dump_unavailable,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                })
            }
            HorizontalGap(24.dp)
        }
    }
}

/** The primary circular dump FAB. */
@Composable
private fun DumpButton(onClick: () -> Unit) {
    val description = stringResource(R.string.inspector_dump)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(FsColors.Primary)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "↓",
            fontSize = 22.sp,
            color = FsColors.OnPrimary,
        )
    }
}
