package com.max.skipflow.engine

/**
 * Guards against accidental ad-click redirects into Google Play Store (`com.android.vending`)
 * within 3 seconds after a skip/close attempt in a game or ad Activity, triggering a single
 * GLOBAL_ACTION_BACK to return the user seamlessly.
 */
class PlayStoreRedirectGuard(
    private val redirectWindowMs: Long = REDIRECT_WINDOW_MS
) {
    private var lastAttemptAtMs: Long = 0L
    private var lastAttemptPackage: String? = null
    private var bouncedForCurrentAttempt: Boolean = false

    fun recordGameOrAdSkipAttempt(packageName: String, nowMs: Long) {
        if (packageName == AdDetectionRules.YOUTUBE_PACKAGE ||
            AdDetectionRules.isStrictlyExcludedPackage(packageName)
        ) {
            return
        }
        lastAttemptAtMs = nowMs
        lastAttemptPackage = packageName
        bouncedForCurrentAttempt = false
    }

    fun clearOnLauncherOrUserExit() {
        lastAttemptAtMs = 0L
        lastAttemptPackage = null
        bouncedForCurrentAttempt = false
    }

    fun shouldBounceBack(
        enteredPackage: String,
        bounceBackEnabled: Boolean,
        nowMs: Long
    ): Boolean {
        if (!bounceBackEnabled) return false
        if (enteredPackage != AdDetectionRules.PLAY_STORE_PACKAGE) return false
        if (bouncedForCurrentAttempt) return false
        if (lastAttemptPackage == null || lastAttemptAtMs <= 0L) return false

        val elapsed = nowMs - lastAttemptAtMs
        if (elapsed in 0..redirectWindowMs) {
            bouncedForCurrentAttempt = true
            return true
        }
        return false
    }

    companion object {
        const val REDIRECT_WINDOW_MS = 3_000L
    }
}
