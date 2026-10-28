package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColorScheme = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = Color(0xFF0D1B2E),
    primaryContainer = ElectricBlue,          // selected tool / selected smooth level
    onPrimaryContainer = Color(0xFF1B1B1B),
    secondary = ElectricBlueLight,
    onSecondary = Color(0xFF0D1B2E),
    secondaryContainer = NavySurfaceVariant,
    onSecondaryContainer = NavyTextPrimary,
    background = NavyBackground,
    onBackground = NavyTextPrimary,
    surface = NavySurface,
    onSurface = NavyTextPrimary,
    surfaceVariant = NavyCard,
    onSurfaceVariant = NavyTextSecondary,
    outline = NavyBorder,
    error = ErrorRed,
    onError = Color(0xFF1B1B1B)
)

// The app is always dark, like the reference app (the transparent-checkerboard look).
@Composable
fun CutoutProTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography,
        content = content
    )
}
