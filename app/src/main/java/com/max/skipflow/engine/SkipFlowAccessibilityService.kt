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
import android.view.accessibility.AccessibilityWindowInfo
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
    private var lastWindowPackage: String? = null
    private var lastWindowClassName: String? = null
    private var lastToastShownAtMs: Long = 0L
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
            volumeController.forceRestore()
            return
        }

        val eventPkg = event?.packageName?.toString() ?: return
        if (eventPkg == packageName || AdDetectionRules.isInputMethodPackage(eventPkg)) {
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (isWindowChange) {
            val eventClass = event.className?.toString()
            if (!eventClass.isNullOrBlank()) {
                lastWindowPackage = eventPkg
                lastWindowClassName = eventClass
            } else if (lastWindowPackage != eventPkg) {
                lastWindowPackage = eventPkg
                lastWindowClassName = null
            }
        }

        val disabledPackages = repository.disabledPackages.value

        // Context-Aware O(1) idle check: zero tree traversal for system or disabled apps
        val monitoredPackage = resolveMonitoredPackage(
            eventPkg = eventPkg,
            eventClassName = event.className,
            disabledPackages = disabledPackages,
            nowElapsed = nowElapsed
        )
        if (monitoredPackage == null) {
            _currentActivePackage.value = null
            if (eventPkg != AdDetectionRules.YOUTUBE_PACKAGE) {
                volumeController.forceRestore()
            }
            return
        }

        _currentActivePackage.value = monitoredPackage

        // Pause automatically during phone/VoIP calls
        if (currentSettings.pauseDuringCalls && CallStateMonitor.isCallActive(audioManager)) {
            volumeController.forceRestore()
            return
        }

        // Throttle rapid window content updates while keeping response fast
        val minInterval = if (isWindowChange) WINDOW_STATE_SCAN_GAP_MS else CONTENT_SCAN_THROTTLE_MS
        if (nowElapsed - lastScanElapsedMs < minInterval) {
            return
        }
        lastScanElapsedMs = nowElapsed

        val candidateWindows = collectCandidateRootNodes(monitoredPackage)
        if (candidateWindows.isEmpty()) return

        var hasYouTubeAd = false
        var bestCandidate: CandidateMatch? = null

        try {
            val adActivityContext = AdDetectionRules.isAdActivityName(event.className) ||
                (lastWindowPackage == eventPkg && AdDetectionRules.isAdActivityName(lastWindowClassName)) ||
                eventPkg == AdDetectionRules.ADMOB_AD_ACTIVITY_PACKAGE

            for (root in candidateWindows) {
                val result = scanWindowTree(
                    root = root,
                    monitoredPackage = monitoredPackage,
                    initialAdActivityHint = adActivityContext || AdDetectionRules.isAdActivityName(root.className)
                )
                if (result.hasYouTubeAdIndicator) {
                    hasYouTubeAd = true
                }
                val match = result.bestCandidate
                if (match != null) {
                    if (bestCandidate == null || match.priority < bestCandidate.priority) {
                        bestCandidate?.recycleNode(this)
                        bestCandidate = match
                    } else {
                        match.recycleNode(this)
                    }
                }
            }

            handleYouTubeAutoMute(
                monitoredPackage = monitoredPackage,
                hasYouTubeAd = hasYouTubeAd,
                autoMuteEnabled = currentSettings.autoMuteYouTubeAds,
                nowElapsed = nowElapsed
            )

            val winner = bestCandidate ?: return
            val decision = multiStageStateMachine.evaluateCandidate(
                packageName = monitoredPackage,
                bounds = winner.bounds,
                targetType = winner.targetType,
                nowMs = nowElapsed
            )

            if (decision is StageDecision.ExecuteTap) {
                executeTargetAction(
                    candidate = winner,
                    stageNumber = decision.stageNumber,
                    countAsNewAdSkip = decision.countAsNewAdSkip
                )
            }
        } finally {
            bestCandidate?.recycleNode(this)
            candidateWindows.forEach { safeRecycle(it) }
        }
    }

    override fun onInterrupt() {
        cancelPendingVolumeRestore()
        volumeController.forceRestore()
        _isServiceConnected.value = false
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cancelPendingVolumeRestore()
        volumeController.forceRestore()
        _isServiceConnected.value = false
        _currentActivePackage.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cancelPendingVolumeRestore()
        volumeController.forceRestore()
        _isServiceConnected.value = false
        _currentActivePackage.value = null
        super.onDestroy()
    }

    private fun resolveMonitoredPackage(
        eventPkg: String,
        eventClassName: CharSequence?,
        disabledPackages: Set<String>,
        nowElapsed: Long
    ): String? {
        if (AdDetectionRules.isSystemPackage(eventPkg)) return null

        // Handle Google Mobile Ads out-of-process AdActivity in com.google.android.gms
        if (eventPkg == AdDetectionRules.ADMOB_AD_ACTIVITY_PACKAGE) {
            val recentGame = lastEnabledGamePackage
            val isAdActivity = AdDetectionRules.isAdActivityName(eventClassName) ||
                (lastWindowPackage == eventPkg && AdDetectionRules.isAdActivityName(lastWindowClassName))
            if (isAdActivity) {
                return recentGame?.takeIf { it !in disabledPackages } ?: eventPkg
            }
            if (recentGame != null &&
                recentGame !in disabledPackages &&
                nowElapsed - lastEnabledGameActiveAtMs <= ADMOB_CONTEXT_WINDOW_MS
            ) {
                return recentGame
            }
            return null
        }

        if (eventPkg in disabledPackages) {
            return null
        }

        if (eventPkg != AdDetectionRules.YOUTUBE_PACKAGE) {
            lastEnabledGamePackage = eventPkg
            lastEnabledGameActiveAtMs = nowElapsed
        }
        return eventPkg
    }

    private fun collectCandidateRootNodes(monitoredPackage: String): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }

        runCatching {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val winRoot = window.root ?: continue
                val winPkg = winRoot.packageName?.toString()
                val alreadyAdded = roots.any { it == winRoot }
                if (!alreadyAdded &&
                    (winPkg == monitoredPackage ||
                        winPkg == AdDetectionRules.ADMOB_AD_ACTIVITY_PACKAGE ||
                        (winPkg != null && !AdDetectionRules.isSystemPackage(winPkg)))
                ) {
                    roots.add(winRoot)
                } else {
                    safeRecycle(winRoot)
                }
                if (roots.size >= 3) break
            }
        }
        return roots
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

    private class CandidateMatch(
        val targetType: AdTargetType,
        val bounds: BoundsRect,
        val priority: Int,
        var nodeCopy: AccessibilityNodeInfo?
    ) {
        fun recycleNode(service: SkipFlowAccessibilityService) {
            nodeCopy?.let { service.safeRecycle(it) }
            nodeCopy = null
        }
    }

    private data class CornerIconCandidate(
        val bounds: BoundsRect,
        val hasPositiveHint: Boolean,
        val isClickable: Boolean,
        val nodeCopy: AccessibilityNodeInfo
    )

    private data class TreeScanResult(
        val bestCandidate: CandidateMatch?,
        val hasYouTubeAdIndicator: Boolean
    )

    private fun scanWindowTree(
        root: AccessibilityNodeInfo,
        monitoredPackage: String,
        initialAdActivityHint: Boolean
    ): TreeScanResult {
        val (screenW, screenH, density) = getScreenMetrics()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        var visitedNodes = 0
        var hasYouTubeAd = false
        var isGameAdScreen = initialAdActivityHint
        var bestCandidate: CandidateMatch? = null
        val cornerIconCandidates = mutableListOf<CornerIconCandidate>()
        val tempRect = Rect()
        val isYouTube = monitoredPackage == AdDetectionRules.YOUTUBE_PACKAGE

        while (queue.isNotEmpty() && visitedNodes < MAX_SCAN_NODES) {
            val (node, depth) = queue.removeFirst()
            visitedNodes++

            val isRoot = node === root
            try {
                val viewId = node.viewIdResourceName
                val text = node.text
                val desc = node.contentDescription
                val visible = node.isVisibleToUser

                if (isYouTube) {
                    if (!hasYouTubeAd && visible && AdDetectionRules.isYouTubeAdIndicator(viewId, text, desc)) {
                        hasYouTubeAd = true
                    }
                } else if (!isGameAdScreen && visible) {
                    if (AdDetectionRules.isGameAdTextMarker(viewId, text, desc)) {
                        isGameAdScreen = true
                    }
                }

                if (visible) {
                    val clickableSelfOrParent = node.isClickable || isAncestorClickable(node)
                    val targetType = AdDetectionRules.matchCandidate(
                        packageName = monitoredPackage,
                        viewIdResourceName = viewId,
                        text = text,
                        contentDescription = desc,
                        isClickableOrHasClickableParent = clickableSelfOrParent
                    )

                    if (targetType != null) {
                        val isCountdownContainer = hasCountdownDescendant(node)
                        if (!isCountdownContainer) {
                            val targetBounds = resolveEffectiveBounds(node, screenW, screenH, tempRect)
                            if (SafeZoneEvaluator.isWithinSafeZone(targetBounds, screenW, screenH, targetType)) {
                                val priority = priorityFor(targetType)
                                if (bestCandidate == null || priority < bestCandidate.priority) {
                                    bestCandidate?.recycleNode(this)
                                    bestCandidate = CandidateMatch(
                                        targetType = targetType,
                                        bounds = targetBounds,
                                        priority = priority,
                                        nodeCopy = obtainNodeCopy(node)
                                    )
                                }
                            }
                        }
                    } else if (!isYouTube && isCornerIconCandidateClass(node)) {
                        node.getBoundsInScreen(tempRect)
                        val iconBounds = BoundsRect(tempRect.left, tempRect.top, tempRect.right, tempRect.bottom)
                        if (SafeZoneEvaluator.isCornerAdIconSize(iconBounds, screenW, screenH, density) &&
                            !AdDetectionRules.isExcludedCornerIcon(viewId, text, desc) &&
                            !hasCountdownDescendant(node)
                        ) {
                            cornerIconCandidates.add(
                                CornerIconCandidate(
                                    bounds = iconBounds,
                                    hasPositiveHint = AdDetectionRules.hasPositiveCornerIconHint(viewId, text, desc),
                                    isClickable = clickableSelfOrParent,
                                    nodeCopy = obtainNodeCopy(node)
                                )
                            )
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
            } finally {
                if (!isRoot) {
                    safeRecycle(node)
                }
            }
        }

        while (queue.isNotEmpty()) {
            val (remaining, _) = queue.removeFirst()
            if (remaining !== root) {
                safeRecycle(remaining)
            }
        }

        // If no explicit text/ID match was found and we are inside a game Ad window,
        // pick the top-corner >> / X icon candidate.
        if (bestCandidate == null && !isYouTube && isGameAdScreen && cornerIconCandidates.isNotEmpty()) {
            val chosen = cornerIconCandidates.sortedWith(
                compareByDescending<CornerIconCandidate> { it.hasPositiveHint }
                    .thenByDescending { it.isClickable }
                    .thenBy { it.bounds.top }
            ).first()

            bestCandidate = CandidateMatch(
                targetType = AdTargetType.GAME_AD_ICON,
                bounds = chosen.bounds,
                priority = priorityFor(AdTargetType.GAME_AD_ICON),
                nodeCopy = obtainNodeCopy(chosen.nodeCopy)
            )
        }

        cornerIconCandidates.forEach { safeRecycle(it.nodeCopy) }

        return TreeScanResult(
            bestCandidate = bestCandidate,
            hasYouTubeAdIndicator = hasYouTubeAd
        )
    }

    private fun isCornerIconCandidateClass(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        val cls = node.className?.toString()?.lowercase() ?: return false
        return cls.contains("imageview") ||
            cls.contains("imagebutton") ||
            cls.endsWith(".image") ||
            cls.endsWith(".view") ||
            cls.contains("button")
    }

    private fun hasCountdownDescendant(node: AccessibilityNodeInfo): Boolean {
        val count = node.childCount.coerceAtMost(6)
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            try {
                val childText = AdDetectionRules.normalizeText(child.text)
                val childDesc = AdDetectionRules.normalizeText(child.contentDescription)
                if ((childText != null && AdDetectionRules.isCountdownText(childText)) ||
                    (childDesc != null && AdDetectionRules.isCountdownText(childDesc))
                ) {
                    return true
                }
            } finally {
                safeRecycle(child)
            }
        }
        return false
    }

    private fun resolveEffectiveBounds(
        node: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
        tempRect: Rect
    ): BoundsRect {
        node.getBoundsInScreen(tempRect)
        val selfBounds = BoundsRect(tempRect.left, tempRect.top, tempRect.right, tempRect.bottom)
        if (node.isClickable || (selfBounds.width >= 36 && selfBounds.height >= 36)) {
            return selfBounds
        }

        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 3) {
            val nextParent = current.parent
            try {
                if (current.isClickable) {
                    val parentRect = Rect()
                    current.getBoundsInScreen(parentRect)
                    val parentBounds = BoundsRect(
                        parentRect.left,
                        parentRect.top,
                        parentRect.right,
                        parentRect.bottom
                    )
                    if (!parentBounds.isEmpty &&
                        parentBounds.width <= screenW * 0.75f &&
                        parentBounds.height <= screenH * 0.22f
                    ) {
                        if (nextParent != null) safeRecycle(nextParent)
                        return parentBounds
                    }
                }
            } finally {
                safeRecycle(current)
            }
            current = nextParent
            depth++
        }
        if (current != null) safeRecycle(current)
        return selfBounds
    }

    private fun isAncestorClickable(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 3) {
            val nextParent = current.parent
            try {
                if (current.isClickable) {
                    if (nextParent != null) safeRecycle(nextParent)
                    return true
                }
            } finally {
                safeRecycle(current)
            }
            current = nextParent
            depth++
        }
        if (current != null) safeRecycle(current)
        return false
    }

    private fun priorityFor(type: AdTargetType): Int = when (type) {
        AdTargetType.YOUTUBE_SKIP_AD -> 1
        AdTargetType.GAME_AD_SKIP -> 2
        AdTargetType.GAME_AD_CLOSE -> 3
        AdTargetType.GAME_AD_ICON -> 4
        AdTargetType.YOUTUBE_POPUP_DISMISS -> 5
    }

    private fun executeTargetAction(
        candidate: CandidateMatch,
        stageNumber: Int,
        countAsNewAdSkip: Boolean
    ) {
        // For YouTube & clickable text controls: climb to clickable parent and try ACTION_CLICK first,
        // then fall back immediately to dispatchGesture on center coordinates if failed or non-clickable.
        if (candidate.targetType != AdTargetType.GAME_AD_ICON) {
            val clickedViaAccessibility = tryClickSelfOrClickableAncestor(candidate.nodeCopy)
            if (clickedViaAccessibility) {
                onSkipGestureSucceeded(
                    targetType = candidate.targetType,
                    stageNumber = stageNumber,
                    countAsNewAdSkip = countAsNewAdSkip
                )
                return
            }
        }

        // Direct center gesture dispatch (for YouTube fallback & all game ad >> / X icons)
        dispatchCenterGesture(
            bounds = candidate.bounds,
            targetType = candidate.targetType,
            stageNumber = stageNumber,
            countAsNewAdSkip = countAsNewAdSkip
        )
    }

    private fun tryClickSelfOrClickableAncestor(startNode: AccessibilityNodeInfo?): Boolean {
        if (startNode == null) return false
        var current: AccessibilityNodeInfo? = obtainNodeCopy(startNode)
        var depth = 0
        while (current != null && depth < 4) {
            val nextParent = current.parent
            try {
                if (current.isClickable && current.isEnabled) {
                    val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        if (nextParent != null) safeRecycle(nextParent)
                        return true
                    }
                }
            } finally {
                safeRecycle(current)
            }
            current = nextParent
            depth++
        }
        if (current != null) safeRecycle(current)
        return false
    }

    private fun dispatchCenterGesture(
        bounds: BoundsRect,
        targetType: AdTargetType,
        stageNumber: Int,
        countAsNewAdSkip: Boolean
    ) {
        val centerX = bounds.centerX.toFloat()
        val centerY = bounds.centerY.toFloat()

        val path = Path().apply {
            moveTo(centerX, centerY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 55L))
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

    private fun getScreenMetrics(): Triple<Int, Int, Float> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), density)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.density.coerceAtLeast(1f))
        }
    }

    private fun cancelPendingVolumeRestore() {
        pendingVolumeRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingVolumeRestoreRunnable = null
    }

    @Suppress("DEPRECATION")
    private fun obtainNodeCopy(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            AccessibilityNodeInfo(node)
        } else {
            AccessibilityNodeInfo.obtain(node)
        }
    }

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { node.recycle() }
        }
    }

    companion object {
        private const val MAX_SCAN_NODES = 1200
        private const val MAX_SCAN_DEPTH = 40
        private const val CONTENT_SCAN_THROTTLE_MS = 220L
        private const val WINDOW_STATE_SCAN_GAP_MS = 80L
        private const val ADMOB_CONTEXT_WINDOW_MS = 30_000L
        private const val TOAST_COOLDOWN_MS = 2_400L

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _currentActivePackage = MutableStateFlow<String?>(null)
        val currentActivePackage: StateFlow<String?> = _currentActivePackage.asStateFlow()
    }
}
