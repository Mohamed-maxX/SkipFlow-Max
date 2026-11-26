package com.max.skipflow.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
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
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.max.skipflow.SkipFlowApplication
import com.max.skipflow.data.SkipFlowRepository
import com.max.skipflow.vision.OpenCvAdIconDetector
import com.max.skipflow.vision.TesseractOcrEngine
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
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SkipFlowAccessibilityService : AccessibilityService() {

    private lateinit var repository: SkipFlowRepository
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private lateinit var volumeController: YouTubeVolumeController
    private lateinit var openCvAdIconDetector: OpenCvAdIconDetector
    private lateinit var tesseractOcrEngine: TesseractOcrEngine
    private lateinit var debugOverlayManager: DebugOverlayManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val visionExecutor = Executors.newSingleThreadExecutor()
    private val isVisionScanInFlight = AtomicBoolean(false)
    private val visionGenerationId = AtomicLong(0L)

    private val multiStageStateMachine = MultiStageAdStateMachine()
    private val playStoreRedirectGuard = PlayStoreRedirectGuard()

    private var pollingJob: Job? = null
    private var settingsObserverJob: Job? = null
    private var activeMonitoredPackage: String? = null
    private var currentActivityPackage: String? = null
    private var currentActivityClassName: String? = null
    @Volatile
    private var isAdScreenActive: Boolean = false
    private var lastScanElapsedMs: Long = 0L
    private var lastVisionScanStartedMs: Long = 0L
    private var lastToastShownAtMs: Long = 0L
    private var lastScreenshotStatus: String = "-"
    private var lastDebugDetail: String = "جاهز"
    private var pendingVolumeRestoreRunnable: Runnable? = null
    private var isScreenReceiverRegistered: Boolean = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    stopCountdownPolling()
                    debugOverlayManager.hide()
                }
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
        val container = SkipFlowApplication.from(this).container
        repository = container.skipFlowRepository
        openCvAdIconDetector = container.openCvAdIconDetector
        tesseractOcrEngine = container.tesseractOcrEngine
        debugOverlayManager = DebugOverlayManager(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        volumeController = YouTubeVolumeController(AndroidMediaVolumeBackend(audioManager))
        registerScreenStateReceiver()

        settingsObserverJob = serviceScope.launch {
            repository.settings.collect { settings ->
                if (!settings.liveDebugOverlayEnabled || !settings.engineEnabled) {
                    debugOverlayManager.hide()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceConnected.value = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        visionGenerationId.incrementAndGet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentSettings = repository.settings.value
        if (!currentSettings.engineEnabled) {
            stopCountdownPolling()
            activeMonitoredPackage = null
            _currentActivePackage.value = null
            volumeController.forceRestore()
            debugOverlayManager.hide()
            return
        }

        val eventPkg = event?.packageName?.toString() ?: return
        val nowElapsed = SystemClock.elapsedRealtime()
        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isTransientOverlay = AdDetectionRules.isTransientOverlayPackage(eventPkg, packageName)

        // Never overwrite currentActivityPackage / currentActivityClassName from transient overlays
        // (`com.android.systemui`, screen recorders, keyboards, or SkipFlow Max's overlay).
        if (isWindowChange && !isTransientOverlay) {
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
                emitStatusUpdate(
                    pkg = eventPkg,
                    isAdScreen = false,
                    screenshotStatus = "-",
                    detail = "رجوع تلقائي من المتجر"
                )
                return
            }

            if (AdDetectionRules.isLauncherPackage(eventPkg)) {
                playStoreRedirectGuard.clearOnLauncherOrUserExit()
            }
        }

        if (isTransientOverlay && !isWindowChange) {
            return
        }

        // Resolve the true foreground application via `service.windows` (`TYPE_APPLICATION` only),
        // ignoring `TYPE_SYSTEM`, `TYPE_ACCESSIBILITY_OVERLAY`, and `TYPE_INPUT_METHOD`.
        val foregroundPkg = resolveActiveApplicationPackage(
            fallbackPackage = if (!isTransientOverlay) eventPkg else activeMonitoredPackage
        ) ?: return

        if (isTransientOverlay &&
            currentActivityPackage != null &&
            foregroundPkg != currentActivityPackage
        ) {
            clearStaleAdState(newPackage = foregroundPkg)
        }

        // Strict Exclusion List: never scan or click inside Play Store, GMS, Chrome, Social apps, System, Launchers, or SkipFlow Max
        if (foregroundPkg == packageName || AdDetectionRules.isStrictlyExcludedPackage(foregroundPkg)) {
            clearStaleAdState(newPackage = null)
            activeMonitoredPackage = null
            _currentActivePackage.value = null
            stopCountdownPolling()
            if (foregroundPkg != AdDetectionRules.YOUTUBE_PACKAGE) {
                volumeController.forceRestore()
            }
            pushDebugOverlay(
                pkg = foregroundPkg,
                isAdScreen = false,
                screenshotStatus = "-",
                detail = "مستثنى"
            )
            return
        }

        val disabledPackages = repository.disabledPackages.value
        if (foregroundPkg in disabledPackages) {
            clearStaleAdState(newPackage = null)
            activeMonitoredPackage = null
            _currentActivePackage.value = null
            stopCountdownPolling()
            if (foregroundPkg != AdDetectionRules.YOUTUBE_PACKAGE) {
                volumeController.forceRestore()
            }
            pushDebugOverlay(
                pkg = foregroundPkg,
                isAdScreen = false,
                screenshotStatus = "-",
                detail = "معطّل من القائمة"
            )
            return
        }

        activeMonitoredPackage = foregroundPkg
        _currentActivePackage.value = foregroundPkg

        // Pause automatically during phone/VoIP calls
        if (currentSettings.pauseDuringCalls && CallStateMonitor.isCallActive(audioManager)) {
            stopCountdownPolling()
            volumeController.forceRestore()
            pushDebugOverlay(
                pkg = foregroundPkg,
                isAdScreen = false,
                screenshotStatus = "-",
                detail = "متوقف (مكالمة)"
            )
            return
        }

        ensureCountdownPollingActive()

        val minInterval = if (isWindowChange) WINDOW_STATE_SCAN_GAP_MS else CONTENT_SCAN_THROTTLE_MS
        if (nowElapsed - lastScanElapsedMs < minInterval) {
            return
        }

        performMultiWindowScan(
            monitoredPackage = foregroundPkg,
            nowElapsed = nowElapsed
        )
    }

    override fun onInterrupt() {
        stopCountdownPolling()
        cancelPendingVolumeRestore()
        volumeController.forceRestore()
        debugOverlayManager.hide()
        _isServiceConnected.value = false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopCountdownPolling()
        cancelPendingVolumeRestore()
        volumeController.forceRestore()
        debugOverlayManager.hide()
        _isServiceConnected.value = false
        activeMonitoredPackage = null
        _currentActivePackage.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopCountdownPolling()
        settingsObserverJob?.cancel()
        cancelPendingVolumeRestore()
        volumeController.forceRestore()
        debugOverlayManager.hide()
        unregisterScreenStateReceiver()
        openCvAdIconDetector.release()
        tesseractOcrEngine.release()
        visionExecutor.shutdownNow()
        serviceScope.cancel()
        _isServiceConnected.value = false
        activeMonitoredPackage = null
        _currentActivePackage.value = null
        super.onDestroy()
    }

    private fun clearStaleAdState(newPackage: String? = currentActivityPackage) {
        isAdScreenActive = false
        if (newPackage != currentActivityPackage) {
            currentActivityPackage = newPackage
            currentActivityClassName = null
        } else if (AdDetectionRules.isRealAdActivity(currentActivityClassName)) {
            currentActivityClassName = null
        }
        lastScreenshotStatus = "-"
        visionGenerationId.incrementAndGet()
        multiStageStateMachine.reset()
    }

    private fun updateCurrentActivityTracking(eventPkg: String, rawClassName: CharSequence?) {
        if (AdDetectionRules.isTransientOverlayPackage(eventPkg, packageName)) {
            return
        }

        val cls = rawClassName?.toString()?.trim()
        val isNewAdActivity = AdDetectionRules.isRealAdActivity(cls)
        if (eventPkg != currentActivityPackage) {
            currentActivityPackage = eventPkg
            currentActivityClassName = cls
            isAdScreenActive = isNewAdActivity
            lastScreenshotStatus = "-"
            lastDebugDetail = "انتقال"
            visionGenerationId.incrementAndGet()
            multiStageStateMachine.reset()
            if (isNewAdActivity) {
                lastScanElapsedMs = 0L
                if (!isVisionScanInFlight.get()) {
                    lastVisionScanStartedMs = 0L
                }
            }
            return
        }
        if (cls.isNullOrBlank()) return

        val isGenericUiComponent = cls.startsWith("android.widget.") ||
            cls.startsWith("android.view.") ||
            cls == "android.app.Dialog" ||
            cls.contains("PopupWindow") ||
            cls.contains("AlertDialog")
        if (!isGenericUiComponent || isNewAdActivity) {
            val wasRealAd = AdDetectionRules.isRealAdActivity(currentActivityClassName)
            currentActivityClassName = cls
            isAdScreenActive = isNewAdActivity
            if (wasRealAd != isNewAdActivity) {
                visionGenerationId.incrementAndGet()
                lastScreenshotStatus = "-"
                if (isNewAdActivity) {
                    lastScanElapsedMs = 0L
                    if (!isVisionScanInFlight.get()) {
                        lastVisionScanStartedMs = 0L
                    }
                } else {
                    multiStageStateMachine.reset()
                    lastDebugDetail = "شاشة عادية"
                }
            }
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
            clearStaleAdState(newPackage = null)
            stopCountdownPolling()
            return
        }
        if (pollingJob?.isActive == true) return

        pollingJob = serviceScope.launch {
            while (isActive) {
                delay(COUNTDOWN_POLLING_INTERVAL_MS)
                val settings = repository.settings.value
                if (!settings.engineEnabled || !powerManager.isInteractive) {
                    clearStaleAdState(newPackage = null)
                    stopCountdownPolling()
                    break
                }
                if (settings.pauseDuringCalls && CallStateMonitor.isCallActive(audioManager)) {
                    continue
                }

                // Every polling cycle: verify the true active `TYPE_APPLICATION` window in `service.windows`
                val nowElapsed = SystemClock.elapsedRealtime()
                val liveActiveAppPkg = resolveActiveApplicationPackage(fallbackPackage = null)
                val disabledPackages = repository.disabledPackages.value

                if (liveActiveAppPkg == null ||
                    liveActiveAppPkg == packageName ||
                    AdDetectionRules.isStrictlyExcludedPackage(liveActiveAppPkg) ||
                    AdDetectionRules.isTransientOverlayPackage(liveActiveAppPkg, packageName) ||
                    liveActiveAppPkg in disabledPackages
                ) {
                    clearStaleAdState(newPackage = null)
                    activeMonitoredPackage = null
                    _currentActivePackage.value = null
                    volumeController.forceRestore()
                    if (liveActiveAppPkg != null) {
                        pushDebugOverlay(
                            pkg = liveActiveAppPkg,
                            isAdScreen = false,
                            screenshotStatus = "-",
                            detail = "مستثنى"
                        )
                    }
                    stopCountdownPolling()
                    break
                }

                if (liveActiveAppPkg != currentActivityPackage) {
                    clearStaleAdState(newPackage = liveActiveAppPkg)
                    pushDebugOverlay(
                        pkg = liveActiveAppPkg,
                        isAdScreen = false,
                        screenshotStatus = "-",
                        detail = "شاشة عادية"
                    )
                }

                activeMonitoredPackage = liveActiveAppPkg
                _currentActivePackage.value = liveActiveAppPkg

                if (nowElapsed - lastScanElapsedMs >= POLLING_MIN_SCAN_GAP_MS) {
                    performMultiWindowScan(
                        monitoredPackage = liveActiveAppPkg,
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

    private fun resolveActiveApplicationPackage(fallbackPackage: String? = null): String? {
        val (screenW, screenH, _) = getCurrentDisplayMetrics()
        val tempRect = Rect()
        val snapshots = mutableListOf<WindowSnapshot>()

        runCatching {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val winRoot = window.root
                val winPkg = try {
                    winRoot?.packageName?.toString()
                } finally {
                    if (winRoot != null) safeRecycle(winRoot)
                }
                window.getBoundsInScreen(tempRect)
                snapshots.add(
                    WindowSnapshot(
                        packageName = winPkg,
                        windowType = WindowFocusResolver.TYPE_APPLICATION,
                        isActive = window.isActive,
                        isFocused = window.isFocused,
                        layer = window.layer,
                        bounds = BoundsRect(tempRect.left, tempRect.top, tempRect.right, tempRect.bottom)
                    )
                )
            }
        }

        val resolvedFromWindows = WindowFocusResolver.selectActiveApplicationPackage(
            windows = snapshots,
            screenWidth = screenW,
            screenHeight = screenH,
            selfPackage = packageName
        )
        if (!resolvedFromWindows.isNullOrBlank()) {
            return resolvedFromWindows
        }

        return fallbackPackage?.takeUnless {
            AdDetectionRules.isTransientOverlayPackage(it, packageName)
        }
    }

    private fun performMultiWindowScan(
        monitoredPackage: String,
        nowElapsed: Long
    ) {
        if (AdDetectionRules.isStrictlyExcludedPackage(monitoredPackage)) return

        // Re-verify active `TYPE_APPLICATION` window right before scanning or tapping
        val verifiedForegroundPkg = resolveActiveApplicationPackage(fallbackPackage = null)
        if (verifiedForegroundPkg != null && verifiedForegroundPkg != monitoredPackage) {
            clearStaleAdState(newPackage = verifiedForegroundPkg)
            pushDebugOverlay(
                pkg = verifiedForegroundPkg,
                isAdScreen = false,
                screenshotStatus = "-",
                detail = "تغيير النافذة"
            )
            return
        }

        lastScanElapsedMs = nowElapsed
        val currentSettings = repository.settings.value
        val candidateWindows = collectAllApplicationWindowRoots(
            monitoredPackage = monitoredPackage,
            disabledPackages = repository.disabledPackages.value
        )
        if (candidateWindows.isEmpty()) return

        var hasYouTubeAd = false
        var hasExplicitAdTreeText = false
        var hasUnityPlayerSurface = false
        var hasRealWebView = false
        var totalVisitedNodes = 0
        var bestCandidate: CandidateMatch? = null
        val excludedCornerRects = mutableListOf<BoundsRect>()
        var realAdActivityActive = isCurrentActivityRealAdScreen(monitoredPackage)
        val preferTopLeftCorner = realAdActivityActive &&
            AdDetectionRules.isTtRewardExpressActivity(currentActivityClassName)
        val (screenW, screenH, _) = getCurrentDisplayMetrics()

        try {
            for (root in candidateWindows) {
                val rootPkg = root.packageName?.toString() ?: monitoredPackage
                if (AdDetectionRules.isStrictlyExcludedPackage(rootPkg) ||
                    AdDetectionRules.isTransientOverlayPackage(rootPkg, packageName)
                ) {
                    continue
                }

                val result = scanWindowTree(
                    root = root,
                    monitoredPackage = rootPkg,
                    isRealAdActivity = realAdActivityActive || isCurrentActivityRealAdScreen(rootPkg),
                    preferTopLeftCorner = preferTopLeftCorner
                )
                totalVisitedNodes += result.visitedNodes
                if (result.hasExplicitAdTextOrId) {
                    hasExplicitAdTreeText = true
                }
                if (result.hasUnityPlayerSurface) {
                    hasUnityPlayerSurface = true
                }
                if (result.hasRealWebView) {
                    hasRealWebView = true
                }
                if (result.hasYouTubeAdIndicator) {
                    hasYouTubeAd = true
                }
                excludedCornerRects.addAll(result.excludedRects)
                val match = result.bestCandidate
                if (match != null) {
                    if (bestCandidate == null ||
                        isHigherPriorityMatch(match, bestCandidate, preferTopLeftCorner, screenW, screenH)
                    ) {
                        bestCandidate?.recycleNode(this)
                        bestCandidate = match
                    } else {
                        match.recycleNode(this)
                    }
                }
            }

            // If we returned to UnityPlayer normal gameplay without any ad text in tree, clear stale AdActivity state immediately
            if (realAdActivityActive && hasUnityPlayerSurface && !hasExplicitAdTreeText) {
                clearStaleAdState(newPackage = monitoredPackage)
                realAdActivityActive = false
                bestCandidate?.recycleNode(this)
                bestCandidate = null
                pushDebugOverlay(
                    pkg = monitoredPackage,
                    isAdScreen = false,
                    screenshotStatus = "-",
                    detail = "شاشة لعبة عادية"
                )
                return
            }

            handleYouTubeAutoMute(
                monitoredPackage = monitoredPackage,
                hasYouTubeAd = hasYouTubeAd,
                autoMuteEnabled = currentSettings.autoMuteYouTubeAds,
                nowElapsed = nowElapsed
            )

            val confirmedAdByTreeOrActivity = realAdActivityActive || hasExplicitAdTreeText
            isAdScreenActive = confirmedAdByTreeOrActivity ||
                (monitoredPackage == AdDetectionRules.YOUTUBE_PACKAGE && hasYouTubeAd)

            val explicitWinner = bestCandidate
            if (explicitWinner != null) {
                val isYouTubeAction = monitoredPackage == AdDetectionRules.YOUTUBE_PACKAGE
                if (!isYouTubeAction && !confirmedAdByTreeOrActivity) {
                    isAdScreenActive = false
                    pushDebugOverlay(
                        pkg = monitoredPackage,
                        isAdScreen = false,
                        screenshotStatus = "-",
                        detail = "ليست شاشة إعلان"
                    )
                    return
                }

                val decision = multiStageStateMachine.evaluateCandidate(
                    packageName = monitoredPackage,
                    bounds = explicitWinner.bounds,
                    targetType = explicitWinner.targetType,
                    nowMs = nowElapsed
                )

                if (decision is StageDecision.ExecuteTap) {
                    playStoreRedirectGuard.recordGameOrAdSkipAttempt(monitoredPackage, nowElapsed)
                    pushDebugOverlay(
                        pkg = monitoredPackage,
                        isAdScreen = true,
                        screenshotStatus = lastScreenshotStatus,
                        detail = "A11y: ${explicitWinner.targetType.name}"
                    )
                    executeTargetAction(
                        candidate = explicitWinner,
                        stageNumber = decision.stageNumber,
                        countAsNewAdSkip = decision.countAsNewAdSkip
                    )
                } else if (decision is StageDecision.Ignore) {
                    pushDebugOverlay(
                        pkg = monitoredPackage,
                        isAdScreen = true,
                        screenshotStatus = lastScreenshotStatus,
                        detail = "تجاهل: ${decision.reason}"
                    )
                }
                return
            }

            // Strict Gate: Only run OpenCV directly on Confirmed AdActivity or explicit A11y ad text;
            // on unconfirmed WebView/sparse containers (never on UnityPlayer gameplay), allow OCR text check only.
            if (shouldAttemptVisionFallback(
                    monitoredPackage = monitoredPackage,
                    isRealAdActivity = realAdActivityActive,
                    hasExplicitAdTreeText = hasExplicitAdTreeText,
                    hasUnityPlayerSurface = hasUnityPlayerSurface,
                    hasRealWebView = hasRealWebView,
                    visitedNodes = totalVisitedNodes,
                    nowElapsed = nowElapsed
                )
            ) {
                captureAndAnalyzeScreen(
                    monitoredPackage = monitoredPackage,
                    isRealAdActivity = realAdActivityActive,
                    hasExplicitAdTreeText = hasExplicitAdTreeText,
                    preferTopLeftCorner = preferTopLeftCorner,
                    scanGeneration = visionGenerationId.get(),
                    excludedRects = excludedCornerRects.toList()
                )
                return
            }

            if (!confirmedAdByTreeOrActivity && monitoredPackage != AdDetectionRules.YOUTUBE_PACKAGE) {
                isAdScreenActive = false
            }

            pushDebugOverlay(
                pkg = monitoredPackage,
                isAdScreen = isAdScreenActive,
                screenshotStatus = lastScreenshotStatus,
                detail = when {
                    monitoredPackage == AdDetectionRules.YOUTUBE_PACKAGE -> {
                        if (hasYouTubeAd) "إعلان يوتيوب (انتظار التخطي)" else "يوتيوب طبيعي"
                    }
                    !isAdScreenActive -> "ليست شاشة إعلان"
                    else -> lastDebugDetail
                }
            )
        } finally {
            bestCandidate?.recycleNode(this)
            candidateWindows.forEach { safeRecycle(it) }
        }
    }

    private fun shouldAttemptVisionFallback(
        monitoredPackage: String,
        isRealAdActivity: Boolean,
        hasExplicitAdTreeText: Boolean,
        hasUnityPlayerSurface: Boolean,
        hasRealWebView: Boolean,
        visitedNodes: Int,
        nowElapsed: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (monitoredPackage == AdDetectionRules.YOUTUBE_PACKAGE) return false
        if (AdDetectionRules.isStrictlyExcludedPackage(monitoredPackage)) return false
        if (monitoredPackage in repository.disabledPackages.value) return false
        if (!powerManager.isInteractive) return false
        if (isVisionScanInFlight.get()) return false
        if (multiStageStateMachine.remainingCooldownMs(nowElapsed) > 120L) return false

        val confirmedAd = isRealAdActivity || hasExplicitAdTreeText
        val cooldownMs = if (confirmedAd) VISION_FALLBACK_COOLDOWN_MS else UNCONFIRMED_OCR_COOLDOWN_MS
        if (nowElapsed - lastVisionScanStartedMs < cooldownMs) return false

        if (confirmedAd) return true

        // On non-AdActivity screens: never inspect UnityPlayer gameplay surfaces without WebView/explicit ad text;
        // check passive OCR only if an embedded WebView or sparse non-Unity window might be hiding an ad banner.
        return hasRealWebView || (!hasUnityPlayerSurface && visitedNodes <= SPARSE_TREE_NODE_THRESHOLD)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureAndAnalyzeScreen(
        monitoredPackage: String,
        isRealAdActivity: Boolean,
        hasExplicitAdTreeText: Boolean,
        preferTopLeftCorner: Boolean,
        scanGeneration: Long,
        excludedRects: List<BoundsRect>
    ) {
        if (!isVisionScanInFlight.compareAndSet(false, true)) return
        lastVisionScanStartedMs = SystemClock.elapsedRealtime()

        runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                visionExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        var hardwareBitmap: Bitmap? = null
                        var softwareBitmap: Bitmap? = null
                        try {
                            // Android 16 / wide-gamut HardwareBuffer -> immediate ARGB_8888 Software Bitmap copy
                            hardwareBitmap = runCatching {
                                Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            }.getOrNull() ?: Bitmap.wrapHardwareBuffer(hardwareBuffer, null)

                            softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (_: Throwable) {
                            softwareBitmap = null
                        } finally {
                            hardwareBitmap?.recycle()
                            runCatching { hardwareBuffer.close() }
                        }

                        if (softwareBitmap == null) {
                            lastScreenshotStatus = "Fail(copy)"
                            pushDebugOverlay(
                                pkg = monitoredPackage,
                                isAdScreen = isRealAdActivity || hasExplicitAdTreeText,
                                screenshotStatus = lastScreenshotStatus,
                                detail = "فشل نسخ HardwareBitmap"
                            )
                            isVisionScanInFlight.set(false)
                            return
                        }

                        lastScreenshotStatus = "OK"
                        serviceScope.launch(Dispatchers.Default) {
                            try {
                                analyzeSoftwareScreenshot(
                                    softwareBitmap = softwareBitmap,
                                    expectedPackage = monitoredPackage,
                                    isRealAdActivity = isRealAdActivity,
                                    hasExplicitAdTreeText = hasExplicitAdTreeText,
                                    preferTopLeftCorner = preferTopLeftCorner,
                                    scanGeneration = scanGeneration,
                                    excludedRects = excludedRects
                                )
                            } finally {
                                softwareBitmap.recycle()
                                isVisionScanInFlight.set(false)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        lastScreenshotStatus = "Fail($errorCode)"
                        pushDebugOverlay(
                            pkg = monitoredPackage,
                            isAdScreen = isRealAdActivity || hasExplicitAdTreeText,
                            screenshotStatus = lastScreenshotStatus,
                            detail = if (errorCode == 6) "شاشة محمية SECURE" else "خطأ لقطة ($errorCode)"
                        )
                        isVisionScanInFlight.set(false)
                    }
                }
            )
        }.onFailure {
            lastScreenshotStatus = "Fail(ex)"
            isVisionScanInFlight.set(false)
        }
    }

    private suspend fun analyzeSoftwareScreenshot(
        softwareBitmap: Bitmap,
        expectedPackage: String,
        isRealAdActivity: Boolean,
        hasExplicitAdTreeText: Boolean,
        preferTopLeftCorner: Boolean,
        scanGeneration: Long,
        excludedRects: List<BoundsRect>
    ) {
        if (!isStillValidForVisionTap(expectedPackage, scanGeneration)) return

        val (screenW, screenH, density) = getCurrentDisplayMetrics()
        val confirmedBeforeOcr = isRealAdActivity || hasExplicitAdTreeText
        var targetBounds: BoundsRect? = null
        var targetType: AdTargetType = AdTargetType.GAME_AD_ICON
        var detectedDetail = "ليست شاشة إعلان"
        var detectedAsAd = confirmedBeforeOcr
        var ocvMaxScore = 0.0

        // 1. Strict Gate: ONLY run OpenCV (`>= 0.78`) & Corner OCR upfront when screen is
        //    either a confirmed AdActivity OR actively displaying explicit ad text in Accessibility tree.
        if (confirmedBeforeOcr) {
            val ocvSummary = openCvAdIconDetector.detectCornerCloseIcon(
                softwareScreenshot = softwareBitmap,
                screenWidth = screenW,
                screenHeight = screenH,
                density = density,
                excludedRects = excludedRects,
                preferTopLeftCorner = preferTopLeftCorner
            )
            ocvMaxScore = ocvSummary.maxScore
            if (ocvSummary.detection != null) {
                targetBounds = ocvSummary.detection.boundsOnScreen
                targetType = AdTargetType.GAME_AD_ICON
                detectedDetail = String.format(
                    Locale.US,
                    "OCV %s (%.2f)",
                    ocvSummary.detection.shape.name,
                    ocvSummary.detection.confidenceScore
                )
            }

            if (targetBounds == null) {
                val cornerOcr = tesseractOcrEngine.recognizeCornerControls(
                    softwareScreenshot = softwareBitmap,
                    screenWidth = screenW,
                    screenHeight = screenH,
                    excludedRects = excludedRects
                )
                if (cornerOcr.actionTargetBounds != null) {
                    targetBounds = cornerOcr.actionTargetBounds
                    targetType = AdTargetType.GAME_AD_SKIP
                    detectedDetail = "CornerOCR: ${cornerOcr.matchedKeyword}"
                }
            }
        }

        // 2. Full-Screen Tesseract OCR (`ara+eng`):
        //    If not a confirmed AdActivity, ONLY verify explicit ad context ("إعلان", "اعرف المزيد", "Sponsored", "Install", "Learn More")
        //    before running OpenCV or tapping anything.
        if (targetBounds == null) {
            val ocrResult = tesseractOcrEngine.recognizeAdControls(
                softwareScreenshot = softwareBitmap,
                screenWidth = screenW,
                screenHeight = screenH,
                isRealAdActivity = confirmedBeforeOcr,
                excludedRects = excludedRects
            )
            if (ocrResult.hasAdContext) {
                detectedAsAd = true
            }

            if (ocrResult.hasAdContext && ocrResult.actionTargetBounds != null) {
                targetBounds = ocrResult.actionTargetBounds
                targetType = AdTargetType.GAME_AD_SKIP
                detectedDetail = "OCR: ${ocrResult.matchedKeyword}"
            } else if (ocrResult.hasAdContext && !confirmedBeforeOcr) {
                // Ad confirmed via visible OCR text -> now safe to run OpenCV (`>= 0.78`) & Corner OCR
                val ocvSummary = openCvAdIconDetector.detectCornerCloseIcon(
                    softwareScreenshot = softwareBitmap,
                    screenWidth = screenW,
                    screenHeight = screenH,
                    density = density,
                    excludedRects = excludedRects,
                    preferTopLeftCorner = preferTopLeftCorner
                )
                ocvMaxScore = maxOf(ocvMaxScore, ocvSummary.maxScore)
                if (ocvSummary.detection != null) {
                    targetBounds = ocvSummary.detection.boundsOnScreen
                    targetType = AdTargetType.GAME_AD_ICON
                    detectedDetail = String.format(
                        Locale.US,
                        "OCV %s (%.2f)",
                        ocvSummary.detection.shape.name,
                        ocvSummary.detection.confidenceScore
                    )
                } else {
                    val cornerOcr = tesseractOcrEngine.recognizeCornerControls(
                        softwareScreenshot = softwareBitmap,
                        screenWidth = screenW,
                        screenHeight = screenH,
                        excludedRects = excludedRects
                    )
                    if (cornerOcr.actionTargetBounds != null) {
                        targetBounds = cornerOcr.actionTargetBounds
                        targetType = AdTargetType.GAME_AD_SKIP
                        detectedDetail = "CornerOCR: ${cornerOcr.matchedKeyword}"
                    } else {
                        detectedDetail = String.format(Locale.US, "OCV=%.2f | بلا زر", ocvMaxScore)
                    }
                }
            } else if (detectedAsAd) {
                detectedDetail = String.format(Locale.US, "OCV=%.2f | OCR: بلا زر", ocvMaxScore)
            } else {
                detectedDetail = "ليست شاشة إعلان"
            }
        }

        // Strict Verification Gate: if neither confirmed AdActivity nor explicit ad text exists, NEVER tap anything.
        if (!detectedAsAd) {
            isAdScreenActive = false
            pushDebugOverlay(
                pkg = expectedPackage,
                isAdScreen = false,
                screenshotStatus = "OK",
                detail = "ليست شاشة إعلان"
            )
            return
        }

        val resolvedBounds = targetBounds
        if (resolvedBounds == null) {
            pushDebugOverlay(
                pkg = expectedPackage,
                isAdScreen = true,
                screenshotStatus = "OK",
                detail = detectedDetail
            )
            return
        }

        if (!isStillValidForVisionTap(expectedPackage, scanGeneration)) return

        withContext(Dispatchers.Main.immediate) {
            // Final verification right before dispatching tap (checks generation + live TYPE_APPLICATION window)
            if (!isStillValidForVisionTap(expectedPackage, scanGeneration)) return@withContext
            val liveAppPkg = resolveActiveApplicationPackage(fallbackPackage = null)
            if (liveAppPkg != expectedPackage) {
                clearStaleAdState(newPackage = liveAppPkg)
                return@withContext
            }

            val nowMs = SystemClock.elapsedRealtime()
            val decision = multiStageStateMachine.evaluateCandidate(
                packageName = expectedPackage,
                bounds = resolvedBounds,
                targetType = targetType,
                nowMs = nowMs
            )
            if (decision is StageDecision.ExecuteTap) {
                playStoreRedirectGuard.recordGameOrAdSkipAttempt(expectedPackage, nowMs)
                pushDebugOverlay(
                    pkg = expectedPackage,
                    isAdScreen = true,
                    screenshotStatus = "OK",
                    detail = "$detectedDetail -> ضغط"
                )
                dispatchCenterGesture(
                    bounds = resolvedBounds,
                    targetType = targetType,
                    stageNumber = decision.stageNumber,
                    countAsNewAdSkip = decision.countAsNewAdSkip
                )
            } else if (decision is StageDecision.Ignore) {
                pushDebugOverlay(
                    pkg = expectedPackage,
                    isAdScreen = true,
                    screenshotStatus = "OK",
                    detail = "$detectedDetail (${decision.reason})"
                )
            }
        }
    }

    private fun isStillValidForVisionTap(expectedPackage: String, scanGeneration: Long): Boolean {
        if (visionGenerationId.get() != scanGeneration) return false
        val settings = repository.settings.value
        if (!settings.engineEnabled) return false
        if (!powerManager.isInteractive) return false
        if (settings.pauseDuringCalls && CallStateMonitor.isCallActive(audioManager)) return false
        if (AdDetectionRules.isStrictlyExcludedPackage(expectedPackage) ||
            AdDetectionRules.isTransientOverlayPackage(expectedPackage, packageName)
        ) {
            return false
        }
        if (expectedPackage in repository.disabledPackages.value) return false
        return activeMonitoredPackage == expectedPackage
    }

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
                        !AdDetectionRules.isTransientOverlayPackage(winPkg, packageName) &&
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
            val windowType = runCatching { activeRoot.window?.type }.getOrNull()
            val isAppWindow = windowType == null || windowType == AccessibilityWindowInfo.TYPE_APPLICATION
            val allowed = isAppWindow && (activePkg == null ||
                (!AdDetectionRules.isStrictlyExcludedPackage(activePkg) &&
                    !AdDetectionRules.isTransientOverlayPackage(activePkg, packageName) &&
                    activePkg !in disabledPackages))
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
        val hasYouTubeAdIndicator: Boolean,
        val hasExplicitAdTextOrId: Boolean,
        val hasUnityPlayerSurface: Boolean,
        val hasRealWebView: Boolean,
        val visitedNodes: Int,
        val excludedRects: List<BoundsRect>
    )

    private fun scanWindowTree(
        root: AccessibilityNodeInfo,
        monitoredPackage: String,
        isRealAdActivity: Boolean,
        preferTopLeftCorner: Boolean
    ): TreeScanResult {
        val (screenW, screenH, density) = getCurrentDisplayMetrics()
        val queue = ArrayDeque<Triple<AccessibilityNodeInfo, Int, Boolean>>()
        val rootClass = root.className?.toString()
        val rootIsWebView = rootClass?.contains("WebView", ignoreCase = true) == true
        queue.add(Triple(root, 0, rootIsWebView))

        var visitedNodes = 0
        var hasYouTubeAd = false
        var hasExplicitAdTextOrId = false
        var hasUnityPlayerSurface = rootClass?.contains("UnityPlayer", ignoreCase = true) == true
        var hasRealWebView = rootIsWebView
        var explicitBestCandidate: CandidateMatch? = null
        var contextGatedBestCandidate: CandidateMatch? = null
        val cornerIconCandidates = mutableListOf<CornerIconCandidate>()
        val excludedRects = mutableListOf<BoundsRect>()
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
                if (!hasRealWebView && isWebViewNode) {
                    hasRealWebView = true
                }
                if (!hasUnityPlayerSurface && clsName?.contains("UnityPlayer", ignoreCase = true) == true) {
                    hasUnityPlayerSurface = true
                }
                val visible = node.isVisibleToUser || isWebViewNode

                if (isYouTube && !hasYouTubeAd && visible) {
                    if (AdDetectionRules.isYouTubeAdIndicator(viewId, text, desc)) {
                        hasYouTubeAd = true
                    }
                }

                if (visible) {
                    if (!isYouTube &&
                        (AdDetectionRules.hasExplicitAdContextText(text, desc) ||
                            AdDetectionRules.isExplicitAdSdkViewId(viewId))
                    ) {
                        hasExplicitAdTextOrId = true
                    }

                    if (!isYouTube && AdDetectionRules.hasExcludedCornerKeyword(viewId, text, desc)) {
                        node.getBoundsInScreen(tempRect)
                        val exRect = BoundsRect(tempRect.left, tempRect.top, tempRect.right, tempRect.bottom)
                        if (!exRect.isEmpty && exRect.width <= screenW * 0.4f && exRect.height <= screenH * 0.2f) {
                            excludedRects.add(exRect)
                        }
                    }

                    val clickableSelfOrParent = node.isClickable || isAncestorClickable(node)
                    val explicitTarget = AdDetectionRules.matchCandidate(
                        packageName = monitoredPackage,
                        viewIdResourceName = viewId,
                        text = text,
                        contentDescription = desc,
                        isClickableOrHasClickableParent = clickableSelfOrParent,
                        isAdActivityContext = false
                    )

                    if (explicitTarget != null) {
                        val isCountdownContainer = hasCountdownDescendant(node)
                        if (!isCountdownContainer) {
                            val targetBounds = resolveEffectiveBounds(node, screenW, screenH, tempRect)
                            if (SafeZoneEvaluator.isWithinSafeZone(targetBounds, screenW, screenH, explicitTarget)) {
                                val nextMatch = CandidateMatch(
                                    targetType = explicitTarget,
                                    bounds = targetBounds,
                                    priority = priorityFor(explicitTarget),
                                    nodeCopy = obtainNodeCopy(node)
                                )
                                if (explicitBestCandidate == null ||
                                    isHigherPriorityMatch(nextMatch, explicitBestCandidate, preferTopLeftCorner, screenW, screenH)
                                ) {
                                    explicitBestCandidate?.recycleNode(this)
                                    explicitBestCandidate = nextMatch
                                } else {
                                    nextMatch.recycleNode(this)
                                }
                            }
                        }
                    } else if (!isYouTube) {
                        val contextualTarget = AdDetectionRules.matchCandidate(
                            packageName = monitoredPackage,
                            viewIdResourceName = viewId,
                            text = text,
                            contentDescription = desc,
                            isClickableOrHasClickableParent = clickableSelfOrParent,
                            isAdActivityContext = true
                        )
                        if (contextualTarget != null) {
                            val isCountdownContainer = hasCountdownDescendant(node)
                            if (!isCountdownContainer) {
                                val targetBounds = resolveEffectiveBounds(node, screenW, screenH, tempRect)
                                if (SafeZoneEvaluator.isWithinSafeZone(targetBounds, screenW, screenH, contextualTarget)) {
                                    val nextMatch = CandidateMatch(
                                        targetType = contextualTarget,
                                        bounds = targetBounds,
                                        priority = priorityFor(contextualTarget),
                                        nodeCopy = obtainNodeCopy(node)
                                    )
                                    if (contextGatedBestCandidate == null ||
                                        isHigherPriorityMatch(nextMatch, contextGatedBestCandidate, preferTopLeftCorner, screenW, screenH)
                                    ) {
                                        contextGatedBestCandidate?.recycleNode(this)
                                        contextGatedBestCandidate = nextMatch
                                    } else {
                                        nextMatch.recycleNode(this)
                                    }
                                }
                            }
                        } else if (isCornerIconCandidateClass(node, isWebViewNode) &&
                            AdDetectionRules.hasPositiveCornerIconHint(viewId, text, desc)
                        ) {
                            node.getBoundsInScreen(tempRect)
                            val iconBounds = BoundsRect(tempRect.left, tempRect.top, tempRect.right, tempRect.bottom)
                            if (SafeZoneEvaluator.isCornerAdIconSize(iconBounds, screenW, screenH, density) &&
                                !AdDetectionRules.isExcludedCornerIcon(viewId, text, desc) &&
                                !hasCountdownDescendant(node)
                            ) {
                                cornerIconCandidates.add(
                                    CornerIconCandidate(
                                        bounds = iconBounds,
                                        hasPositiveHint = true,
                                        isClickable = clickableSelfOrParent,
                                        nodeCopy = obtainNodeCopy(node)
                                    )
                                )
                            }
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

        val adContextVerified = isRealAdActivity || hasExplicitAdTextOrId
        var chosenCandidate: CandidateMatch? = explicitBestCandidate
        if (chosenCandidate == null && adContextVerified) {
            chosenCandidate = contextGatedBestCandidate
        } else {
            contextGatedBestCandidate?.recycleNode(this)
        }

        if (chosenCandidate == null && !isYouTube && adContextVerified && cornerIconCandidates.isNotEmpty()) {
            val chosen = cornerIconCandidates.sortedWith(
                compareByDescending<CornerIconCandidate> {
                    preferTopLeftCorner && isTopLeftPriorityRegion(it.bounds, screenW, screenH)
                }
                    .thenByDescending { it.isClickable }
                    .thenBy { it.bounds.top }
            ).first()

            chosenCandidate = CandidateMatch(
                targetType = AdTargetType.GAME_AD_ICON,
                bounds = chosen.bounds,
                priority = priorityFor(AdTargetType.GAME_AD_ICON),
                nodeCopy = obtainNodeCopy(chosen.nodeCopy)
            )
        }

        cornerIconCandidates.forEach { safeRecycle(it.nodeCopy) }

        return TreeScanResult(
            bestCandidate = chosenCandidate,
            hasYouTubeAdIndicator = hasYouTubeAd,
            hasExplicitAdTextOrId = hasExplicitAdTextOrId,
            hasUnityPlayerSurface = hasUnityPlayerSurface,
            hasRealWebView = hasRealWebView,
            visitedNodes = visitedNodes,
            excludedRects = excludedRects
        )
    }

    private fun isTopLeftPriorityRegion(bounds: BoundsRect, screenW: Int, screenH: Int): Boolean {
        if (screenW <= 0 || screenH <= 0) return false
        val ratios = DisplayGeometry.cornerRatiosFor(screenW, screenH)
        return bounds.centerX <= screenW * ratios.widthRatio && bounds.centerY <= screenH * ratios.heightRatio
    }

    private fun isHigherPriorityMatch(
        candidate: CandidateMatch,
        currentBest: CandidateMatch,
        preferTopLeftCorner: Boolean,
        screenW: Int,
        screenH: Int
    ): Boolean {
        if (candidate.priority != currentBest.priority) {
            return candidate.priority < currentBest.priority
        }
        if (preferTopLeftCorner) {
            val candTopLeft = isTopLeftPriorityRegion(candidate.bounds, screenW, screenH)
            val bestTopLeft = isTopLeftPriorityRegion(currentBest.bounds, screenW, screenH)
            if (candTopLeft != bestTopLeft) {
                return candTopLeft
            }
        }
        return false
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
                    val maxParentHeightRatio = if (screenW > screenH) 0.35f else 0.22f
                    if (!parentBounds.isEmpty &&
                        parentBounds.width <= screenW * 0.75f &&
                        parentBounds.height <= screenH * maxParentHeightRatio
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
        val (currentW, currentH, _) = getCurrentDisplayMetrics()
        val (tapX, tapY) = DisplayGeometry.clampTapPoint(
            x = bounds.centerX.toFloat(),
            y = bounds.centerY.toFloat(),
            screenWidth = currentW,
            screenHeight = currentH
        )

        val path = Path().apply {
            moveTo(tapX, tapY)
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

        val actionSummary = when {
            targetType == AdTargetType.YOUTUBE_POPUP_DISMISS -> "تم إغلاق نافذة يوتيوب المزعجة"
            stageNumber > 1 -> "تم إغلاق المرحلة $stageNumber من الإعلان"
            else -> "تم تخطي الإعلان تلقائياً"
        }

        val overlayRunning = settings.liveDebugOverlayEnabled && debugOverlayManager.canDrawOverlay()
        if (overlayRunning) {
            pushDebugOverlay(
                pkg = activeMonitoredPackage,
                isAdScreen = true,
                screenshotStatus = lastScreenshotStatus,
                detail = actionSummary
            )
        } else if (settings.toastOnSkip) {
            showDebouncedToast(actionSummary, SystemClock.elapsedRealtime())
        }
    }

    private fun emitStatusUpdate(
        pkg: String?,
        isAdScreen: Boolean,
        screenshotStatus: String,
        detail: String
    ) {
        val settings = repository.settings.value
        val overlayRunning = settings.liveDebugOverlayEnabled && debugOverlayManager.canDrawOverlay()
        if (overlayRunning) {
            pushDebugOverlay(pkg, isAdScreen, screenshotStatus, detail)
        } else if (settings.toastOnSkip) {
            showDebouncedToast(detail, SystemClock.elapsedRealtime())
        }
    }

    private fun pushDebugOverlay(
        pkg: String?,
        isAdScreen: Boolean,
        screenshotStatus: String,
        detail: String
    ) {
        lastDebugDetail = detail
        val settings = repository.settings.value
        val activityLabel = if (pkg == currentActivityPackage) currentActivityClassName else null
        debugOverlayManager.updateStatus(
            enabled = settings.engineEnabled && settings.liveDebugOverlayEnabled,
            activityName = activityLabel,
            packageName = pkg,
            isAdScreen = isAdScreen,
            screenshotStatus = screenshotStatus,
            detail = detail
        )
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

    private fun findActiveApplicationWindowBounds(): BoundsRect? {
        val tempRect = Rect()
        var bestFocusedOrActive: BoundsRect? = null
        runCatching {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                window.getBoundsInScreen(tempRect)
                val rect = BoundsRect(tempRect.left, tempRect.top, tempRect.right, tempRect.bottom)
                if (rect.isEmpty) continue
                if (window.isActive || window.isFocused) {
                    return rect
                }
                if (bestFocusedOrActive == null ||
                    rect.width.toLong() * rect.height.toLong() >
                    bestFocusedOrActive!!.width.toLong() * bestFocusedOrActive!!.height.toLong()
                ) {
                    bestFocusedOrActive = rect
                }
            }
        }
        return bestFocusedOrActive
    }

    /**
     * Dynamically queries current display dimensions on every scan cycle without caching Portrait size.
     */
    private fun getCurrentDisplayMetrics(): Triple<Int, Int, Float> {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val metricsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && defaultDisplay != null) {
            runCatching { createDisplayContext(defaultDisplay) }.getOrDefault(this)
        } else {
            this
        }

        val windowManager = metricsContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = metricsContext.resources.displayMetrics.density.coerceAtLeast(1f)

        val (rawW, rawH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }

        val configIsLandscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
                metricsContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val (resolvedW, resolvedH) = DisplayGeometry.resolveDynamicDimensions(
            windowWidth = rawW,
            windowHeight = rawH,
            activeAppBounds = findActiveApplicationWindowBounds(),
            configIsLandscape = configIsLandscape
        )
        return Triple(resolvedW, resolvedH, density)
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
        private const val CONTENT_SCAN_THROTTLE_MS = 180L
        private const val WINDOW_STATE_SCAN_GAP_MS = 50L
        private const val COUNTDOWN_POLLING_INTERVAL_MS = 600L
        private const val POLLING_MIN_SCAN_GAP_MS = 450L
        private const val VISION_FALLBACK_COOLDOWN_MS = 900L
        private const val UNCONFIRMED_OCR_COOLDOWN_MS = 1_800L
        private const val SPARSE_TREE_NODE_THRESHOLD = 25
        private const val TOAST_COOLDOWN_MS = 2_400L

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _currentActivePackage = MutableStateFlow<String?>(null)
        val currentActivePackage: StateFlow<String?> = _currentActivePackage.asStateFlow()
    }
}
