package com.max.skipflow.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.max.skipflow.SkipFlowApplication

/**
 * Handles notification shade actions for 10-minute Smart Snooze and instant resume.
 */
class SnoozeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val repo = (context.applicationContext as? SkipFlowApplication)
            ?.container
            ?.skipFlowRepository ?: return

        when (action) {
            ServiceNotificationManager.ACTION_SNOOZE_10M -> {
                repo.startSmartSnooze()
            }

            ServiceNotificationManager.ACTION_RESUME_ENGINE -> {
                repo.cancelSmartSnooze()
                repo.setEngineEnabled(true)
            }

            else -> return
        }

        SkipFlowAccessibilityService.notifyControlStateChanged(context.applicationContext)
    }
}
