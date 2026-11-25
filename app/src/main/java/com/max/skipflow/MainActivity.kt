package com.max.skipflow

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.max.skipflow.ui.screens.AppsScreen
import com.max.skipflow.ui.screens.HomeScreen
import com.max.skipflow.ui.screens.SettingsScreen
import com.max.skipflow.ui.theme.SkipFlowColors
import com.max.skipflow.ui.theme.SkipFlowMaxTheme

private enum class SkipFlowTab(
    val title: String,
    val icon: ImageVector
) {
    HOME("الرئيسية", Icons.Rounded.Home),
    APPS("التطبيقات", Icons.Rounded.Apps),
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
                                    fontSize = 12.sp,
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

            SkipFlowTab.SETTINGS -> {
                SettingsScreen(
                    uiState = uiState,
                    onAutoMuteChanged = viewModel::setAutoMuteYouTubeAds,
                    onVibrateChanged = viewModel::setVibrateOnSkip,
                    onToastChanged = viewModel::setToastOnSkip,
                    onPauseDuringCallsChanged = viewModel::setPauseDuringCalls,
                    onCheckForUpdates = viewModel::checkForUpdates,
                    onDownloadUpdate = viewModel::downloadUpdate,
                    onInstallApk = { file -> viewModel.installDownloadedApk(context, file) },
                    onOpenReleasePage = { url -> viewModel.openReleasePage(context, url) },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}
