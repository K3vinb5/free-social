package dev.k3vinb5.freesocial.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.settings.AppMode
import dev.k3vinb5.freesocial.ui.theme.FsColors

private val CardShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(999.dp)
private val ChipBackground = Color(0xFF2E2B38)

// --- Top bar -----------------------------------------------------------------------------

@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    BackArrow(
                        modifier = Modifier.size(20.dp),
                        contentDescription = stringResource(R.string.nav_back),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = FsColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(FsColors.Outline),
        )
    }
}

@Composable
private fun BackArrow(modifier: Modifier = Modifier, contentDescription: String) {
    Canvas(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    ) {
        val path = Path().apply {
            moveTo(size.width * 0.65f, size.height * 0.2f)
            lineTo(size.width * 0.3f, size.height * 0.5f)
            lineTo(size.width * 0.65f, size.height * 0.8f)
        }
        drawPath(
            path = path,
            color = FsColors.TextPrimary,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Small right-pointing chevron used at the end of navigable rows. */
@Composable
fun Chevron(modifier: Modifier = Modifier, color: Color = FsColors.TextTertiary) {
    Canvas(modifier = modifier.size(16.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.35f, size.height * 0.2f)
            lineTo(size.width * 0.65f, size.height * 0.5f)
            lineTo(size.width * 0.35f, size.height * 0.8f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Small coloured status dot (permission state, step progress, list status). */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, diameter: Dp = 8.dp) {
    Box(
        modifier
            .size(diameter)
            .clip(CircleShape)
            .background(color),
    )
}

// --- Cards and rows ----------------------------------------------------------------------

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = FsColors.TextSecondary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(FsColors.Card)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun HDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FsColors.Outline.copy(alpha = 0.6f)),
    )
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(FsColors.Card)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = FsColors.TextPrimary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = FsColors.TextTertiary,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = FsColors.TextSecondary,
                textAlign = TextAlign.End,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(10.dp))
            Chevron()
        }
    }
}

/**
 * Row with a coloured status dot, a title and a coloured status line — permissions (F12) and
 * the wizard's service steps (F3/F4). [actionLabel] is the trailing text affordance.
 */
@Composable
fun StatusListRow(
    title: String,
    status: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(FsColors.Card)
            .then(if (onAction != null) Modifier.clickable(role = Role.Button, onClick = onAction) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(statusColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = FsColors.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )
        }
        if (actionLabel != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = FsColors.Primary,
            )
        }
    }
}

// --- Chips, pills, switches --------------------------------------------------------------
/** State chip: Filter = success "Filtering", Block = danger "Blocked", Allow = tertiary "Allowed". */
@Composable
fun StateChip(mode: AppMode, modifier: Modifier = Modifier) {
    val color: Color
    val label: String
    when (mode) {
        AppMode.FILTER -> {
            color = FsColors.Success
            label = stringResource(R.string.chip_filtering)
        }
        AppMode.BLOCK -> {
            color = FsColors.Danger
            label = stringResource(R.string.chip_blocked)
        }
        AppMode.ALLOW -> {
            color = FsColors.Warning
            label = stringResource(R.string.chip_allowed)
        }
    }
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(ChipBackground)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = FsColors.TextPrimary,
        )
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = FsColors.TextPrimary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = FsColors.TextTertiary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = FsColors.Primary,
                checkedBorderColor = FsColors.Primary,
                uncheckedThumbColor = FsColors.TextTertiary,
                uncheckedTrackColor = FsColors.Surface,
                uncheckedBorderColor = FsColors.Outline,
            ),
        )
    }
}

/** Selectable pill chip (inspector app filter, F14). Selected chips get the primary container. */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(if (selected) FsColors.PrimaryContainer else FsColors.Card)
            .clickable(role = Role.Tab, onClick = onClick)
            .height(36.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            StatusDot(FsColors.Primary, diameter = 6.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) FsColors.Primary else FsColors.TextSecondary,
        )
    }
}

/** Numbered step badge for the restricted-settings card (F2). */
@Composable
fun StepBadge(number: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(FsColors.PrimaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = FsColors.Primary,
        )
    }
}

/** Tinted informational card: coloured dot plus one or two lines of copy (F12 banner). */
@Composable
fun InfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = FsColors.Warning,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(color.copy(alpha = 0.12f))
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(color, modifier = Modifier.padding(top = 5.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = FsColors.TextPrimary,
        )
    }
}

// --- Stepper, segmented control, buttons -------------------------------------------------

@Composable
fun Stepper(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    decreaseEnabled: Boolean = true,
    increaseEnabled: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepperButton(
            label = "−",
            description = stringResource(R.string.stepper_decrease),
            enabled = decreaseEnabled,
            onClick = onDecrease,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = FsColors.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        StepperButton(
            label = "+",
            description = stringResource(R.string.stepper_increase),
            enabled = increaseEnabled,
            onClick = onIncrease,
        )
    }
}

@Composable
private fun StepperButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(ChipBackground)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) FsColors.Primary else FsColors.TextTertiary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun SegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(FsColors.Surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(PillShape)
                    .background(if (selected) FsColors.Primary else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) FsColors.OnPrimary else FsColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ButtonSurface(
        text = text,
        background = FsColors.Primary,
        contentColor = FsColors.OnPrimary,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ButtonSurface(
        text = text,
        background = FsColors.Card,
        contentColor = FsColors.TextPrimary,
        onClick = onClick,
        modifier = modifier,
    )
}

/** Tinted primary-container button: secondary action that still reads as warm (F4). */
@Composable
fun PrimaryContainerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ButtonSurface(
        text = text,
        background = FsColors.PrimaryContainer,
        contentColor = FsColors.Primary,
        onClick = onClick,
        modifier = modifier,
    )
}

/** Compact pill action, e.g. "Allow" inside a permission card (F5). */
@Composable
fun SmallTintedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(40.dp)
            .widthIn(min = 88.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(FsColors.PrimaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = FsColors.Primary,
        )
    }
}

@Composable
fun TextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = FsColors.TextSecondary,
        )
    }
}

@Composable
private fun ButtonSurface(
    text: String,
    background: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = contentColor,
        )
    }
}

/** Small tappable pill showing a clock value (schedule day rows). */
@Composable
fun TimeChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .widthIn(min = 88.dp)
            .height(32.dp)
            .clip(PillShape)
            .background(ChipBackground)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = FsColors.TextSecondary,
        )
    }
}

@Composable
fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = FsColors.TextTertiary,
        textAlign = align,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun HorizontalGap(height: Dp) {
    Spacer(Modifier.height(height))
}
