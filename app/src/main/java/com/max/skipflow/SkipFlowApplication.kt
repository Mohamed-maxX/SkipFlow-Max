package com.max.skipflow

import android.app.Application
import android.content.Context
import com.max.skipflow.data.AppCatalogRepository
import com.max.skipflow.data.SkipFlowRepository
import com.max.skipflow.health.HealthCheckManager
import com.max.skipflow.updater.GitHubOtaUpdater

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
}

class SkipFlowApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        fun from(context: Context): SkipFlowApplication {
            return context.applicationContext as SkipFlowApplication
        }
    }
}
