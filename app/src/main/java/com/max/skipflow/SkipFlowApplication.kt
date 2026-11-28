package com.max.skipflow

import android.app.Application
import android.content.Context
import com.max.skipflow.data.AppCatalogRepository
import com.max.skipflow.data.RemoteRulesManager
import com.max.skipflow.data.SkipFlowRepository
import com.max.skipflow.engine.YouTubeSponsorBlockManager
import com.max.skipflow.health.HealthCheckManager
import com.max.skipflow.shizuku.ShizukuTapBridge
import com.max.skipflow.updater.GitHubOtaUpdater
import com.max.skipflow.vision.OpenCvAdIconDetector
import com.max.skipflow.vision.TessDataManager
import com.max.skipflow.vision.TesseractOcrEngine
import com.max.skipflow.workers.RulesSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val skipFlowRepository: SkipFlowRepository by lazy {
        SkipFlowRepository(appContext)
    }

    val appCatalogRepository: AppCatalogRepository by lazy {
        AppCatalogRepository(appContext)
    }

    val healthCheckManager: HealthCheckManager by lazy {
        HealthCheckManager(appContext)
    }

    val gitHubOtaUpdater: GitHubOtaUpdater by lazy {
        GitHubOtaUpdater(appContext)
    }

    val tessDataManager: TessDataManager by lazy {
        TessDataManager(appContext)
    }

    val openCvAdIconDetector: OpenCvAdIconDetector by lazy {
        OpenCvAdIconDetector()
    }

    val tesseractOcrEngine: TesseractOcrEngine by lazy {
        TesseractOcrEngine(tessDataManager)
    }

    val remoteRulesManager: RemoteRulesManager by lazy {
        RemoteRulesManager(appContext, skipFlowRepository)
    }

    val youTubeSponsorBlockManager: YouTubeSponsorBlockManager by lazy {
        YouTubeSponsorBlockManager()
    }

    val shizukuTapBridge: ShizukuTapBridge by lazy {
        ShizukuTapBridge(appContext)
    }
}

class SkipFlowApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    var isOpenCvInitialized: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        isOpenCvInitialized = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
        container = AppContainer(this)
        container.remoteRulesManager.loadAndApplyCachedOrBundledRules()
        container.shizukuTapBridge.registerListeners()
        RulesSyncWorker.schedulePeriodicSync(this)
        appScope.launch {
            container.tessDataManager.ensureTessDataReady()
        }
    }

    companion object {
        fun from(context: Context): SkipFlowApplication {
            return context.applicationContext as SkipFlowApplication
        }
    }
}
