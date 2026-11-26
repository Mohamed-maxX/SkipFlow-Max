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

    fun recordGameOrAdSkipAttempt(
        packageName: String,
        nowMs: Long,
        activityName: String? = null,
        bounds: BoundsRect? = null,
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        viewId: String? = null
    ) {
        if (packageName == AdDetectionRules.YOUTUBE_PACKAGE ||
            AdDetectionRules.isStrictlyExcludedPackage(packageName)
        ) {
            return
        }
        lastAttemptAtMs = nowMs
        lastAttemptPackage = packageName
        bouncedForCurrentAttempt = false
        lastAttemptInfo = if (bounds != null && screenWidth > 0 && screenHeight > 0) {
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

        val withinSkipAttempt = lastAttemptPackage != null &&
            lastAttemptAtMs > 0L &&
            !bouncedForCurrentAttempt &&
            (nowMs - lastAttemptAtMs) in 0..redirectWindowMs

        if (isPlayStore) {
            if (withinSkipAttempt) {
                bouncedForCurrentAttempt = true
                lastChooserBounceAtMs = nowMs
                lastRedirectedTrapInfo = lastAttemptInfo
                return true
            }
            return false
        }

        // App Chooser / Store Picker ("android", ResolverActivity, ChooserActivity, "متجر Play", "GetApps", "حدد متجر تطبيقات")
        if (nowMs - lastChooserBounceAtMs in 0 until CHOOSER_BOUNCE_COOLDOWN_MS) {
            return false
        }

        if (withinSkipAttempt || isWithinActiveAdWindow(nowMs)) {
            bouncedForCurrentAttempt = true
            lastChooserBounceAtMs = nowMs
            lastRedirectedTrapInfo = if (withinSkipAttempt) lastAttemptInfo else null
            return true
        }
        return false
    }

    companion object {
        const val REDIRECT_WINDOW_MS = 3_000L
        const val ACTIVE_AD_WINDOW_MS = 5_000L
        const val CHOOSER_BOUNCE_COOLDOWN_MS = 1_800L
    }
}
