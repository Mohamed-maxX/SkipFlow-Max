package com.max.skipflow.data

data class SkipFlowSettings(
    val engineEnabled: Boolean = true,
    val autoMuteYouTubeAds: Boolean = true,
    val vibrateOnSkip: Boolean = true,
    val toastOnSkip: Boolean = true,
    val pauseDuringCalls: Boolean = true,
    val bounceBackFromPlayStore: Boolean = true,
    val liveDebugOverlayEnabled: Boolean = true,
    val adCurtainEnabled: Boolean = false,
    val autoSwipeSocialAdsEnabled: Boolean = true,
    val annoyanceKillerEnabled: Boolean = false
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

enum class SkipDetectionMethod(val arabicLabel: String) {
    ACCESSIBILITY("إمكانية الوصول"),
    OPENCV("OpenCV"),
    OCR("Tesseract OCR"),
    AUTO_SWIPE("التمرير التلقائي"),
    ANNOYANCE_KILLER("نوافذ مزعجة")
}

data class SkipLogEntry(
    val id: Long,
    val packageName: String,
    val appName: String,
    val timestampMs: Long,
    val method: SkipDetectionMethod,
    val detail: String
)

data class FakeXTrapRecord(
    val packageName: String,
    val activityName: String,
    val normCenterX: Float,
    val normCenterY: Float,
    val viewId: String?,
    val recordedAtWallMs: Long
)
