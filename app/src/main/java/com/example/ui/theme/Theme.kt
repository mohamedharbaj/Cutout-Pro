package com.example.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

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
    outline = ElectricBlue,
    outlineVariant = NavyBorder,
    error = ErrorRed,
    onError = Color(0xFF1B1B1B)
)

// The app is always dark, like the reference app (the transparent-checkerboard look).
@Composable
fun CutoutProTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    // Keep the UI compact: ignore a very large system font size.
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = density.fontScale.coerceAtMost(1f))
    ) {
        MaterialTheme(
            colorScheme = AppColorScheme,
            typography = Typography
        ) {
            // Surface provides the default text/icon colour. Without it every Text() that has no
            // explicit colour is drawn BLACK on the dark background (invisible labels/titles).
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content
            )
        }
    }
}
