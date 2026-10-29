package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Compact type scale so the app does not feel oversized on a phone screen.
private fun style(size: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size + 6).sp,
    letterSpacing = spacing.sp
)

val Typography = Typography(
    headlineLarge = style(24, FontWeight.Bold),
    titleLarge = style(18),
    titleMedium = style(14, FontWeight.Medium),
    bodyLarge = style(14),
    bodyMedium = style(13),
    bodySmall = style(11),
    labelLarge = style(13, FontWeight.Medium),
    labelMedium = style(11, FontWeight.Medium),
    labelSmall = style(10, FontWeight.Medium)
)
