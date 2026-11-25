package com.max.skipflow.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.max.skipflow.SkipFlowApplication
import com.max.skipflow.data.SkipFlowRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

class SkipFlowAccessibilityService : AccessibilityService() {

    private lateinit var repository: SkipFlowRepository
    private lateinit var audioManager: AudioManager
    private lateinit var volumeController: YouTubeVolumeController

    private val mainHandler = Handler(Looper.getMainLooper())
    private val multiStageStateMachine = MultiStageAdStateMachine()

    private var lastScanElapsedMs: Long = 0L
    private var lastEnabledGamePackage: String? = null
    private var lastEnabledGameActiveAtMs: Long = 0L
    private var lastToastShownAtMs: Long = 0L
    private var pendingTapRunnable: Runnable? = null
    private var pendingVolumeRestoreRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        repository = SkipFlowApplication.from(this).container.skipFlowRepository
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volumeController = YouTubeVolumeController(AndroidMediaVolumeBackend(audioManager))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentSettings = repository.settings.value
        if (!currentSettings.engineEnabled) {
            cancelPendingTap()
            volumeController.forceRestore()
            return
        }

        val eventPkg = event?.packageName?.toString() ?: return
        if (eventPkg == packageName || AdDetectionRules.isInputMethodPackage(eventPkg)) {
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val enabledPackages = repository.enabledPackages.value

        // Context-Aware O(1) idle check: zero tree traversal for system or disabled apps
        val monitoredPackage = resolveMonitoredPackage(eventPkg, enabledPackages, nowElapsed)
        if (monitoredPackage == null) {
            _currentActivePackage.value = null
            cancelPendingTap()
            if (eventPkg != AdDetectionRules.YOUTUBE_PACKAGE) {
                volumeController.forceRestore()
            }
            return
        }

        _currentActivePackage.value = monitoredPackage

        // Pause automatically during phone/VoIP calls
        if (currentSettings.pauseDuringCalls && CallStateMonitor.isCallActive(audioManager)) {
            cancelPendingTap()
            volumeController.forceRestore()
            return
        }

        // Throttle rapid window content updates to keep CPU overhead minimal
        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val minInterval = if (isWindowChange) WINDOW_STATE_SCAN_GAP_MS else CONTENT_SCAN_THROTTLE_MS
        if (nowElapsed - lastScanElapsedMs < minInterval) {
            return
        }
        lastScanElapsedMs = nowElapsed

        val root = rootInActiveWindow ?: return
        val rootPkg = root.packageName?.toString() ?: monitoredPackage
        if (rootPkg != eventPkg && !isMonitoredExactOrAdActivity(rootPkg, enabledPackages, nowElapsed)) {
            safeRecycle(root)
            return
        }

        val scanResult = try {
            scanWindowTree(root, monitoredPackage)
        } finally {
            safeRecycle(root)
        }

        handleYouTubeAutoMute(
            monitoredPackage = monitoredPackage,
            hasYouTubeAd = scanResult.hasYouTubeAdIndicator,
            autoMuteEnabled = currentSettings.autoMuteYouTubeAds,
            nowElapsed = nowElapsed
        )

        val candidate = scanResult.bestCandidate ?: return
        val decision = multiStageStateMachine.evaluateCandidate(
            packageName = monitoredPackage,
            bounds = candidate.bounds,
            targetType = candidate.targetType,
            nowMs = nowElapsed
        )

        if (decision !is StageDecision.ExecuteTap) {
            return
        }

        val tapPlan = HumanTouchGenerator.generateTapPlan(candidate.bounds)
        scheduleHumanTap(
            plan = tapPlan,
            targetType = candidate.targetType,
            stageNumber = decision.stageNumber,
            countAsNewAdSkip = decision.countAsNewAdSkip,
            monitoredPackage = monitoredPackage
        )
    }

    override fun onInterrupt() {
        cancelPendingTap()
        cancelPendingVolumeRestore()
        volumeController.forceRestore()
        _isServiceConnected.value = false
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cancelPendingTap()
        cancelPendingVolumeRestore()
        volumeController.forceRestore()
        _isServiceConnected.value = false
        _currentActivePackage.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cancelPendingTap()
        cancelPendingVolumeRestore()
        volumeController.forceRestore()
        _isServiceConnected.value = false
        _currentActivePackage.value = null
        super.onDestroy()
    }

