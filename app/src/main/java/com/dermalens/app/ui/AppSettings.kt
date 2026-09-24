package com.dermalens.app.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

data class AppSettings(
    val fontScale: Float = 1.0f,
    val highContrast: Boolean = false
) {
    // Text sizes that scale with fontScale
    val textSm get() = 11f * fontScale
    val textBase get() = 13f * fontScale
    val textMd get() = 14f * fontScale
    val textLg get() = 16f * fontScale
    val textXl get() = 18f * fontScale
    val textXxl get() = 20f * fontScale
    val textTitle get() = 24f * fontScale
    val textDisplay get() = 28f * fontScale

    // High contrast colors
    val textPrimary get() = if (highContrast) Color(0xFF000000) else Color(0xFF111827)
    val textSecondary get() = if (highContrast) Color(0xFF1a1a1a) else Color(0xFF6B7280)
    val background get() = Color(0xFFFFFFFF)
    val cardBg get() = if (highContrast) Color(0xFFF0F0F0) else Color(0xFFF9FAFB)
}

val LocalAppSettings = compositionLocalOf { AppSettings() }