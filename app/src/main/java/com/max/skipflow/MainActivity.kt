package com.max.skipflow

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.max.skipflow.ui.MainViewModel
import com.max.skipflow.ui.screens.ActivityLogScreen
import com.max.skipflow.ui.screens.AppsScreen
import com.max.skipflow.ui.screens.HomeScreen
import com.max.skipflow.ui.screens.SettingsScreen
import com.max.skipflow.ui.theme.SkipFlowColors
import com.max.skipflow.ui.theme.SkipFlowMaxTheme
import com.max.skipflow.updater.OtaUpdateState

private enum class SkipFlowTab(
    val title: String,
    val icon: ImageVector
) {
    HOME("الرئيسية", Icons.Rounded.Home),
    APPS("التطبيقات", Icons.Rounded.Apps),
    ACTIVITY_LOG("سجل النشاط", Icons.Rounded.History),
    SETTINGS("الإعدادات", Icons.Rounded.Settings)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            SkipFlowMaxTheme {
                SkipFlowAppShell(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun SkipFlowAppShell(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var selectedTab by rememberSaveable { mutableStateOf(SkipFlowTab.HOME) }
    var askedNotificationPermission by rememberSaveable { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        com.max.skipflow.engine.SkipFlowAccessibilityService.notifyControlStateChanged(context)
    }

    LaunchedEffect(uiState.isServiceConnected, askedNotificationPermission) {
        if (!askedNotificationPermission &&
            uiState.isServiceConnected &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(SkipFlowColors.ObsidianBlack),
        containerColor = SkipFlowColors.ObsidianBlack,
        bottomBar = {
            Box {
                HorizontalDivider(color = SkipFlowColors.NavyBorder, thickness = 1.dp)
                NavigationBar(
                    containerColor = SkipFlowColors.DarkNavyCard,
                    tonalElevation = 0.dp
                ) {
                    SkipFlowTab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { selectedTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = SkipFlowColors.ElectricCyan,
                                selectedTextColor = SkipFlowColors.ElectricCyan,
                                unselectedIconColor = SkipFlowColors.TextSecondary,
                                unselectedTextColor = SkipFlowColors.TextSecondary,
                                indicatorColor = SkipFlowColors.ElectricBlue.copy(alpha = 0.18f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            SkipFlowTab.HOME -> {
                HomeScreen(
                    uiState = uiState,
                    onToggleEngine = viewModel::toggleMasterEngine,
                    onStartSnooze = viewModel::startSmartSnooze,
                    onCancelSnooze = viewModel::cancelSmartSnooze,
                    onFixAccessibility = { viewModel.openAccessibilitySettings(context) },
                    onFixBatteryOptimization = { viewModel.requestBatteryOptimizationExemption(context) },
                    onNavigateToApps = { selectedTab = SkipFlowTab.APPS },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            SkipFlowTab.APPS -> {
                AppsScreen(
                    uiState = uiState,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onFilterTabSelected = viewModel::setAppFilterTab,
                    onToggleApp = viewModel::setPackageEnabled,
                    loadAppIcon = viewModel::loadAppIcon,
                    modifier = Modifier.padding(innerPadding)
                )
            }

            SkipFlowTab.ACTIVITY_LOG -> {
                ActivityLogScreen(
                    logs = uiState.skipLogs,
                    disabledPackages = uiState.disabledPackages,
                    onDisableApp = { pkg -> viewModel.setPackageEnabled(pkg, false) },
                    onClearLogs = viewModel::clearSkipLogs,
                    modifier = Modifier.padding(innerPadding)
                )
            }

            SkipFlowTab.SETTINGS -> {
                SettingsScreen(
                    uiState = uiState,
                    onAutoMuteChanged = viewModel::setAutoMuteYouTubeAds,
                    onVibrateChanged = viewModel::setVibrateOnSkip,
                    onToastChanged = viewModel::setToastOnSkip,
                    onPauseDuringCallsChanged = viewModel::setPauseDuringCalls,
                    onBounceBackFromPlayStoreChanged = viewModel::setBounceBackFromPlayStore,
                    onLiveDebugOverlayChanged = { enabled ->
                        viewModel.setLiveDebugOverlayEnabled(context, enabled)
                    },
                    onAdCurtainChanged = viewModel::setAdCurtainEnabled,
                    onAutoSwipeSocialAdsChanged = viewModel::setAutoSwipeSocialAdsEnabled,
                    onAnnoyanceKillerChanged = viewModel::setAnnoyanceKillerEnabled,
                    onSponsorBlockChanged = viewModel::setSponsorBlockEnabled,
                    onShizukuTurboModeChanged = viewModel::setShizukuTurboModeEnabled,
                    onRequestShizukuPermission = viewModel::requestShizukuPermission,
                    onSyncRulesNow = viewModel::syncRulesNow,
                    onRequestOverlayPermission = { viewModel.openOverlayPermissionSettings(context) },
                    onCheckForUpdates = viewModel::checkForUpdates,
                    onDownloadUpdate = viewModel::downloadUpdate,
                    onInstallApk = { file -> viewModel.installDownloadedApk(context, file) },
                    onOpenReleasePage = { url -> viewModel.openReleasePage(context, url) },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }

    var dismissedUpdateTag by rememberSaveable { mutableStateOf<String?>(null) }
    val otaState = uiState.otaUpdateState
    val activeDialogTag = when (otaState) {
        is OtaUpdateState.UpdateAvailable -> otaState.release.tagName
        is OtaUpdateState.Downloading -> "downloading"
        is OtaUpdateState.ReadyToInstall -> otaState.release.tagName
        else -> null
    }
    if (activeDialogTag != null && activeDialogTag != dismissedUpdateTag && otaState !is OtaUpdateState.Idle) {
        val showDialog = when (otaState) {
            is OtaUpdateState.UpdateAvailable -> otaState.release.tagName != dismissedUpdateTag
            is OtaUpdateState.Downloading -> dismissedUpdateTag != "downloading"
            is OtaUpdateState.ReadyToInstall -> otaState.release.tagName != dismissedUpdateTag
            else -> false
        }
        if (showDialog) {
            OtaUpdateDialog(
                otaState = otaState,
                onDismiss = {
                    dismissedUpdateTag = activeDialogTag
                },
                onDownload = viewModel::downloadUpdate,
                onInstall = { file -> viewModel.installDownloadedApk(context, file) },
                onOpenReleasePage = { url -> viewModel.openReleasePage(context, url) }
            )
        }
    }
}

@Composable
private fun OtaUpdateDialog(
    otaState: OtaUpdateState,
    onDismiss: () -> Unit,
    onDownload: (com.max.skipflow.updater.GitHubReleaseInfo) -> Unit,
    onInstall: (java.io.File) -> Unit,
    onOpenReleasePage: (String) -> Unit
) {
    val titleText = when (otaState) {
        is OtaUpdateState.UpdateAvailable -> "تحديث جديد متاح (${otaState.release.tagName})"
        is OtaUpdateState.Downloading -> "جاري تحميل التحديث..."
        is OtaUpdateState.ReadyToInstall -> "التحديث جاهز للتثبيت (${otaState.release.tagName})"
        else -> "تحديث SkipFlow Max"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SkipFlowColors.DarkNavyCard,
        titleContentColor = SkipFlowColors.TextPrimary,
        textContentColor = SkipFlowColors.TextSecondary,
        title = {
            Text(text = titleText, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (otaState) {
                    is OtaUpdateState.UpdateAvailable -> {
                        Text(
                            text = otaState.release.notes.ifBlank {
                                "يتوفر إصدار أحدث من SkipFlow Max مع تحسينات في الأداء والتخطي."
                            },
                            fontSize = 13.sp,
                            color = SkipFlowColors.TextSecondary
                        )
                    }
                    is OtaUpdateState.Downloading -> {
                        Text(
                            text = "نسبة التحميل: ${otaState.progressPercent}%",
                            color = SkipFlowColors.ElectricCyan
                        )
                        LinearProgressIndicator(
                            progress = { otaState.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = SkipFlowColors.ElectricCyan,
                            trackColor = SkipFlowColors.ElevatedNavy
                        )
                    }
                    is OtaUpdateState.ReadyToInstall -> {
                        Text(
                            text = "اكتمل تنزيل الحزمة بنجاح. اضغط تثبيت التحديث الآن للمتابعة.",
                            color = SkipFlowColors.HealthyGreen
                        )
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (otaState) {
                is OtaUpdateState.UpdateAvailable -> {
                    Button(
                        onClick = {
                            if (otaState.release.apkDownloadUrl != null) {
                                onDownload(otaState.release)
                            } else {
                                onOpenReleasePage(otaState.release.htmlUrl)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SkipFlowColors.ElectricBlue)
                    ) {
                        Text(
                            text = if (otaState.release.apkDownloadUrl != null) "تحميل التحديث" else "فتح صفحة التحميل"
                        )
                    }
                }
                is OtaUpdateState.ReadyToInstall -> {
                    Button(
                        onClick = { onInstall(otaState.apkFile) },
                        colors = ButtonDefaults.buttonColors(containerColor = SkipFlowColors.HealthyGreen)
                    ) {
                        Text("تثبيت الآن", color = SkipFlowColors.ObsidianBlack, fontWeight = FontWeight.Bold)
                    }
                }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("لاحقاً", color = SkipFlowColors.TextSecondary)
            }
        }
    )
}