    private fun resolveMonitoredPackage(
        eventPkg: String,
        enabledPackages: Set<String>,
        nowElapsed: Long
    ): String? {
        if (AdDetectionRules.isSystemPackage(eventPkg)) return null

        if (eventPkg in enabledPackages) {
            if (eventPkg != AdDetectionRules.YOUTUBE_PACKAGE) {
                lastEnabledGamePackage = eventPkg
                lastEnabledGameActiveAtMs = nowElapsed
            }
            return eventPkg
        }

        // Handle AdMob out-of-process AdActivity launched from an active enabled app/game
        if (eventPkg == AdDetectionRules.ADMOB_AD_ACTIVITY_PACKAGE) {
            val recentGame = lastEnabledGamePackage
            if (recentGame != null &&
                recentGame in enabledPackages &&
                nowElapsed - lastEnabledGameActiveAtMs <= ADMOB_CONTEXT_WINDOW_MS
            ) {
                return recentGame
            }
        }

        return null
    }

    private fun isMonitoredExactOrAdActivity(
        pkg: String,
        enabledPackages: Set<String>,
        nowElapsed: Long
    ): Boolean {
        return resolveMonitoredPackage(pkg, enabledPackages, nowElapsed) != null
    }

    private fun handleYouTubeAutoMute(
        monitoredPackage: String,
        hasYouTubeAd: Boolean,
        autoMuteEnabled: Boolean,
        nowElapsed: Long
    ) {
        if (monitoredPackage != AdDetectionRules.YOUTUBE_PACKAGE) {
            cancelPendingVolumeRestore()
            volumeController.forceRestore()
            return
        }

        if (hasYouTubeAd) {
            cancelPendingVolumeRestore()
            volumeController.onAdVisible(autoMuteEnabled)
        } else if (volumeController.isMuted()) {
            volumeController.onAdNotVisible(nowElapsed)
            if (volumeController.isMuted() && pendingVolumeRestoreRunnable == null) {
                val restoreRunnable = Runnable {
                    pendingVolumeRestoreRunnable = null
                    volumeController.forceRestore()
                }
                pendingVolumeRestoreRunnable = restoreRunnable
                mainHandler.postDelayed(restoreRunnable, YouTubeVolumeController.DEFAULT_RESTORE_GRACE_MS)
            }
        }
    }

    private data class CandidateMatch(
        val targetType: AdTargetType,
        val bounds: BoundsRect
    )

    private data class TreeScanResult(
        val bestCandidate: CandidateMatch?,
        val hasYouTubeAdIndicator: Boolean
    )

    private fun scanWindowTree(
        root: AccessibilityNodeInfo,
        monitoredPackage: String
    ): TreeScanResult {
        val (screenW, screenH) = getScreenDimensions()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        var visitedNodes = 0
        var hasYouTubeAd = false
        var bestCandidate: CandidateMatch? = null
        var bestPriority = Int.MAX_VALUE
        val tempRect = Rect()

        while (queue.isNotEmpty() && visitedNodes < MAX_SCAN_NODES) {
            val (node, depth) = queue.removeFirst()
            visitedNodes++

            val isRoot = node === root
            try {
                if (node.isVisibleToUser) {
                    val viewId = node.viewIdResourceName
                    val text = node.text
                    val desc = node.contentDescription

                    if (monitoredPackage == AdDetectionRules.YOUTUBE_PACKAGE && !hasYouTubeAd) {
                        if (AdDetectionRules.isYouTubeAdIndicator(viewId, text, desc)) {
                            hasYouTubeAd = true
                        }
                    }

                    val clickableSelfOrParent = node.isClickable || isParentClickable(node)
                    val targetType = AdDetectionRules.matchCandidate(
                        packageName = monitoredPackage,
                        viewIdResourceName = viewId,
                        text = text,
                        contentDescription = desc,
                        isClickableOrHasClickableParent = clickableSelfOrParent
                    )

                    if (targetType != null) {
                        val targetBounds = resolveEffectiveBounds(node, screenW, screenH, tempRect)
                        if (SafeZoneEvaluator.isWithinSafeZone(targetBounds, screenW, screenH, targetType)) {
                            val priority = priorityFor(targetType)
                            if (priority < bestPriority) {
                                bestPriority = priority
                                bestCandidate = CandidateMatch(targetType, targetBounds)
                            }
                        }
                    }

                    if (depth < MAX_SCAN_DEPTH) {
                        val childCount = node.childCount
                        for (i in 0 until childCount) {
                            val child = node.getChild(i) ?: continue
                            queue.add(child to (depth + 1))
                        }
                    }
                }
            } finally {
                if (!isRoot) {
                    safeRecycle(node)
                }
            }
        }

        // Recycle any remaining queued nodes if MAX_SCAN_NODES limit stopped traversal early
        while (queue.isNotEmpty()) {
            val (remaining, _) = queue.removeFirst()
            if (remaining !== root) {
                safeRecycle(remaining)
            }
        }

        return TreeScanResult(
            bestCandidate = bestCandidate,
            hasYouTubeAdIndicator = hasYouTubeAd
        )
    }

