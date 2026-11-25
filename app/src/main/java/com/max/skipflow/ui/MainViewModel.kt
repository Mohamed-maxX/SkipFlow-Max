package com.max.skipflow.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.max.skipflow.SkipFlowApplication
import com.max.skipflow.data.DailyStats
import com.max.skipflow.data.InstalledAppInfo
import com.max.skipflow.data.SkipFlowSettings
import com.max.skipflow.engine.CallStateMonitor
import com.max.skipflow.engine.SkipFlowAccessibilityService
import com.max.skipflow.health.HealthStatus
import com.max.skipflow.updater.GitHubReleaseInfo
import com.max.skipflow.updater.OtaUpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class AppFilterTab {
    ALL,
    ENABLED,
    DISABLED
}

data class MainUiState(
    val settings: SkipFlowSettings = SkipFlowSettings(),
    val stats: DailyStats = DailyStats(),
    val disabledPackages: Set<String> = emptySet(),
    val healthStatus: HealthStatus = HealthStatus(),
    val isServiceConnected: Boolean = false,
    val isCallInProgress: Boolean = false,
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = true,
    val searchQuery: String = "",
    val appFilterTab: AppFilterTab = AppFilterTab.ALL,
    val otaUpdateState: OtaUpdateState = OtaUpdateState.Idle
) {
    val enabledAppsCount: Int
        get() = installedApps.count { it.packageName !in disabledPackages }

    val filteredApps: List<InstalledAppInfo>
        get() {
            val query = searchQuery.trim().lowercase()
            return installedApps.filter { app ->
                val matchesFilter = when (appFilterTab) {
                    AppFilterTab.ALL -> true
                    AppFilterTab.ENABLED -> app.packageName !in disabledPackages
                    AppFilterTab.DISABLED -> app.packageName in disabledPackages
                }
                val matchesQuery = query.isEmpty() ||
                    app.appName.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query)
                matchesFilter && matchesQuery
            }
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as SkipFlowApplication).container
    private val repository = container.skipFlowRepository
    private val appCatalog = container.appCatalogRepository
    private val healthCheckManager = container.healthCheckManager
    private val otaUpdater = container.gitHubOtaUpdater
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _healthStatus = MutableStateFlow(healthCheckManager.checkHealth())
    private val _isCallInProgress = MutableStateFlow(false)
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val _isLoadingApps = MutableStateFlow(true)
    private val _searchQuery = MutableStateFlow("")
    private val _appFilterTab = MutableStateFlow(AppFilterTab.ALL)

    private val appListState = combine(
        _installedApps,
        _isLoadingApps,
        _searchQuery,
        _appFilterTab
    ) { apps, loading, query, filter ->
        AppListSlice(apps, loading, query, filter)
    }

    private val systemStatusState = combine(
        _healthStatus,
        SkipFlowAccessibilityService.isServiceConnected,
        _isCallInProgress,
        otaUpdater.state
    ) { health, connected, inCall, ota ->
        SystemStatusSlice(health, connected, inCall, ota)
    }

    val uiState: StateFlow<MainUiState> = combine(
        repository.settings,
        repository.stats,
        repository.disabledPackages,
        appListState,
        systemStatusState
    ) { settings, stats, disabledPkgs, appsSlice, sysSlice ->
        MainUiState(
            settings = settings,
            stats = stats,
            disabledPackages = disabledPkgs,
            healthStatus = sysSlice.health,
            isServiceConnected = sysSlice.connected,
            isCallInProgress = sysSlice.inCall,
            installedApps = appsSlice.apps,
            isLoadingApps = appsSlice.loading,
            searchQuery = appsSlice.query,
            appFilterTab = appsSlice.filter,
            otaUpdateState = sysSlice.ota
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState()
    )

    init {
        refreshSystemState()
        loadInstalledApps()
    }

    fun refreshSystemState() {
        repository.refreshDateRolloverIfNeeded()
        _healthStatus.value = healthCheckManager.checkHealth()
        _isCallInProgress.value = CallStateMonitor.isCallActive(audioManager)
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            _installedApps.value = appCatalog.loadInstalledApps()
            _isLoadingApps.value = false
        }
    }

    suspend fun loadAppIcon(packageName: String): ImageBitmap? {
        return appCatalog.loadAppIcon(packageName)
    }

    fun toggleMasterEngine() {
        repository.setEngineEnabled(!repository.settings.value.engineEnabled)
    }

    fun setAutoMuteYouTubeAds(enabled: Boolean) {
        repository.setAutoMuteYouTubeAds(enabled)
    }

    fun setVibrateOnSkip(enabled: Boolean) {
        repository.setVibrateOnSkip(enabled)
    }

    fun setToastOnSkip(enabled: Boolean) {
        repository.setToastOnSkip(enabled)
    }

    fun setPauseDuringCalls(enabled: Boolean) {
        repository.setPauseDuringCalls(enabled)
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) {
        repository.setPackageEnabled(packageName, enabled)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setAppFilterTab(tab: AppFilterTab) {
        _appFilterTab.value = tab
    }

    fun openAccessibilitySettings(context: Context) {
        healthCheckManager.openAccessibilitySettings(context)
    }

    fun requestBatteryOptimizationExemption(context: Context) {
        healthCheckManager.requestIgnoreBatteryOptimizations(context)
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            otaUpdater.checkForUpdates()
        }
    }

    fun downloadUpdate(release: GitHubReleaseInfo) {
        viewModelScope.launch {
            otaUpdater.downloadAndPrepareInstall(release)
        }
    }

    fun installDownloadedApk(context: Context, apkFile: File): Boolean {
        return otaUpdater.launchApkInstaller(context, apkFile)
    }

    fun openReleasePage(context: Context, url: String) {
        otaUpdater.openReleasePage(context, url)
    }

    private data class AppListSlice(
        val apps: List<InstalledAppInfo>,
        val loading: Boolean,
        val query: String,
        val filter: AppFilterTab
    )

    private data class SystemStatusSlice(
        val health: HealthStatus,
        val connected: Boolean,
        val inCall: Boolean,
        val ota: OtaUpdateState
    )
}
