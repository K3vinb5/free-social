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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.core.DaySchedule
import dev.k3vinb5.freesocial.settings.SettingsRepository
import dev.k3vinb5.freesocial.ui.components.Caption
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.SectionCard
import dev.k3vinb5.freesocial.ui.components.TextButton
import dev.k3vinb5.freesocial.ui.components.TimeChip
import dev.k3vinb5.freesocial.ui.components.TopBar
import dev.k3vinb5.freesocial.ui.theme.FsColors
import java.time.ZonedDateTime

private val WEEKDAYS = listOf(
    R.string.day_mon,
    R.string.day_tue,
    R.string.day_wed,
    R.string.day_thu,
    R.string.day_fri,
    R.string.day_sat,
    R.string.day_sun,
)

private data class TimeEdit(val weekday: Int, val bedtime: Boolean)

/**
 * F11 — per-weekday sleep window. Mandatory: no master switch, and every day is bedtime -> wake
 * exactly, never shorter than six hours (validated live in the picker and again in the repository).
 */
@Composable
fun ScheduleScreen(
    settings: SettingsRepository,
    now: ZonedDateTime,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<TimeEdit?>(null) }
    val window = settings.tonightLockWindow(now)
    val minimumMinutes = DaySchedule.MIN_WINDOW_MINUTES

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FsColors.Background),
    ) {
        TopBar(title = stringResource(R.string.schedule_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            HorizontalGap(16.dp)
            SectionCard {
                Text(
                    text = stringResource(R.string.schedule_always_on),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FsColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.schedule_minimum_explainer, durationLabel(minimumMinutes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = FsColors.TextTertiary,
                )
            }

            HorizontalGap(8.dp)
            for (weekday in 1..7) {
                val day = settings.daySchedule(weekday)
                DayRow(
                    label = stringResource(WEEKDAYS[weekday - 1]),
                    bedtime = SettingsRepository.formatClock(day.bedtimeMinutes),
                    wake = SettingsRepository.formatClock(day.wakeMinutes),
                    duration = durationLabel(day.windowMinutes),
                    onEditBedtime = { editing = TimeEdit(weekday, bedtime = true) },
                    onEditWake = { editing = TimeEdit(weekday, bedtime = false) },
                )
                Spacer(Modifier.height(8.dp))
            }

            HorizontalGap(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(FsColors.PrimaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        R.string.schedule_preview,
                        SettingsRepository.formatClock(window.startMinutes),
                        SettingsRepository.formatClock(window.endMinutes),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = FsColors.Primary,
                )
            }
            HorizontalGap(16.dp)
            Caption(stringResource(R.string.schedule_caption, durationLabel(minimumMinutes)))
            Spacer(Modifier.height(24.dp))
        }
    }

    val current = editing
    if (current != null) {
        val day = settings.daySchedule(current.weekday)
        ClockPickerDialog(
            title = stringResource(
                if (current.bedtime) R.string.time_bedtime else R.string.time_wake,
            ),
            initialMinutes = if (current.bedtime) day.bedtimeMinutes else day.wakeMinutes,
            bedtimeMinutes = day.bedtimeMinutes,
            wakeMinutes = day.wakeMinutes,
            editingBedtime = current.bedtime,
            onDismiss = { editing = null },
            onConfirm = { minutes ->
                val saved = if (current.bedtime) {
                    settings.setBedtimeMinutes(current.weekday, minutes)
                } else {
                    settings.setWakeMinutes(current.weekday, minutes)
                }
                if (saved) editing = null
            },
        )
    }
}

@Composable
private fun DayRow(
    label: String,
    bedtime: String,
    wake: String,
    duration: String,
    onEditBedtime: () -> Unit,
    onEditWake: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(FsColors.Card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = FsColors.TextSecondary,
                modifier = Modifier.widthIn(min = 36.dp),
            )
            Spacer(Modifier.weight(1f))
            TimeChip(text = bedtime, onClick = onEditBedtime)
            Text(
                text = stringResource(R.string.time_range_separator),
                style = MaterialTheme.typography.bodyMedium,
                color = FsColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            TimeChip(text = wake, onClick = onEditWake)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = duration,
            style = MaterialTheme.typography.bodySmall,
            color = FsColors.TextTertiary,
        )
    }
}

/** "8h 30m" from a minute count; used for the live readout and the six-hour floor. */
@Composable
private fun durationLabel(minutes: Int): String =
    stringResource(R.string.schedule_duration, minutes / 60, minutes % 60)

/**
 * One clock picker with a live window readout. The OK action only exists while the candidate
 * window (this pick combined with the other end) clears the six-hour floor; otherwise the dialog
 * shows the same readout plus an inline explanation of why it cannot be saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockPickerDialog(
    title: String,
    initialMinutes: Int,
    bedtimeMinutes: Int,
    wakeMinutes: Int,
    editingBedtime: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        // The device's own 12/24-hour preference, not a hardcoded 24-hour clock.
        is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
    )
    val picked = pickerState.hour * 60 + pickerState.minute
    val candidateBedtime = if (editingBedtime) picked else bedtimeMinutes
    val candidateWake = if (editingBedtime) wakeMinutes else picked
    val candidateMinutes = SettingsRepository.windowMinutesOf(candidateBedtime, candidateWake)
    val valid = candidateMinutes >= DaySchedule.MIN_WINDOW_MINUTES

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = FsColors.TextPrimary,
            )
        },
        text = {
            Column {
                TimePicker(state = pickerState)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.schedule_duration_readout,
                        durationLabel(candidateMinutes),
                        durationLabel(DaySchedule.MIN_WINDOW_MINUTES),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) FsColors.TextTertiary else FsColors.Warning,
                )
                if (!valid) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.schedule_below_minimum,
                            durationLabel(DaySchedule.MIN_WINDOW_MINUTES),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = FsColors.Warning,
                    )
                }
            }
        },
        confirmButton = {
            if (valid) {
                TextButton(
                    text = stringResource(R.string.action_ok),
                    onClick = { onConfirm(picked) },
                )
            } else {
                Text(
                    text = stringResource(R.string.action_ok),
                    style = MaterialTheme.typography.labelLarge,
                    color = FsColors.TextTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        },
        dismissButton = {
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
        containerColor = FsColors.Surface,
    )
}
