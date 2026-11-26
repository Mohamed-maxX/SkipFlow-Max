package com.max.skipflow.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.max.skipflow.BuildConfig
import com.max.skipflow.engine.ServiceNotificationManager
import com.max.skipflow.ui.MainUiState
import com.max.skipflow.ui.theme.SkipFlowColors
import com.max.skipflow.ui.theme.formatSavedTimeArabic

@Composable
fun HomeScreen(
    uiState: MainUiState,
    onToggleEngine: () -> Unit,
    onStartSnooze: () -> Unit,
    onCancelSnooze: () -> Unit,
    onFixAccessibility: () -> Unit,
    onFixBatteryOptimization: () -> Unit,
    onNavigateToApps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEngineFullyActive = uiState.settings.engineEnabled &&
        uiState.snoozeRemainingMs <= 0L &&
        uiState.healthStatus.isAccessibilityHealthy &&
        !(uiState.settings.pauseDuringCalls && uiState.isCallInProgress)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SkipFlowColors.ObsidianBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HomeHeader(uiState = uiState, isEngineFullyActive = isEngineFullyActive)

        HeroPowerControl(
            engineEnabled = uiState.settings.engineEnabled,
            snoozeRemainingMs = uiState.snoozeRemainingMs,
            isAccessibilityHealthy = uiState.healthStatus.isAccessibilityHealthy,
            isCallPaused = uiState.settings.pauseDuringCalls && uiState.isCallInProgress,
            onToggleEngine = onToggleEngine,
            onStartSnooze = onStartSnooze,
            onCancelSnooze = onCancelSnooze
        )

        LiveStatsRow(
            skippedToday = uiState.stats.skippedToday,
            savedSecondsToday = uiState.stats.savedSecondsToday
        )

        OneTapHealthCheckCard(
            accessibilityHealthy = uiState.healthStatus.isAccessibilityHealthy,
            batteryHealthy = uiState.healthStatus.batteryOptimizationIgnored,
            onFixAccessibility = onFixAccessibility,
            onFixBatteryOptimization = onFixBatteryOptimization
        )

        ContextAwareAppsCard(
            enabledAppsCount = uiState.enabledAppsCount,
            disabledAppsCount = uiState.disabledPackages.size,
            onNavigateToApps = onNavigateToApps
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun HomeHeader(
    uiState: MainUiState,
    isEngineFullyActive: Boolean
) {
    val (statusLabel, statusColor) = when {
        !uiState.healthStatus.isAccessibilityHealthy ->
            "تتطلب تفعيل الخدمة" to SkipFlowColors.AlertRed
        !uiState.settings.engineEnabled ->
            "متوقف يدوياً" to SkipFlowColors.TextSecondary
        uiState.settings.pauseDuringCalls && uiState.isCallInProgress ->
            "متوقف مؤقتاً (مكالمة)" to SkipFlowColors.WarningAmber
        isEngineFullyActive ->
            "نشط في الخلفية" to SkipFlowColors.HealthyGreen
        else ->
            "جاهز" to SkipFlowColors.ElectricCyan
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "SkipFlow Max",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SkipFlowColors.TextPrimary
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SkipFlowColors.ElectricBlue.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, SkipFlowColors.ElectricBlue.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = SkipFlowColors.ElectricCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = "محرك التخطي الذكي للإعلانات",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Surface(
            shape = RoundedCornerShape(50),
            color = statusColor.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = statusLabel,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun HeroPowerControl(
    engineEnabled: Boolean,
    snoozeRemainingMs: Long,
    isAccessibilityHealthy: Boolean,
    isCallPaused: Boolean,
    onToggleEngine: () -> Unit,
    onStartSnooze: () -> Unit,
    onCancelSnooze: () -> Unit
) {
    val isSnoozed = snoozeRemainingMs > 0L
    val activeVisual = engineEnabled && !isSnoozed && isAccessibilityHealthy && !isCallPaused
    val infiniteTransition = rememberInfiniteTransition(label = "power_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (activeVisual) 1.07f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val ringColor by animateColorAsState(
        targetValue = when {
            isSnoozed -> SkipFlowColors.WarningAmber
            activeVisual -> SkipFlowColors.ElectricCyan
            engineEnabled && !isAccessibilityHealthy -> SkipFlowColors.AlertRed
            engineEnabled && isCallPaused -> SkipFlowColors.WarningAmber
            else -> SkipFlowColors.NavyBorder
        },
        label = "ring_color"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SkipFlowColors.DarkNavyCard),
        border = BorderStroke(1.dp, SkipFlowColors.NavyBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(164.dp)
            ) {
                if (activeVisual) {
                    Box(
                        modifier = Modifier
                            .size(158.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        SkipFlowColors.ElectricBlue.copy(alpha = 0.34f),
                                        SkipFlowColors.ElectricCyan.copy(alpha = 0.08f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(134.dp)
                        .clip(CircleShape)
                        .background(
                            if (engineEnabled) {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        SkipFlowColors.ElectricBlue.copy(alpha = 0.28f),
                                        SkipFlowColors.DarkNavyCard
                                    )
                                )
                            } else {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        SkipFlowColors.ElevatedNavy,
                                        SkipFlowColors.DarkNavyCard
                                    )
                                )
                            }
                        )
                        .border(2.5.dp, ringColor, CircleShape)
                        .clickable { onToggleEngine() }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PowerSettingsNew,
                            contentDescription = "تشغيل أو إيقاف المحرك",
                            tint = when {
                                isSnoozed -> SkipFlowColors.WarningAmber
                                engineEnabled -> SkipFlowColors.ElectricCyan
                                else -> SkipFlowColors.TextSecondary
                            },
                            modifier = Modifier.size(46.dp)
                        )
                        Text(
                            text = when {
                                isSnoozed -> "غفوة مؤقتة"
                                engineEnabled -> "مُفعّل"
                                else -> "متوقف"
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (engineEnabled) SkipFlowColors.TextPrimary else SkipFlowColors.TextSecondary
                        )
                    }
                }
            }

            Text(
                text = when {
                    isSnoozed -> "إيقاف مؤقت: سيعود المحرك تلقائياً خلال ${ServiceNotificationManager.formatCountdown(snoozeRemainingMs)}"
                    !engineEnabled -> "المحرك متوقف حالياً — اضغط للتشغيل"
                    !isAccessibilityHealthy -> "المحرك جاهز — يرجى تفعيل خدمة إمكانية الوصول أدناه"
                    isCallPaused -> "تم الإيقاف مؤقتاً لوجود مكالمة جارية"
                    else -> "تخطي الإعلانات تلقائياً مفعّل بنجاح"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    isSnoozed -> SkipFlowColors.WarningAmber
                    activeVisual -> SkipFlowColors.ElectricCyan
                    else -> SkipFlowColors.TextSecondary
                }
            )

            if (isSnoozed) {
                Button(
                    onClick = onCancelSnooze,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SkipFlowColors.ElectricBlue,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "استئناف الآن (${ServiceNotificationManager.formatCountdown(snoozeRemainingMs)})",
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (engineEnabled) {
                OutlinedButton(
                    onClick = onStartSnooze,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SkipFlowColors.WarningAmber.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SkipFlowColors.WarningAmber
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Snooze,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "إيقاف مؤقت 10 دقائق",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveStatsRow(
    skippedToday: Int,
    savedSecondsToday: Long
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatMetricCard(
            title = "إعلانات متخطاة اليوم",
            value = skippedToday.toString(),
            icon = Icons.Rounded.Bolt,
            accentColor = SkipFlowColors.ElectricCyan,
            modifier = Modifier.weight(1f)
        )
        StatMetricCard(
            title = "الوقت الموفّر",
            value = formatSavedTimeArabic(savedSecondsToday),
            icon = Icons.Rounded.Timer,
            accentColor = SkipFlowColors.ElectricBlue,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatMetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SkipFlowColors.DarkNavyCard),
        border = BorderStroke(1.dp, SkipFlowColors.NavyBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.16f))
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = SkipFlowColors.TextSecondary
                )
            }
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = SkipFlowColors.TextPrimary
            )
        }
    }
}

@Composable
private fun OneTapHealthCheckCard(
    accessibilityHealthy: Boolean,
    batteryHealthy: Boolean,
    onFixAccessibility: () -> Unit,
    onFixBatteryOptimization: () -> Unit
) {
    val allHealthy = accessibilityHealthy && batteryHealthy
    val overallColor = if (allHealthy) SkipFlowColors.HealthyGreen else SkipFlowColors.AlertRed

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SkipFlowColors.DarkNavyCard),
        border = BorderStroke(
            width = 1.dp,
            color = if (allHealthy) SkipFlowColors.NavyBorder else SkipFlowColors.AlertRed.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = SkipFlowColors.ElectricCyan
                    )
                    Text(
                        text = "التشخيص الذاتي السريع",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = overallColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (allHealthy) "سليم 100%" else "يتطلب إصلاحاً",
                        color = overallColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            HorizontalDivider(color = SkipFlowColors.NavyBorder)

            HealthCheckRow(
                icon = Icons.Rounded.AccessibilityNew,
                title = "خدمة إمكانية الوصول",
                subtitle = if (accessibilityHealthy) {
                    "مفعّلة وتراقب التطبيقات المحددة فقط"
                } else {
                    "معطّلة — مطلوبة لتخطي الإعلانات تلقائياً"
                },
                isHealthy = accessibilityHealthy,
                fixButtonText = "تفعيل الآن",
                onFixClick = onFixAccessibility
            )

            HorizontalDivider(color = SkipFlowColors.NavyBorder.copy(alpha = 0.6f))

            HealthCheckRow(
                icon = Icons.Rounded.BatterySaver,
                title = "استثناء تحسين البطارية",
                subtitle = if (batteryHealthy) {
                    "التطبيق مستثنى ويعمل باستقرار في الخلفية"
                } else {
                    "مقيّد — قد يغلق النظام الخدمة في الخلفية"
                },
                isHealthy = batteryHealthy,
                fixButtonText = "إصلاح الآن",
                onFixClick = onFixBatteryOptimization
            )
        }
    }
}

