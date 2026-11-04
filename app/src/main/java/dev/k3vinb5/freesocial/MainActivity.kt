package dev.k3vinb5.freesocial

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.k3vinb5.freesocial.settings.SettingsRepository
import dev.k3vinb5.freesocial.ui.Screen
import dev.k3vinb5.freesocial.ui.screens.AppDetailScreen
import dev.k3vinb5.freesocial.ui.screens.BudgetScreen
import dev.k3vinb5.freesocial.ui.screens.PermissionsScreen
import dev.k3vinb5.freesocial.ui.screens.RuleInspectorScreen
import dev.k3vinb5.freesocial.ui.screens.ScheduleScreen
import dev.k3vinb5.freesocial.ui.screens.SettingsHubScreen
import dev.k3vinb5.freesocial.ui.theme.FreeSocialTheme
import dev.k3vinb5.freesocial.ui.theme.FsColors
import java.time.ZonedDateTime
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeSocialTheme {
                val settings = remember { SettingsRepository.from(applicationContext) }
                FreeSocialApp(settings = settings)
            }
        }
    }
}

/** Hand-rolled navigation: one [Screen] at a time, back always returns to the hub. */
@Composable
fun FreeSocialApp(
    settings: SettingsRepository,
    modifier: Modifier = Modifier,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Hub) }
    var now by remember { mutableStateOf(ZonedDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = ZonedDateTime.now()
        }
    }

    BackHandler(enabled = screen != Screen.Hub) {
        screen = Screen.Hub
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = FsColors.Background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FsColors.Background)
                .padding(innerPadding),
        ) {
            val toHub: () -> Unit = { screen = Screen.Hub }
            when (val current = screen) {
                Screen.Hub -> SettingsHubScreen(
                    settings = settings,
                    now = now,
                    onOpenApp = { app -> screen = Screen.AppDetail(app) },
                    onOpenBudget = { screen = Screen.Budget },
                    onOpenSchedule = { screen = Screen.Schedule },
                    onOpenPermissions = { screen = Screen.Permissions },
                    onOpenRuleInspector = { screen = Screen.RuleInspector },
                )
                is Screen.AppDetail -> AppDetailScreen(
                    app = current.app,
                    settings = settings,
                    onBack = toHub,
                )
                Screen.Budget -> BudgetScreen(settings = settings, now = now, onBack = toHub)
                Screen.Schedule -> ScheduleScreen(settings = settings, now = now, onBack = toHub)
                Screen.Permissions -> PermissionsScreen(onBack = toHub)
                Screen.RuleInspector -> RuleInspectorScreen(onBack = toHub)
            }
        }
    }
}
