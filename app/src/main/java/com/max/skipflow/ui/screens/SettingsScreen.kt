package com.max.skipflow.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.max.skipflow.BuildConfig
import com.max.skipflow.ui.MainUiState
import com.max.skipflow.ui.theme.SkipFlowColors
import com.max.skipflow.updater.GitHubOtaUpdater
import com.max.skipflow.updater.GitHubReleaseInfo
import com.max.skipflow.updater.OtaUpdateState
import java.io.File
import java.util.Locale

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    onAutoMuteChanged: (Boolean) -> Unit,
    onVibrateChanged: (Boolean) -> Unit,
    onToastChanged: (Boolean) -> Unit,
    onPauseDuringCallsChanged: (Boolean) -> Unit,
    onBounceBackFromPlayStoreChanged: (Boolean) -> Unit,
    onLiveDebugOverlayChanged: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: (GitHubReleaseInfo) -> Unit,
    onInstallApk: (File) -> Unit,
    onOpenReleasePage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SkipFlowColors.ObsidianBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "الإعدادات والتحديثات",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "تخصيص سلوك المحرك ونظام التحديث التلقائي عبر GitHub",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SkipFlowColors.DarkNavyCard),
            border = BorderStroke(1.dp, SkipFlowColors.NavyBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "سلوك التخطي والوسائط",
                    style = MaterialTheme.typography.titleMedium,
                    color = SkipFlowColors.ElectricCyan
                )

                HorizontalDivider(color = SkipFlowColors.NavyBorder)

                SettingsToggleRow(
                    icon = Icons.AutoMirrored.Rounded.VolumeOff,
                    title = "كتم صوت إعلانات يوتيوب تلقائياً",
                    subtitle = "كتم صوت الوسائط عند ظهور الإعلان وإعادة المستوى الأصلي فور التخطي",
                    checked = uiState.settings.autoMuteYouTubeAds,
                    onCheckedChange = onAutoMuteChanged
                )

                HorizontalDivider(color = SkipFlowColors.NavyBorder.copy(alpha = 0.6f))

                SettingsToggleRow(
                    icon = Icons.Rounded.Vibration,
                    title = "الاهتزاز عند التخطي",
                    subtitle = "تأكيد تخطي الإعلان بنبضة اهتزاز خفيفة",
                    checked = uiState.settings.vibrateOnSkip,
                    onCheckedChange = onVibrateChanged
                )

                HorizontalDivider(color = SkipFlowColors.NavyBorder.copy(alpha = 0.6f))

                SettingsToggleRow(
                    icon = Icons.Rounded.NotificationsActive,
                    title = "إشعار (Toast) عند التخطي",
                    subtitle = "عرض رسالة فورية مختصرة عند كل تخطي ناجح",
                    checked = uiState.settings.toastOnSkip,
                    onCheckedChange = onToastChanged
                )

                HorizontalDivider(color = SkipFlowColors.NavyBorder.copy(alpha = 0.6f))

                SettingsToggleRow(
                    icon = Icons.Rounded.Call,
                    title = "التوقف التلقائي أثناء المكالمات",
                    subtitle = "تعليق عمل المحرك تلقائياً خلال المكالمات الهاتفية والصوتية",
                    checked = uiState.settings.pauseDuringCalls,
                    onCheckedChange = onPauseDuringCallsChanged
                )

                HorizontalDivider(color = SkipFlowColors.NavyBorder.copy(alpha = 0.6f))

                SettingsToggleRow(
                    icon = Icons.AutoMirrored.Rounded.Undo,
                    title = "الرجوع التلقائي من المتجر",
                    subtitle = "العودة تلقائياً للعبة إذا فُتح متجر Google Play بالخطأ خلال 3 ثوانٍ من محاولة التخطي",
                    checked = uiState.settings.bounceBackFromPlayStore,
                    onCheckedChange = onBounceBackFromPlayStoreChanged
                )

                HorizontalDivider(color = SkipFlowColors.NavyBorder.copy(alpha = 0.6f))

                SettingsToggleRow(
                    icon = Icons.Rounded.BugReport,
                    title = "شريط التشخيص الحي فوق الشاشة",
                    subtitle = if (uiState.canDrawOverlays) {
                        "عرض سطر حي أعلى الشاشة لحالة النشاط ولقطة الشاشة والتعرف البصري (بدل Toast)"
                    } else {
                        "يتطلب إذن الظهور فوق التطبيقات (SYSTEM_ALERT_WINDOW) لعرض الشريط الحي"
                    },
                    checked = uiState.settings.liveDebugOverlayEnabled,
                    onCheckedChange = onLiveDebugOverlayChanged
                )

                if (uiState.settings.liveDebugOverlayEnabled && !uiState.canDrawOverlays) {
                    OutlinedButton(
                        onClick = onRequestOverlayPermission,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SkipFlowColors.WarningAmber.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(
                            text = "منح إذن الظهور فوق التطبيقات الآن",
                            color = SkipFlowColors.WarningAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        EngineCapabilitiesCard()

        GitHubOtaUpdaterCard(
            otaState = uiState.otaUpdateState,
            onCheckForUpdates = onCheckForUpdates,
            onDownloadUpdate = onDownloadUpdate,
            onInstallApk = onInstallApk,
            onOpenReleasePage = onOpenReleasePage
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SkipFlowColors.ElectricBlue.copy(alpha = 0.14f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SkipFlowColors.ElectricCyan,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SkipFlowColors.TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SkipFlowColors.ElectricBlue,
                uncheckedThumbColor = SkipFlowColors.TextSecondary,
                uncheckedTrackColor = SkipFlowColors.ElevatedNavy,
                uncheckedBorderColor = SkipFlowColors.NavyBorder
            )
        )
    }
}

@Composable
private fun EngineCapabilitiesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SkipFlowColors.DarkNavyCard),
        border = BorderStroke(1.dp, SkipFlowColors.NavyBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = SkipFlowColors.ElectricBlue
                )
                Text(
                    text = "تقنيات الحماية المدمجة (المرحلة الأولى)",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = "• التعرف المزدوج عبر النصوص ومعرّفات View IDs (YouTube / AdMob / Unity Ads / AppLovin)",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp
            )
            Text(
                text = "• منطقة الأمان لمنع الضغطات الخاطئة + اللمسة البشرية (إزاحة 2–5 بكسل وتأخير 100–250 مللي ثانية)",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp
            )
            Text(
                text = "• الإغلاق متعدد المراحل لإعلانات الألعاب + إغلاق نوافذ YouTube Premium المزعجة تلقائياً",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun GitHubOtaUpdaterCard(
    otaState: OtaUpdateState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: (GitHubReleaseInfo) -> Unit,
    onInstallApk: (File) -> Unit,
    onOpenReleasePage: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SkipFlowColors.DarkNavyCard),
        border = BorderStroke(1.dp, SkipFlowColors.NavyBorder)
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
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = SkipFlowColors.ElectricCyan
                    )
                    Column {
                        Text(
                            text = "نظام التحديث التلقائي (OTA)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = GitHubOtaUpdater.GITHUB_REPO,
                            fontSize = 11.sp,
                            color = SkipFlowColors.TextSecondary
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SkipFlowColors.ElevatedNavy
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkipFlowColors.ElectricCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            HorizontalDivider(color = SkipFlowColors.NavyBorder)

            when (otaState) {
                OtaUpdateState.Idle -> {
                    Text(
                        text = "تحقق من وجود إصدار أحدث مباشرة من مستودع GitHub الرسمي.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                OtaUpdateState.Checking -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "جاري الاتصال بـ GitHub للتحقق من التحديثات...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SkipFlowColors.ElectricCyan
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = SkipFlowColors.ElectricCyan,
                            trackColor = SkipFlowColors.ElevatedNavy
                        )
                    }
                }

                is OtaUpdateState.UpToDate -> {
                    Text(
                        text = "أنت تستخدم أحدث إصدار متاح حالياً (v${otaState.currentVersion}).",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = SkipFlowColors.HealthyGreen
                    )
                }

                is OtaUpdateState.NoReleasesYet -> {
                    Text(
                        text = "لا توجد إصدارات منشورة بعد على GitHub — إصدارك الحالي v${otaState.currentVersion}.",
                        fontSize = 13.sp,
                        color = SkipFlowColors.ElectricCyan
                    )
                }

                is OtaUpdateState.UpdateAvailable -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "يتوفر تحديث جديد: ${otaState.release.tagName}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = SkipFlowColors.HealthyGreen
                        )
                        if (otaState.release.notes.isNotBlank()) {
                            Text(
                                text = otaState.release.notes.take(240),
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 12.sp
                            )
                        }
                        if (otaState.release.apkDownloadUrl != null) {
                            val sizeMb = String.format(
                                Locale.US,
                                "%.1f MB",
                                otaState.release.apkSizeBytes / (1024f * 1024f)
                            )
                            Button(
                                onClick = { onDownloadUpdate(otaState.release) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SkipFlowColors.ElectricBlue,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("تنزيل وتثبيت التحديث ($sizeMb)")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onOpenReleasePage(otaState.release.htmlUrl) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, SkipFlowColors.ElectricCyan)
                            ) {
                                Text(
                                    text = "فتح صفحة الإصدار على GitHub",
                                    color = SkipFlowColors.ElectricCyan
                                )
                            }
                        }
                    }
                }

                is OtaUpdateState.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "جاري تنزيل التحديث... ${otaState.progressPercent}%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = SkipFlowColors.ElectricCyan
                        )
                        LinearProgressIndicator(
                            progress = { otaState.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = SkipFlowColors.ElectricCyan,
                            trackColor = SkipFlowColors.ElevatedNavy
                        )
                    }
                }

                is OtaUpdateState.ReadyToInstall -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "اكتمل تنزيل الإصدار ${otaState.release.tagName} وجاهز للتثبيت.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SkipFlowColors.HealthyGreen
                        )
                        Button(
                            onClick = { onInstallApk(otaState.apkFile) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SkipFlowColors.HealthyGreen,
                                contentColor = SkipFlowColors.ObsidianBlack
                            )
                        ) {
                            Text(
                                text = "تثبيت التحديث الآن",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                is OtaUpdateState.Error -> {
                    Text(
                        text = otaState.message,
                        fontSize = 13.sp,
                        color = SkipFlowColors.AlertRed
                    )
                }
            }

            if (otaState !is OtaUpdateState.Checking && otaState !is OtaUpdateState.Downloading) {
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SkipFlowColors.ElectricBlue.copy(alpha = 0.65f)),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = SkipFlowColors.ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "التحقق من التحديثات",
                        color = SkipFlowColors.ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