@Composable
private fun HealthCheckRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isHealthy: Boolean,
    fixButtonText: String,
    onFixClick: () -> Unit
) {
    val indicatorColor = if (isHealthy) SkipFlowColors.HealthyGreen else SkipFlowColors.AlertRed

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(indicatorColor.copy(alpha = 0.14f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = indicatorColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SkipFlowColors.TextPrimary
                    )
                    Icon(
                        imageVector = if (isHealthy) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = indicatorColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp
                )
            }
        }

        if (!isHealthy) {
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onFixClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SkipFlowColors.ElectricBlue,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = fixButtonText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ContextAwareAppsCard(
    enabledAppsCount: Int,
    disabledAppsCount: Int,
    onNavigateToApps: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SkipFlowColors.DarkNavyCard),
        border = BorderStroke(1.dp, SkipFlowColors.NavyBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SkipFlowColors.ElectricBlue.copy(alpha = 0.16f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Apps,
                        contentDescription = null,
                        tint = SkipFlowColors.ElectricBlue
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "التفعيل الذكي حسب التطبيق",
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (disabledAppsCount == 0) {
                            "كل التطبيقات والألعاب مفعّلة تلقائياً ($enabledAppsCount) • خمول بالنظام"
                        } else {
                            "مفعّل لـ $enabledAppsCount تطبيق • معطّل: $disabledAppsCount"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onNavigateToApps,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SkipFlowColors.ElectricBlue.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "إدارة",
                    color = SkipFlowColors.ElectricCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
