package com.max.skipflow.data

data class SkipFlowSettings(
    val engineEnabled: Boolean = true,
    val autoMuteYouTubeAds: Boolean = true,
    val vibrateOnSkip: Boolean = true,
    val toastOnSkip: Boolean = true,
    val pauseDuringCalls: Boolean = true,
    val bounceBackFromPlayStore: Boolean = true,
    val liveDebugOverlayEnabled: Boolean = true
)

data class DailyStats(
    val dateKey: String = "",
    val skippedToday: Int = 0,
    val savedSecondsToday: Long = 0L
)

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isGame: Boolean,
    val isYouTube: Boolean
)