    private fun resolveEffectiveBounds(
        node: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
        tempRect: Rect
    ): BoundsRect {
        node.getBoundsInScreen(tempRect)
        val selfBounds = BoundsRect(tempRect.left, tempRect.top, tempRect.right, tempRect.bottom)
        if (node.isClickable || selfBounds.width >= 36 && selfBounds.height >= 36) {
            return selfBounds
        }

        val parent = node.parent ?: return selfBounds
        return try {
            if (parent.isClickable) {
                val parentRect = Rect()
                parent.getBoundsInScreen(parentRect)
                val parentBounds = BoundsRect(parentRect.left, parentRect.top, parentRect.right, parentRect.bottom)
                if (!parentBounds.isEmpty &&
                    parentBounds.width <= screenW * 0.55f &&
                    parentBounds.height <= screenH * 0.18f
                ) {
                    parentBounds
                } else {
                    selfBounds
                }
            } else {
                selfBounds
            }
        } finally {
            safeRecycle(parent)
        }
    }

    private fun isParentClickable(node: AccessibilityNodeInfo): Boolean {
        val parent = node.parent ?: return false
        return try {
            parent.isClickable
        } finally {
            safeRecycle(parent)
        }
    }

    private fun priorityFor(type: AdTargetType): Int = when (type) {
        AdTargetType.YOUTUBE_SKIP_AD -> 1
        AdTargetType.GAME_AD_SKIP -> 2
        AdTargetType.GAME_AD_CLOSE -> 3
        AdTargetType.YOUTUBE_POPUP_DISMISS -> 4
    }

    private fun scheduleHumanTap(
        plan: HumanTapPlan,
        targetType: AdTargetType,
        stageNumber: Int,
        countAsNewAdSkip: Boolean,
        monitoredPackage: String
    ) {
        cancelPendingTap()
        val runnable = Runnable {
            pendingTapRunnable = null
            val settings = repository.settings.value
            if (!settings.engineEnabled) return@Runnable
            if (settings.pauseDuringCalls && CallStateMonitor.isCallActive(audioManager)) return@Runnable
            if (monitoredPackage !in repository.enabledPackages.value) return@Runnable

            val path = Path().apply {
                moveTo(plan.x, plan.y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, plan.strokeDurationMs))
                .build()

            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        onSkipGestureSucceeded(
                            targetType = targetType,
                            stageNumber = stageNumber,
                            countAsNewAdSkip = countAsNewAdSkip
                        )
                    }
                },
                mainHandler
            )
        }
        pendingTapRunnable = runnable
        mainHandler.postDelayed(runnable, plan.preDelayMs)
    }

    private fun onSkipGestureSucceeded(
        targetType: AdTargetType,
        stageNumber: Int,
        countAsNewAdSkip: Boolean
    ) {
        if (targetType == AdTargetType.YOUTUBE_SKIP_AD) {
            cancelPendingVolumeRestore()
            volumeController.forceRestore()
        }

        if (countAsNewAdSkip) {
            repository.recordAdSkipped(targetType.estimatedSavedSeconds)
        }

        val settings = repository.settings.value
        if (settings.vibrateOnSkip) {
            performSubtleVibration()
        }
        if (settings.toastOnSkip) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastToastShownAtMs >= TOAST_COOLDOWN_MS) {
                lastToastShownAtMs = now
                val message = when {
                    targetType == AdTargetType.YOUTUBE_POPUP_DISMISS -> "تم إغلاق نافذة يوتيوب المزعجة"
                    stageNumber > 1 -> "تم إغلاق المرحلة $stageNumber من الإعلان"
                    else -> "تم تخطي الإعلان تلقائياً"
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performSubtleVibration() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(25L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun cancelPendingTap() {
        pendingTapRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingTapRunnable = null
    }

    private fun cancelPendingVolumeRestore() {
        pendingVolumeRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingVolumeRestoreRunnable = null
    }

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { node.recycle() }
        }
    }

    companion object {
        private const val MAX_SCAN_NODES = 240
        private const val MAX_SCAN_DEPTH = 24
        private const val CONTENT_SCAN_THROTTLE_MS = 260L
        private const val WINDOW_STATE_SCAN_GAP_MS = 90L
        private const val ADMOB_CONTEXT_WINDOW_MS = 25_000L
        private const val TOAST_COOLDOWN_MS = 2_400L

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _currentActivePackage = MutableStateFlow<String?>(null)
        val currentActivePackage: StateFlow<String?> = _currentActivePackage.asStateFlow()
    }
}
