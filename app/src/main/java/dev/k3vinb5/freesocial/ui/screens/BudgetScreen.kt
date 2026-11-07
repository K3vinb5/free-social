package dev.k3vinb5.freesocial.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.settings.SettingsRepository
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.SectionCard
import dev.k3vinb5.freesocial.ui.components.StatusPill
import dev.k3vinb5.freesocial.ui.components.Stepper
import dev.k3vinb5.freesocial.ui.components.TopBar
import dev.k3vinb5.freesocial.ui.theme.FsColors
import java.time.ZonedDateTime

/** F10 — combined Reels & Shorts budget. */
@Composable
fun BudgetScreen(
    settings: SettingsRepository,
    now: ZonedDateTime,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val limit = settings.budgetLimitMinutes
    val remaining = settings.remainingMinutesCeil(now)
    // Same truth as the hub pill: filtering is only on when the budget is spent or the window locks.
    val status = hubStatus(
        masterEnabled = settings.filteringEnabled,
        serviceEnabled = settings.permissionStatus.filterService,
        locked = settings.isLocked(now),
        usedSeconds = settings.usedSecondsToday(now),
        limitMinutes = limit,
    )
    val statusText = if (status == HubStatus.PAUSED_BUDGET) {
        pluralStringResource(R.plurals.budget_status_paused_budget, remaining, remaining)
    } else {
        hubPillText(status, remaining)
    }
    val statusColor = when (status) {
        HubStatus.FILTERING -> FsColors.Success
        HubStatus.PAUSED_BUDGET, HubStatus.PAUSED_SERVICE -> FsColors.Warning
        HubStatus.OFF -> FsColors.TextTertiary
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FsColors.Background),
    ) {
        TopBar(title = stringResource(R.string.budget_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalGap(32.dp)
            BudgetRing(
                remainingMinutes = remaining,
                limitMinutes = limit,
            )

            HorizontalGap(16.dp)
            StatusPill(text = statusText, color = statusColor)

            HorizontalGap(24.dp)
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.budget_daily_allowance),
                        style = MaterialTheme.typography.bodyLarge,
                        color = FsColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Stepper(
                        value = stringResource(R.string.budget_minutes_value, limit),
                        onDecrease = { settings.updateBudgetLimit(limit - STEP_MINUTES) },
                        onIncrease = { settings.updateBudgetLimit(limit + STEP_MINUTES) },
                        decreaseEnabled = limit > SettingsRepository.MIN_BUDGET,
                        increaseEnabled = limit < SettingsRepository.MAX_BUDGET,
                    )
                }
            }

            HorizontalGap(16.dp)
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.budget_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FsColors.TextSecondary,
                )
            }

            Spacer(Modifier.weight(1f))
            Caption(
                text = stringResource(R.string.budget_caption),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BudgetRing(remainingMinutes: Int, limitMinutes: Int) {
    val fraction = if (limitMinutes <= 0) 0f else (remainingMinutes.toFloat() / limitMinutes).coerceIn(0f, 1f)
    Box(
        modifier = Modifier.size(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = FsColors.Outline,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            if (fraction > 0f) {
                drawArc(
                    color = FsColors.Primary,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = remainingMinutes.toString(),
                color = FsColors.Primary,
                fontSize = 44.sp,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.budget_min_left_today),
                style = MaterialTheme.typography.bodyLarge,
                color = FsColors.TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.budget_of_daily, limitMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = FsColors.TextTertiary,
            )
        }
    }
}

private const val STEP_MINUTES = 5
