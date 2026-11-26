package com.max.skipflow.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.max.skipflow.data.SkipDetectionMethod
import com.max.skipflow.data.SkipLogEntry
import com.max.skipflow.ui.theme.SkipFlowColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityLogScreen(
    logs: List<SkipLogEntry>,
    disabledPackages: Set<String>,
    onDisableApp: (String) -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember {
        SimpleDateFormat("HH:mm:ss • dd/MM", Locale.getDefault())
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SkipFlowColors.DarkNavyCard),
                border = BorderStroke(1.dp, SkipFlowColors.NavyBorder),
                shape = RoundedCornerShape(22.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "سجل النشاط الحي",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = SkipFlowColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "آخر ${logs.size} من أصل 50 عملية تخطي مسجلة محلياً",
                            style = MaterialTheme.typography.bodySmall,
                            color = SkipFlowColors.TextSecondary
                        )
                    }

                    if (logs.isNotEmpty()) {
                        TextButton(onClick = onClearLogs) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = null,
                                tint = SkipFlowColors.AlertRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "مسح الكل",
                                color = SkipFlowColors.AlertRed,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SkipFlowColors.DarkNavyCard),
                    border = BorderStroke(1.dp, SkipFlowColors.NavyBorder),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = SkipFlowColors.ElevatedNavy,
                            border = BorderStroke(1.dp, SkipFlowColors.ElectricBlue.copy(alpha = 0.35f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = SkipFlowColors.ElectricCyan,
                                modifier = Modifier
                                    .padding(14.dp)
                                    .size(30.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "لا توجد عمليات تخطي مسجلة بعد",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SkipFlowColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "بمجرد تخطي أي إعلان عبر Accessibility أو OpenCV أو OCR أو Shorts/Reels، سيظهر هنا فوراً مع خيار تعطيل التخطي لهذا التطبيق بضغطة واحدة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SkipFlowColors.TextSecondary
                        )
                    }
                }
            }
        } else {
            items(logs, key = { it.id }) { entry ->
                val isAppDisabled = entry.packageName in disabledPackages
                val formattedTime = remember(entry.timestampMs) {
                    timeFormatter.format(Date(entry.timestampMs))
                }
                ActivityLogRowCard(
                    entry = entry,
                    formattedTime = formattedTime,
                    isAppDisabled = isAppDisabled,
                    onDisableApp = { onDisableApp(entry.packageName) }
                )
            }
        }
    }
}

@Composable
private fun ActivityLogRowCard(
    entry: SkipLogEntry,
    formattedTime: String,
    isAppDisabled: Boolean,
    onDisableApp: () -> Unit
) {
    val badgeColor = methodBadgeColor(entry.method)
    val badgeLabel = methodBadgeLabel(entry.method)

    Card(
        colors = CardDefaults.cardColors(containerColor = SkipFlowColors.DarkNavyCard),
        border = BorderStroke(1.dp, SkipFlowColors.NavyBorder),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.appName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = SkipFlowColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = SkipFlowColors.TextSecondary
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = badgeColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (entry.detail.isNotBlank()) {
                Text(
                    text = entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = SkipFlowColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isAppDisabled) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SkipFlowColors.ElevatedNavy,
                    border = BorderStroke(1.dp, SkipFlowColors.HealthyGreen.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = SkipFlowColors.HealthyGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "تم تعطيل التخطي لهذا التطبيق",
                            style = MaterialTheme.typography.labelMedium,
                            color = SkipFlowColors.HealthyGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onDisableApp,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SkipFlowColors.WarningAmber.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SkipFlowColors.WarningAmber
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Block,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "تعطيل لهذا التطبيق",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun methodBadgeLabel(method: SkipDetectionMethod): String {
    return when (method) {
        SkipDetectionMethod.ACCESSIBILITY -> "Accessibility"
        SkipDetectionMethod.OPENCV -> "OpenCV"
        SkipDetectionMethod.OCR -> "OCR"
        SkipDetectionMethod.AUTO_SWIPE -> "Auto-Swipe"
        SkipDetectionMethod.ANNOYANCE_KILLER -> "منع المزعجات"
    }
}

private fun methodBadgeColor(method: SkipDetectionMethod): Color {
    return when (method) {
        SkipDetectionMethod.ACCESSIBILITY -> SkipFlowColors.ElectricBlue
        SkipDetectionMethod.OPENCV -> SkipFlowColors.ElectricCyan
        SkipDetectionMethod.OCR -> SkipFlowColors.WarningAmber
        SkipDetectionMethod.AUTO_SWIPE -> SkipFlowColors.HealthyGreen
        SkipDetectionMethod.ANNOYANCE_KILLER -> Color(0xFFC084FC)
    }
}
