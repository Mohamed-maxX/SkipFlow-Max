package com.max.skipflow.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.max.skipflow.SkipFlowApplication
import com.max.skipflow.data.RemoteRulesSyncResult
import java.util.concurrent.TimeUnit

/**
 * Periodically syncs `rules.json` from GitHub every 24 hours when network connectivity is available,
 * hot-reloading newly fetched rules directly into [com.max.skipflow.engine.AdDetectionRules].
 */
class RulesSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SkipFlowApplication ?: return Result.failure()
        return when (app.container.remoteRulesManager.syncFromRemoteNow()) {
            is RemoteRulesSyncResult.Success -> Result.success()
            is RemoteRulesSyncResult.Error -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "skipflow_remote_rules_sync"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<RulesSyncWorker>(24L, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
