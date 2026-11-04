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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.settings.SettingsRepository
import dev.k3vinb5.freesocial.ui.components.HorizontalGap
import dev.k3vinb5.freesocial.ui.components.SectionCard
import dev.k3vinb5.freesocial.ui.components.Stepper
import dev.k3vinb5.freesocial.ui.components.SwitchRow
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

/** F11 — per-weekday nightly lock window. */
@Composable
fun ScheduleScreen(
    settings: SettingsRepository,
    now: ZonedDateTime,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<TimeEdit?>(null) }
    val window = settings.tonightLockWindow(now)

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
                SwitchRow(
                    title = stringResource(R.string.schedule_master_title),
                    subtitle = stringResource(R.string.schedule_master_subtitle),
                    checked = settings.scheduleEnabled,
                    onCheckedChange = settings::updateScheduleEnabled,
                )
            }

            HorizontalGap(8.dp)
            for (weekday in 1..7) {
                val day = settings.daySchedule(weekday)
                DayRow(
                    label = stringResource(WEEKDAYS[weekday - 1]),
                    bedtime = SettingsRepository.formatClock(day.bedtimeMinutes),
                    wake = SettingsRepository.formatClock(day.wakeMinutes),
                    onEditBedtime = { editing = TimeEdit(weekday, bedtime = true) },
                    onEditWake = { editing = TimeEdit(weekday, bedtime = false) },
                )
                Spacer(Modifier.height(8.dp))
            }

            HorizontalGap(4.dp)
            StepperRow(
                label = stringResource(R.string.schedule_wind_down),
                value = stringResource(R.string.schedule_minutes_value, settings.windDownMinutes),
                onDecrease = { settings.updateWindDown(settings.windDownMinutes - STEP_MINUTES) },
                onIncrease = { settings.updateWindDown(settings.windDownMinutes + STEP_MINUTES) },
                decreaseEnabled = settings.windDownMinutes > 0,
                increaseEnabled = settings.windDownMinutes < SettingsRepository.MAX_MINUTES,
            )
            Spacer(Modifier.height(8.dp))
            StepperRow(
                label = stringResource(R.string.schedule_morning_lock),
                value = stringResource(R.string.schedule_minutes_value, settings.morningLockMinutes),
                onDecrease = { settings.updateMorningLock(settings.morningLockMinutes - STEP_MINUTES) },
                onIncrease = { settings.updateMorningLock(settings.morningLockMinutes + STEP_MINUTES) },
                decreaseEnabled = settings.morningLockMinutes > 0,
                increaseEnabled = settings.morningLockMinutes < SettingsRepository.MAX_MINUTES,
            )

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
            onDismiss = { editing = null },
            onConfirm = { minutes ->
                if (current.bedtime) {
                    settings.setBedtimeMinutes(current.weekday, minutes)
                } else {
                    settings.setWakeMinutes(current.weekday, minutes)
                }
                editing = null
            },
        )
    }
}

@Composable
private fun DayRow(
    label: String,
    bedtime: String,
    wake: String,
    onEditBedtime: () -> Unit,
    onEditWake: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(FsColors.Card)
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = FsColors.TextSecondary,
            modifier = Modifier.widthIn(min = 36.dp),
        )
        Spacer(Modifier.width(8.dp))
        Spacer(Modifier.weight(1f))
        TimeChip(text = bedtime, onClick = onEditBedtime)
        Text(
            text = "→",
            style = MaterialTheme.typography.bodyMedium,
            color = FsColors.TextTertiary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        TimeChip(text = wake, onClick = onEditWake)
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean,
) {
    SectionCard {
        Row(
            modifier = Modifier.height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = FsColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Stepper(
                value = value,
                onDecrease = onDecrease,
                onIncrease = onIncrease,
                decreaseEnabled = decreaseEnabled,
                increaseEnabled = increaseEnabled,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockPickerDialog(
    title: String,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = FsColors.TextPrimary,
            )
        },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(
                text = stringResource(R.string.action_ok),
                onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) },
            )
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

private const val STEP_MINUTES = 30
