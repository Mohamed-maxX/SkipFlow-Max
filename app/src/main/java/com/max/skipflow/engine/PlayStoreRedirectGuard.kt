package com.max.skipflow.engine

data class TappedSkipCandidateInfo(
    val packageName: String,
    val activityName: String?,
    val normCenterX: Float,
    val normCenterY: Float,
    val viewId: String?
)

/**
 * Guards against accidental ad-click or auto-redirect escapes into Google Play Store (`com.android.vending`)
 * or Android's app chooser / store picker (`android` / `ResolverActivity` / `ChooserActivity` /
 * `"متجر Play"` / `"GetApps"` / `"حدد متجر تطبيقات"`), triggering a single `GLOBAL_ACTION_BACK`
 * and returning metadata of the offending tap so Fake-X Learning can blacklist that trap for 24h.
 */
class PlayStoreRedirectGuard(
    private val redirectWindowMs: Long = REDIRECT_WINDOW_MS,
    private val activeAdWindowMs: Long = ACTIVE_AD_WINDOW_MS
) {
    private var lastAttemptAtMs: Long = 0L
    private var lastAttemptPackage: String? = null
    private var lastAttemptInfo: TappedSkipCandidateInfo? = null
    private var bouncedForCurrentAttempt: Boolean = false
    private var lastRedirectedTrapInfo: TappedSkipCandidateInfo? = null

    private var lastAdActiveAtMs: Long = 0L
    private var lastAdPackage: String? = null
    private var lastChooserBounceAtMs: Long = 0L
    private val recentBounceTimestamps = mutableListOf<Long>()

    fun recordGameOrAdSkipAttempt(
        packageName: String,
        nowMs: Long,
        activityName: String? = null,
        bounds: BoundsRect? = null,
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        viewId: String? = null,
        isGooglePlayAdvance: Boolean = false
    ) {
        if (packageName == AdDetectionRules.YOUTUBE_PACKAGE ||
            AdDetectionRules.isStrictlyExcludedPackage(packageName)
        ) {
            return
        }
        lastAttemptAtMs = nowMs
        lastAttemptPackage = packageName
        bouncedForCurrentAttempt = false
        lastAttemptInfo = if (!isGooglePlayAdvance && bounds != null && screenWidth > 0 && screenHeight > 0) {
            TappedSkipCandidateInfo(
                packageName = packageName,
                activityName = activityName,
                normCenterX = bounds.centerX.toFloat() / screenWidth.toFloat(),
                normCenterY = bounds.centerY.toFloat() / screenHeight.toFloat(),
                viewId = viewId
            )
        } else {
            null
        }
    }

    fun consumeRedirectedTapInfo(): TappedSkipCandidateInfo? {
        val info = lastRedirectedTrapInfo
        lastRedirectedTrapInfo = null
        return info
    }

    fun markActiveAdContext(packageName: String, nowMs: Long) {
        if (packageName == AdDetectionRules.YOUTUBE_PACKAGE ||
            AdDetectionRules.isStrictlyExcludedPackage(packageName)
        ) {
            return
        }
        lastAdActiveAtMs = nowMs
        lastAdPackage = packageName
    }

    fun isWithinActiveAdWindow(nowMs: Long): Boolean {
        if (lastAdPackage == null || lastAdActiveAtMs <= 0L) return false
        return (nowMs - lastAdActiveAtMs) in 0..activeAdWindowMs
    }

    fun clearOnLauncherOrUserExit() {
        lastAttemptAtMs = 0L
        lastAttemptPackage = null
        lastAttemptInfo = null
        lastRedirectedTrapInfo = null
        bouncedForCurrentAttempt = false
        lastAdActiveAtMs = 0L
        lastAdPackage = null
    }

    fun shouldBounceBack(
        enteredPackage: String,
        bounceBackEnabled: Boolean,
        nowMs: Long,
        enteredClassName: CharSequence? = null,
        hasStoreChooserContent: Boolean = false
    ): Boolean {
        if (!bounceBackEnabled) return false

        val isPlayStore = enteredPackage == AdDetectionRules.PLAY_STORE_PACKAGE
        val isAppOrStoreChooser = AdDetectionRules.isAppChooserPackageOrActivity(enteredPackage, enteredClassName) ||
            hasStoreChooserContent

        if (!isPlayStore && !isAppOrStoreChooser) return false

        // Prevent duplicate back-presses on rapid consecutive events for the same transition.
        if (lastChooserBounceAtMs > 0L && (nowMs - lastChooserBounceAtMs) in 0 until CHOOSER_BOUNCE_COOLDOWN_MS) {
            return false
        }

        // Safety guard: allow at most 2 automatic bounce-backs within 10 seconds so a user
        // intentionally opening Play Store is never trapped.
        recentBounceTimestamps.removeAll { it < nowMs - BOUNCE_RATE_WINDOW_MS || it > nowMs }
        if (recentBounceTimestamps.size >= MAX_BOUNCES_PER_WINDOW) {
            return false
        }

        val withinSkipAttempt = lastAttemptPackage != null &&
            lastAttemptAtMs > 0L &&
            !bouncedForCurrentAttempt &&
            (nowMs - lastAttemptAtMs) in 0..redirectWindowMs

        val activeAdRecently = isWithinActiveAdWindow(nowMs)
        if (!withinSkipAttempt && !activeAdRecently) {
            return false
        }

        if (withinSkipAttempt) {
            bouncedForCurrentAttempt = true
        }
        lastChooserBounceAtMs = nowMs
        recentBounceTimestamps.add(nowMs)
        lastRedirectedTrapInfo = if (withinSkipAttempt) lastAttemptInfo else null
        return true
    }

    companion object {
        const val REDIRECT_WINDOW_MS = 3_000L
        const val ACTIVE_AD_WINDOW_MS = 10_000L
        const val CHOOSER_BOUNCE_COOLDOWN_MS = 1_500L
        const val BOUNCE_RATE_WINDOW_MS = 10_000L
        const val MAX_BOUNCES_PER_WINDOW = 2
    }
}
