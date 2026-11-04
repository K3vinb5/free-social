package dev.k3vinb5.freesocial.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.SectionCard
import dev.k3vinb5.freesocial.ui.components.TopBar
import dev.k3vinb5.freesocial.ui.theme.FsColors

/** F12 placeholder — live permission state arrives in a later wave. */
@Composable
fun PermissionsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    StubScreen(
        title = stringResource(R.string.permissions_title),
        caption = stringResource(R.string.permissions_caption),
        onBack = onBack,
        modifier = modifier,
    )
}

/** F14 placeholder — the inspector itself arrives in a later wave. */
@Composable
fun RuleInspectorScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    StubScreen(
        title = stringResource(R.string.inspector_title),
        caption = stringResource(R.string.inspector_caption),
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun StubScreen(
    title: String,
    caption: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FsColors.Background),
    ) {
        TopBar(title = title, onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            HorizontalGap(16.dp)
            SectionCard {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = FsColors.TextPrimary,
                )
            }
            Spacer(Modifier.height(16.dp))
            Caption(caption)
        }
    }
}
