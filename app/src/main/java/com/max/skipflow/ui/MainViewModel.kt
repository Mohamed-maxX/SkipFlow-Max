package com.max.skipflow.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.max.skipflow.SkipFlowApplication
import com.max.skipflow.data.DailyStats
import com.max.skipflow.data.InstalledAppInfo
import com.max.skipflow.data.RemoteRuleCounts
import com.max.skipflow.data.RemoteRulesSyncResult
import com.max.skipflow.data.SkipFlowSettings
import com.max.skipflow.data.SkipLogEntry
import com.max.skipflow.engine.CallStateMonitor
import com.max.skipflow.engine.SkipFlowAccessibilityService
import com.max.skipflow.health.HealthStatus
import com.max.skipflow.shizuku.ShizukuStatus
import com.max.skipflow.updater.GitHubReleaseInfo
import com.max.skipflow.updater.OtaUpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
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
    val snoozeRemainingMs: Long = 0L,
    val skipLogs: List<SkipLogEntry> = emptyList(),
    val healthStatus: HealthStatus = HealthStatus(),
    val canDrawOverlays: Boolean = false,
    val isServiceConnected: Boolean = false,
    val isCallInProgress: Boolean = false,
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = true,
    val searchQuery: String = "",
    val appFilterTab: AppFilterTab = AppFilterTab.ALL,
    val otaUpdateState: OtaUpdateState = OtaUpdateState.Idle,
    val lastRulesSyncEpochMs: Long = 0L,
    val remoteRuleCounts: RemoteRuleCounts = RemoteRuleCounts(),
    val isSyncingRules: Boolean = false,
    val rulesSyncFeedback: String? = null,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_INSTALLED
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
    private val remoteRulesManager = container.remoteRulesManager
    private val shizukuTapBridge = container.shizukuTapBridge
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _healthStatus = MutableStateFlow(healthCheckManager.checkHealth())
    private val _canDrawOverlays = MutableStateFlow(Settings.canDrawOverlays(application))
    private val _isCallInProgress = MutableStateFlow(false)
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val _isLoadingApps = MutableStateFlow(true)
    private val _searchQuery = MutableStateFlow("")
    private val _appFilterTab = MutableStateFlow(AppFilterTab.ALL)
    private val _snoozeRemainingMs = MutableStateFlow(repository.getSnoozeRemainingMs())
    private val _isSyncingRules = MutableStateFlow(false)
    private val _rulesSyncFeedback = MutableStateFlow<String?>(null)

    private val coreDataState = combine(
        repository.settings,
        repository.stats,
        repository.disabledPackages,
        repository.skipLogs,
        _snoozeRemainingMs
    ) { settings, stats, disabledPkgs, logs, snoozeRemainingMs ->
        CoreDataSlice(settings, stats, disabledPkgs, logs, snoozeRemainingMs)
    }

    private val appListState = combine(
        _installedApps,
        _isLoadingApps,
        _searchQuery,
        _appFilterTab
    ) { apps, loading, query, filter ->
        AppListSlice(apps, loading, query, filter)
    }

    private val remoteAndTurboState = combine(
        repository.lastRulesSyncEpochMs,
        remoteRulesManager.ruleCounts,
        _isSyncingRules,
        _rulesSyncFeedback,
        shizukuTapBridge.status
    ) { lastSync, ruleCounts, syncing, feedback, shizukuStatus ->
        RemoteAndTurboSlice(lastSync, ruleCounts, syncing, feedback, shizukuStatus)
    }

    private val systemStatusState = combine(
        _healthStatus,
        _canDrawOverlays,
        SkipFlowAccessibilityService.isServiceConnected,
        _isCallInProgress,
        otaUpdater.state
    ) { health, overlayAllowed, connected, inCall, ota ->
        SystemStatusSlice(health, overlayAllowed, connected, inCall, ota)
    }

    val uiState: StateFlow<MainUiState> = combine(
        coreDataState,
        appListState,
        systemStatusState,
        remoteAndTurboState
    ) { coreSlice, appsSlice, sysSlice, remoteSlice ->
        MainUiState(
            settings = coreSlice.settings,
            stats = coreSlice.stats,
            disabledPackages = coreSlice.disabledPackages,
            snoozeRemainingMs = coreSlice.snoozeRemainingMs,
            skipLogs = coreSlice.skipLogs,
            healthStatus = sysSlice.health,
            canDrawOverlays = sysSlice.canDrawOverlays,
            isServiceConnected = sysSlice.connected,
            isCallInProgress = sysSlice.inCall,
            installedApps = appsSlice.apps,
            isLoadingApps = appsSlice.loading,
            searchQuery = appsSlice.query,
            appFilterTab = appsSlice.filter,
            otaUpdateState = sysSlice.ota,
            lastRulesSyncEpochMs = remoteSlice.lastSyncEpochMs,
            remoteRuleCounts = remoteSlice.ruleCounts,
            isSyncingRules = remoteSlice.isSyncingRules,
            rulesSyncFeedback = remoteSlice.feedback,
            shizukuStatus = remoteSlice.shizukuStatus
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState()
    )

    init {
        refreshSystemState()
        loadInstalledApps()
        observeSnoozeCountdown()
        performDailySilentOtaCheckIfDue()
    }

    private fun performDailySilentOtaCheckIfDue() {
        if (!repository.shouldRunDailyOtaCheck()) return
        repository.recordOtaCheckedNow()
        viewModelScope.launch {
            otaUpdater.checkForUpdates(silent = true)
        }
    }

    private fun observeSnoozeCountdown() {
        viewModelScope.launch {
            repository.snoozeUntilEpochMs.collectLatest { untilMs ->
                while (true) {
                    val remaining = (untilMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    _snoozeRemainingMs.value = remaining
                    if (remaining <= 0L) {
                        if (untilMs > 0L) {
                            repository.cancelSmartSnooze()
                            SkipFlowAccessibilityService.notifyControlStateChanged(getApplication())
                        }
                        break
                    }
                    delay(1_000L)
                }
            }
        }
    }

    fun refreshSystemState() {
        repository.refreshDateRolloverIfNeeded()
        _snoozeRemainingMs.value = repository.getSnoozeRemainingMs()
        _healthStatus.value = healthCheckManager.checkHealth()
        _canDrawOverlays.value = Settings.canDrawOverlays(getApplication())
        _isCallInProgress.value = CallStateMonitor.isCallActive(audioManager)
        shizukuTapBridge.refreshStatus()
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
        if (repository.isSnoozed()) {
            repository.cancelSmartSnooze()
            repository.setEngineEnabled(true)
        } else {
            repository.setEngineEnabled(!repository.settings.value.engineEnabled)
        }
        SkipFlowAccessibilityService.notifyControlStateChanged(getApplication())
    }

    fun startSmartSnooze() {
        repository.startSmartSnooze()
        _snoozeRemainingMs.value = repository.getSnoozeRemainingMs()
        SkipFlowAccessibilityService.notifyControlStateChanged(getApplication())
    }

    fun cancelSmartSnooze() {
        repository.cancelSmartSnooze()
        repository.setEngineEnabled(true)
        _snoozeRemainingMs.value = 0L
        SkipFlowAccessibilityService.notifyControlStateChanged(getApplication())
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

    fun setBounceBackFromPlayStore(enabled: Boolean) {
        repository.setBounceBackFromPlayStore(enabled)
    }

    fun setAdCurtainEnabled(enabled: Boolean) {
        repository.setAdCurtainEnabled(enabled)
    }

    fun setAutoSwipeSocialAdsEnabled(enabled: Boolean) {
        repository.setAutoSwipeSocialAdsEnabled(enabled)
    }

    fun setAnnoyanceKillerEnabled(enabled: Boolean) {
        repository.setAnnoyanceKillerEnabled(enabled)
    }

    fun setSponsorBlockEnabled(enabled: Boolean) {
        repository.setSponsorBlockEnabled(enabled)
    }

    fun setShizukuTurboModeEnabled(enabled: Boolean) {
        repository.setShizukuTurboModeEnabled(enabled)
        if (enabled) {
            shizukuTapBridge.requestPermissionIfNeeded()
        } else {
            shizukuTapBridge.refreshStatus()
        }
    }

    fun requestShizukuPermission() {
        shizukuTapBridge.requestPermissionIfNeeded()
    }

    fun syncRulesNow() {
        if (_isSyncingRules.value) return
        viewModelScope.launch {
            _isSyncingRules.value = true
            _rulesSyncFeedback.value = null
            when (val res = remoteRulesManager.syncFromRemoteNow()) {
                is RemoteRulesSyncResult.Success -> {
                    _rulesSyncFeedback.value = "تم تحديث القواعد (v${res.config.version}) بنجاح"
                }
                is RemoteRulesSyncResult.Error -> {
                    _rulesSyncFeedback.value = res.message
                }
            }
            _isSyncingRules.value = false
        }
    }

    fun clearSkipLogs() {
        repository.clearSkipLogs()
    }

    fun setLiveDebugOverlayEnabled(context: Context, enabled: Boolean) {
        repository.setLiveDebugOverlayEnabled(enabled)
        if (enabled && !Settings.canDrawOverlays(context)) {
            openOverlayPermissionSettings(context)
        }
    }

    fun openOverlayPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
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
        repository.recordOtaCheckedNow()
        viewModelScope.launch {
            otaUpdater.checkForUpdates(silent = false)
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

    private data class CoreDataSlice(
        val settings: SkipFlowSettings,
        val stats: DailyStats,
        val disabledPackages: Set<String>,
        val skipLogs: List<SkipLogEntry>,
        val snoozeRemainingMs: Long
    )

    private data class AppListSlice(
        val apps: List<InstalledAppInfo>,
        val loading: Boolean,
        val query: String,
        val filter: AppFilterTab
    )

    private data class SystemStatusSlice(
        val health: HealthStatus,
        val canDrawOverlays: Boolean,
        val connected: Boolean,
        val inCall: Boolean,
        val ota: OtaUpdateState
    )

    private data class RemoteAndTurboSlice(
        val lastSyncEpochMs: Long,
        val ruleCounts: RemoteRuleCounts,
        val isSyncingRules: Boolean,
        val feedback: String?,
        val shizukuStatus: ShizukuStatus
    )
}
