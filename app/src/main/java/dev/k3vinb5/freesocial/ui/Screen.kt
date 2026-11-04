package dev.k3vinb5.freesocial.ui

import dev.k3vinb5.freesocial.settings.AppTarget

/** Every destination in the app. No navigation library: MainActivity switches on this. */
sealed class Screen {
    data object Hub : Screen()
    data class AppDetail(val app: AppTarget) : Screen()
    data object Budget : Screen()
    data object Schedule : Screen()
    data object Permissions : Screen()
    data object RuleInspector : Screen()
}
