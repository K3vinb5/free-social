package dev.k3vinb5.freesocial.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.settings.AppMode
import dev.k3vinb5.freesocial.settings.AppTarget
import dev.k3vinb5.freesocial.settings.SettingsRepository
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.HDivider
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.SectionCard
import dev.k3vinb5.freesocial.ui.components.SectionLabel
import dev.k3vinb5.freesocial.ui.components.SegmentedControl
import dev.k3vinb5.freesocial.ui.components.SwitchRow
import dev.k3vinb5.freesocial.ui.components.TopBar
import dev.k3vinb5.freesocial.ui.theme.FsColors

/** F7–F9 — one app's mode and surface toggles. */
@Composable
fun AppDetailScreen(
    app: AppTarget,
    settings: SettingsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            Caption(stringResource(R.string.rules_verified_for, app.rulesVersion))
            Spacer(Modifier.height(24.dp))
        }
    }
}
