package com.max.skipflow.engine

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.max.skipflow.R
import com.max.skipflow.SkipFlowApplication

/**
 * Quick Settings Tile allowing users to toggle SkipFlow Max ON/OFF (or resume from Smart Snooze)
 * straight from Android's notification shade.
 */
class SkipFlowTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileVisuals()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext as? SkipFlowApplication ?: return
        val repo = app.container.skipFlowRepository

        if (!SkipFlowAccessibilityService.isRunning) {
            repo.cancelSmartSnooze()
            repo.setEngineEnabled(true)
            openAccessibilitySettings()
            refreshTileVisuals()
            return
        }

        if (repo.isSnoozed()) {
            repo.cancelSmartSnooze()
            repo.setEngineEnabled(true)
        } else {
            val nextEnabled = !repo.settings.value.engineEnabled
            repo.setEngineEnabled(nextEnabled)
        }

        SkipFlowAccessibilityService.notifyControlStateChanged(applicationContext)
        refreshTileVisuals()
    }

    private fun refreshTileVisuals() {
        val tile = qsTile ?: return
        val app = applicationContext as? SkipFlowApplication ?: return
        val repo = app.container.skipFlowRepository
        val settings = repo.settings.value
        val isServiceRunning = SkipFlowAccessibilityService.isRunning
        val snoozeRemainingMs = repo.getSnoozeRemainingMs()

        tile.icon = Icon.createWithResource(this, R.drawable.ic_skipflow_tile)
        tile.label = getString(R.string.quick_tile_label)

        when {
            !isServiceRunning -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "بحاجة لتفعيل"
                }
            }

            snoozeRemainingMs > 0L -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "مؤقت ${ServiceNotificationManager.formatCountdown(snoozeRemainingMs)}"
                }
            }

            settings.engineEnabled -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "مفعّل"
                }
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "متوقف"
                }
            }
        }
        tile.updateTile()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                305,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            launchActivityPreApi34(intent)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchActivityPreApi34(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    companion object {
        fun requestTileRefresh(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, SkipFlowTileService::class.java)
                )
            }
        }
    }
}
