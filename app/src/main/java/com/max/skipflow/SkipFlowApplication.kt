package com.max.skipflow

import android.app.Application
import android.content.Context
import com.max.skipflow.data.AppCatalogRepository
import com.max.skipflow.data.SkipFlowRepository
import com.max.skipflow.health.HealthCheckManager
import com.max.skipflow.updater.GitHubOtaUpdater
import com.max.skipflow.vision.OpenCvAdIconDetector
import com.max.skipflow.vision.TessDataManager
import com.max.skipflow.vision.TesseractOcrEngine
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
