package com.max.skipflow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

object SkipFlowColors {
    val ObsidianBlack = Color(0xFF05070A)
    val DarkNavyCard = Color(0xFF0D131F)
    val ElevatedNavy = Color(0xFF131C2E)
    val NavyBorder = Color(0xFF1C2942)
    val ElectricBlue = Color(0xFF0088FF)
    val ElectricCyan = Color(0xFF00E5FF)
    val HealthyGreen = Color(0xFF00E676)
    val AlertRed = Color(0xFFFF4D6A)
    val WarningAmber = Color(0xFFFFB300)
    val TextPrimary = Color(0xFFF4F7FC)
    val TextSecondary = Color(0xFF8E9BB5)
}

private val SkipFlowDarkColorScheme = darkColorScheme(
    primary = SkipFlowColors.ElectricBlue,
    onPrimary = Color.White,
    secondary = SkipFlowColors.ElectricCyan,
    onSecondary = SkipFlowColors.ObsidianBlack,
    background = SkipFlowColors.ObsidianBlack,
    onBackground = SkipFlowColors.TextPrimary,
    surface = SkipFlowColors.DarkNavyCard,
    onSurface = SkipFlowColors.TextPrimary,
    surfaceVariant = SkipFlowColors.ElevatedNavy,
    onSurfaceVariant = SkipFlowColors.TextSecondary,
    outline = SkipFlowColors.NavyBorder,
    error = SkipFlowColors.AlertRed,
    onError = Color.White
)

private val SkipFlowTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = SkipFlowColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = SkipFlowColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = SkipFlowColors.TextPrimary
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        color = SkipFlowColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        color = SkipFlowColors.TextSecondary
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = SkipFlowColors.TextSecondary
    )
)

@Composable
fun SkipFlowMaxTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = SkipFlowDarkColorScheme,
            typography = SkipFlowTypography,
            content = content
        )
    }
}

fun formatSavedTimeArabic(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "0 ثانية"
    if (totalSeconds < 60L) return "$totalSeconds ثانية"
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L && minutes > 0L -> "$hours س و $minutes د"
        hours > 0L -> "$hours ساعة"
        seconds > 0L -> "$minutes د و $seconds ث"
        else -> "$minutes دقيقة"
    }
}
