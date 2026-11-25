package com.max.skipflow.health

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.max.skipflow.engine.SkipFlowAccessibilityService

data class HealthStatus(
    val accessibilityEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false
) {
    val isAccessibilityHealthy: Boolean
        get() = accessibilityEnabled || accessibilityConnected

    val isAllHealthy: Boolean
        get() = isAccessibilityHealthy && batteryOptimizationIgnored
}

class HealthCheckManager(private val context: Context) {

    fun checkHealth(): HealthStatus {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val accessibilityConnected = SkipFlowAccessibilityService.isServiceConnected.value
        val batteryIgnored = isIgnoringBatteryOptimizations()
        return HealthStatus(
            accessibilityEnabled = accessibilityEnabled,
            accessibilityConnected = accessibilityConnected,
            batteryOptimizationIgnored = batteryIgnored
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(context, SkipFlowAccessibilityService::class.java)

        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabledList = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()
        if (enabledList.any { it.resolveInfo?.serviceInfo?.let { si -> ComponentName(si.packageName, si.name) } == expectedComponent }) {
            return true
        }

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val flattened = expectedComponent.flattenToString()
        val shortFlattened = expectedComponent.flattenToShortString()
        return enabledServices.split(':').any { entry ->
            entry.equals(flattened, ignoreCase = true) || entry.equals(shortFlattened, ignoreCase = true)
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openAccessibilitySettings(launcherContext: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launcherContext.startActivity(intent)
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(launcherContext: Context) {
        val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            launcherContext.startActivity(directIntent)
        }.onFailure {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launcherContext.startActivity(fallbackIntent)
        }
    }
}
