package dev.k3vinb5.freesocial.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Design tokens from docs/design/DESIGN.md (dark theme). */
object FsColors {
    val Background = Color(0xFF131218)
    val Surface = Color(0xFF1E1C24)
    val Card = Color(0xFF26232E)
    val Outline = Color(0xFF453F4E)

    val TextPrimary = Color(0xFFECEAF2)
    val TextSecondary = Color(0xFFB3AEC0)
    val TextTertiary = Color(0xFF8E8899)

    val Primary = Color(0xFFFFB25C)
    val OnPrimary = Color(0xFF2B1B00)
    val PrimaryContainer = Color(0xFF3A2A14)

    val Success = Color(0xFF86D9A0)
    val Warning = Color(0xFFFFD166)
    val Danger = Color(0xFFFF7A7A)
}

private val FreeSocialColorScheme = darkColorScheme(
    primary = FsColors.Primary,
    onPrimary = FsColors.OnPrimary,
    primaryContainer = FsColors.PrimaryContainer,
    onPrimaryContainer = FsColors.Primary,
    background = FsColors.Background,
    onBackground = FsColors.TextPrimary,
    surface = FsColors.Surface,
    onSurface = FsColors.TextPrimary,
    surfaceVariant = FsColors.Card,
    onSurfaceVariant = FsColors.TextSecondary,
    surfaceContainer = FsColors.Card,
    outline = FsColors.Outline,
    outlineVariant = FsColors.Outline,
    error = FsColors.Danger,
    onError = FsColors.OnPrimary,
    tertiary = FsColors.Warning,
)

/** Roboto type scale: title 22/600 · headline 17/600 · body 15/400 · label 13/500 · caption 12/400. */
private val FreeSocialTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun FreeSocialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FreeSocialColorScheme,
        typography = FreeSocialTypography,
        content = content,
    )
}
