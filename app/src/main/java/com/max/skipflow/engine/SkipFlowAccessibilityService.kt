package com.max.skipflow.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class SkipFlowAccessibilityService : AccessibilityService() {

    private lateinit var repository: SkipFlowRepository
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private lateinit var volumeController: YouTubeVolumeController

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val multiStageStateMachine = MultiStageAdStateMachine()
    private val playStoreRedirectGuard = PlayStoreRedirectGuard()

    private var pollingJob: Job? = null
    private var activeMonitoredPackage: String? = null
    private var currentActivityPackage: String? = null
    private var currentActivityClassName: String? = null
    private var lastScanElapsedMs: Long = 0L
    private var lastToastShownAtMs: Long = 0L
    private var pendingVolumeRestoreRunnable: Runnable? = null
    private var isScreenReceiverRegistered: Boolean = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> stopCountdownPolling()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    val pkg = activeMonitoredPackage
                    if (pkg != null && repository.settings.value.engineEnabled) {
                        ensureCountdownPollingActive()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = SkipFlowApplication.from(this).container.skipFlowRepository
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        volumeController = YouTubeVolumeController(AndroidMediaVolumeBackend(audioManager))
        registerScreenStateReceiver()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentSettings = repository.settings.value
        if (!currentSettings.engineEnabled) {
            stopCountdownPolling()
            activeMonitoredPackage = null
            _currentActivePackage.value = null
            volumeController.forceRestore()
            return
        }

        val eventPkg = event?.packageName?.toString() ?: return
        if (AdDetectionRules.isInputMethodPackage(eventPkg)) {
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        if (isWindowChange) {
            updateCurrentActivityTracking(eventPkg, event.className)

            // Anti-Redirect Bounce-Back: return user once if an ad click redirected straight to Play Store
            if (playStoreRedirectGuard.shouldBounceBack(
                    enteredPackage = eventPkg,
                    bounceBackEnabled = currentSettings.bounceBackFromPlayStore,
                    nowMs = nowElapsed
                )
            ) {
                stopCountdownPolling()
                activeMonitoredPackage = null
                _currentActivePackage.value = null
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (currentSettings.toastOnSkip) {
                    showDebouncedToast("تم الرجوع تلقائياً من المتجر", nowElapsed)
                }
                return
            }

            if (AdDetectionRules.isLauncherPackage(eventPkg)) {
                playStoreRedirectGuard.clearOnLauncherOrUserExit()
            }
        }

        // Strict Exclusion List: never scan or click inside Play Store, GMS, Chrome, Social apps, System, Launchers, or SkipFlow Max
        if (eventPkg == packageName || AdDetectionRules.isStrictlyExcludedPackage(eventPkg)) {
            activeMonitoredPackage = null
            _currentActivePackage.value = null
            stopCountdownPolling()
            if (eventPkg != AdDetectionRules.YOUTUBE_PACKAGE) {
                volumeController.forceRestore()
            }
            return
        }

        val disabledPackages = repository.disabledPackages.value
        if (eventPkg in disabledPackages) {
            activeMonitoredPackage = null
            _currentActivePackage.value = null
            stopCountdownPolling()
            if (eventPkg != AdDetectionRules.YOUTUBE_PACKAGE) {
                volumeController.forceRestore()
            }
            return
        }

        activeMonitoredPackage = eventPkg
        _currentActivePackage.value = eventPkg

        // Pause automatically during phone/VoIP calls
        if (currentSettings.pauseDuringCalls && CallStateMonitor.isCallActive(audioManager)) {
            stopCountdownPolling()
            volumeController.forceRestore()
            return
        }

        // Start lightweight 800ms polling while inside an active monitored app/game
        ensureCountdownPollingActive()

        val minInterval = if (isWindowChange) WINDOW_STATE_SCAN_GAP_MS else CONTENT_SCAN_THROTTLE_MS
        if (nowElapsed - lastScanElapsedMs < minInterval) {
            return
        }

        performMultiWindowScan(
            monitoredPackage = eventPkg,
            nowElapsed = nowElapsed
        )
    }

    override fun onInterrupt() {
        stopCountdownPolling()
        cancelPendingVolumeRestore()
        volumeController.forceRestore()
        _isServiceConnected.value = false
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopCountdownPolling()
        cancelPendingVolumeRestore()
        volumeController.forceRestore()
        _isServiceConnected.value = false
        activeMonitoredPackage = null
        _currentActivePackage.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopCountdownPolling()
        cancelPendingVolumeRestore()
        volumeController.forceRestore()
        unregisterScreenStateReceiver()
        serviceScope.cancel()
        _isServiceConnected.value = false
        activeMonitoredPackage = null
        _currentActivePackage.value = null
        super.onDestroy()
    }

    private fun updateCurrentActivityTracking(eventPkg: String, rawClassName: CharSequence?) {
        val cls = rawClassName?.toString()?.trim()
        if (eventPkg != currentActivityPackage) {
            currentActivityPackage = eventPkg
            currentActivityClassName = cls
            return
        }
        if (cls.isNullOrBlank()) return

        // Avoid replacing a real AdActivity name with transient Dialog/Widget classes inside the same package
        val isGenericUiComponent = cls.startsWith("android.widget.") ||
            cls.startsWith("android.view.") ||
            cls == "android.app.Dialog" ||
            cls.contains("PopupWindow") ||
            cls.contains("AlertDialog")
        if (!isGenericUiComponent || AdDetectionRules.isRealAdActivity(cls)) {
            currentActivityClassName = cls
        }
    }

    private fun isCurrentActivityRealAdScreen(packageName: String): Boolean {
        if (packageName == AdDetectionRules.YOUTUBE_PACKAGE) return false
        if (AdDetectionRules.isStrictlyExcludedPackage(packageName)) return false
        if (currentActivityPackage != packageName) return false
        return AdDetectionRules.isRealAdActivity(currentActivityClassName)
    }

    private fun ensureCountdownPollingActive() {
        if (!powerManager.isInteractive) {
            stopCountdownPolling()
            return
        }
        if (pollingJob?.isActive == true) return

        pollingJob = serviceScope.launch {
            while (isActive) {
                delay(COUNTDOWN_POLLING_INTERVAL_MS)
                val settings = repository.settings.value
                if (!settings.engineEnabled || !powerManager.isInteractive) {
                    stopCountdownPolling()
                    break
                }
                if (settings.pauseDuringCalls && CallStateMonitor.isCallActive(audioManager)) {
                    continue
                }

                val nowElapsed = SystemClock.elapsedRealtime()
                val liveMonitoredPkg = resolveForegroundMonitoredPackage()
                if (liveMonitoredPkg == null) {
                    activeMonitoredPackage = null
                    _currentActivePackage.value = null
                    volumeController.forceRestore()
                    stopCountdownPolling()
                    break
                }

                activeMonitoredPackage = liveMonitoredPkg
                _currentActivePackage.value = liveMonitoredPkg

                if (nowElapsed - lastScanElapsedMs >= POLLING_MIN_SCAN_GAP_MS) {
                    performMultiWindowScan(
                        monitoredPackage = liveMonitoredPkg,
                        nowElapsed = nowElapsed
                    )
                }
            }
        }
    }

    private fun stopCountdownPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun resolveForegroundMonitoredPackage(): String? {
        val disabledPackages = repository.disabledPackages.value
        val activeRoot = rootInActiveWindow
        val activePkg = try {
            activeRoot?.packageName?.toString()
        } finally {
            if (activeRoot != null) safeRecycle(activeRoot)
        }

        if (!activePkg.isNullOrBlank()) {
            if (activePkg == packageName ||
                AdDetectionRules.isStrictlyExcludedPackage(activePkg) ||
                activePkg in disabledPackages
            ) {
                return null
            }
            if (!AdDetectionRules.isInputMethodPackage(activePkg)) {
                return activePkg
            }
        }

        return activeMonitoredPackage?.takeIf {
            it != packageName &&
                !AdDetectionRules.isStrictlyExcludedPackage(it) &&
                it !in disabledPackages
        }
    }

    private fun performMultiWindowScan(
        monitoredPackage: String,
        nowElapsed: Long
    ) {
        if (AdDetectionRules.isStrictlyExcludedPackage(monitoredPackage)) return

        lastScanElapsedMs = nowElapsed
        val currentSettings = repository.settings.value
        val candidateWindows = collectAllApplicationWindowRoots(
            monitoredPackage = monitoredPackage,
            disabledPackages = repository.disabledPackages.value
        )
        if (candidateWindows.isEmpty()) return

        var hasYouTubeAd = false
        var bestCandidate: CandidateMatch? = null

        try {
            val realAdActivityActive = isCurrentActivityRealAdScreen(monitoredPackage)

            for (root in candidateWindows) {
                val rootPkg = root.packageName?.toString() ?: monitoredPackage
                if (AdDetectionRules.isStrictlyExcludedPackage(rootPkg)) continue

                val result = scanWindowTree(
                    root = root,
                    monitoredPackage = rootPkg,
                    isRealAdActivity = realAdActivityActive || isCurrentActivityRealAdScreen(rootPkg)
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
                playStoreRedirectGuard.recordGameOrAdSkipAttempt(monitoredPackage, nowElapsed)
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

    /**
     * Collects non-excluded TYPE_APPLICATION windows from service.windows (plus rootInActiveWindow)
     * so game ad overlays displayed in separate windows are detected without ever scanning excluded apps.
     */
    private fun collectAllApplicationWindowRoots(
        monitoredPackage: String,
        disabledPackages: Set<String>
    ): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()

        runCatching {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val winRoot = window.root ?: continue
                val winPkg = winRoot.packageName?.toString()
                val allowed = (winPkg == null || winPkg == monitoredPackage ||
                    (!AdDetectionRules.isStrictlyExcludedPackage(winPkg) &&
                        !AdDetectionRules.isInputMethodPackage(winPkg) &&
                        winPkg !in disabledPackages))
                if (allowed && roots.none { it == winRoot }) {
                    roots.add(winRoot)
                } else {
                    safeRecycle(winRoot)
                }
            }
        }

        rootInActiveWindow?.let { activeRoot ->
            val activePkg = activeRoot.packageName?.toString()
            val allowed = activePkg == null ||
                (!AdDetectionRules.isStrictlyExcludedPackage(activePkg) && activePkg !in disabledPackages)
            if (allowed && roots.none { it == activeRoot }) {
                roots.add(activeRoot)
            } else {
                safeRecycle(activeRoot)
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
        isRealAdActivity: Boolean
    ): TreeScanResult {
        val (screenW, screenH, density) = getScreenMetrics()
        val queue = ArrayDeque<Triple<AccessibilityNodeInfo, Int, Boolean>>()
        val rootIsWebView = root.className?.toString()?.contains("WebView", ignoreCase = true) == true
        queue.add(Triple(root, 0, rootIsWebView))

        var visitedNodes = 0
        var hasYouTubeAd = false
        var bestCandidate: CandidateMatch? = null
        val cornerIconCandidates = mutableListOf<CornerIconCandidate>()
        val tempRect = Rect()
        val isYouTube = monitoredPackage == AdDetectionRules.YOUTUBE_PACKAGE

        while (queue.isNotEmpty() && visitedNodes < MAX_SCAN_NODES) {
            val (node, depth, insideWebView) = queue.removeFirst()
            visitedNodes++

            val isRoot = node === root
            try {
                val viewId = node.viewIdResourceName
                val text = node.text
                val desc = node.contentDescription
                val clsName = node.className?.toString()
                val isWebViewNode = insideWebView || (clsName?.contains("WebView", ignoreCase = true) == true)
                val visible = node.isVisibleToUser || isWebViewNode

                if (isYouTube && !hasYouTubeAd && visible) {
                    if (AdDetectionRules.isYouTubeAdIndicator(viewId, text, desc)) {
                        hasYouTubeAd = true
                    }
                }

                if (visible) {
                    val clickableSelfOrParent = node.isClickable || isAncestorClickable(node)
                    val targetType = AdDetectionRules.matchCandidate(
                        packageName = monitoredPackage,
                        viewIdResourceName = viewId,
                        text = text,
                        contentDescription = desc,
                        isClickableOrHasClickableParent = clickableSelfOrParent,
                        isAdActivityContext = isRealAdActivity
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
                    } else if (!isYouTube && isRealAdActivity && isCornerIconCandidateClass(node, isWebViewNode)) {
                        // Corner Detector ONLY runs inside real Ad Activities
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
                        queue.add(Triple(child, depth + 1, isWebViewNode))
                    }
                }
            } finally {
                if (!isRoot) {
                    safeRecycle(node)
                }
            }
        }

        while (queue.isNotEmpty()) {
            val (remaining, _, _) = queue.removeFirst()
            if (remaining !== root) {
                safeRecycle(remaining)
            }
        }

        // Strict AdActivity-only corner icon selection
        if (bestCandidate == null && !isYouTube && isRealAdActivity && cornerIconCandidates.isNotEmpty()) {
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

    private fun isCornerIconCandidateClass(node: AccessibilityNodeInfo, isWebViewNode: Boolean): Boolean {
        if (node.isClickable) return true
        val cls = node.className?.toString()?.lowercase() ?: return isWebViewNode
        return cls.contains("imageview") ||
            cls.contains("imagebutton") ||
            cls.endsWith(".image") ||
            cls.endsWith(".view") ||
            cls.contains("button") ||
            (isWebViewNode && !cls.contains("webview"))
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
        AdTargetType.GAME_AD_CONTINUE -> 4
        AdTargetType.GAME_AD_ICON -> 5
        AdTargetType.YOUTUBE_POPUP_DISMISS -> 6
    }

    private fun executeTargetAction(
        candidate: CandidateMatch,
        stageNumber: Int,
        countAsNewAdSkip: Boolean
    ) {
        if (candidate.targetType == AdTargetType.YOUTUBE_SKIP_AD ||
            candidate.targetType == AdTargetType.YOUTUBE_POPUP_DISMISS
        ) {
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
            val message = when {
                targetType == AdTargetType.YOUTUBE_POPUP_DISMISS -> "تم إغلاق نافذة يوتيوب المزعجة"
                stageNumber > 1 -> "تم إغلاق المرحلة $stageNumber من الإعلان"
                else -> "تم تخطي الإعلان تلقائياً"
            }
            showDebouncedToast(message, now)
        }
    }

    private fun showDebouncedToast(message: String, nowElapsed: Long) {
        if (nowElapsed - lastToastShownAtMs >= TOAST_COOLDOWN_MS) {
            lastToastShownAtMs = nowElapsed
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
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

    private fun registerScreenStateReceiver() {
        if (isScreenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
            isScreenReceiverRegistered = true
        }
    }

    private fun unregisterScreenStateReceiver() {
        if (!isScreenReceiverRegistered) return
        runCatching { unregisterReceiver(screenStateReceiver) }
        isScreenReceiverRegistered = false
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
        private const val CONTENT_SCAN_THROTTLE_MS = 200L
        private const val WINDOW_STATE_SCAN_GAP_MS = 70L
        private const val COUNTDOWN_POLLING_INTERVAL_MS = 800L
        private const val POLLING_MIN_SCAN_GAP_MS = 650L
        private const val TOAST_COOLDOWN_MS = 2_400L

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _currentActivePackage = MutableStateFlow<String?>(null)
        val currentActivePackage: StateFlow<String?> = _currentActivePackage.asStateFlow()
    }
}
