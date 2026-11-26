package com.max.skipflow.engine

data class AnnoyanceNodeObservation(
    val index: Int,
    val text: String?,
    val contentDescription: String?,
    val bounds: BoundsRect,
    val isClickable: Boolean
)

data class AnnoyanceMatch(
    val nodeIndex: Int,
    val bounds: BoundsRect,
    val kind: String
)

/**
 * Pure JVM-testable matcher for optional "Annoyance Killer":
 * - "Rate Us / قيّم تطبيقنا" dialogs -> clicks "Later / لاحقاً / No thanks / لا شكراً"
 * - Browser cookie consent banners -> clicks "Accept / Accept all / موافق / أوافق" ONLY when cookie text exists.
 */
object AnnoyanceKillerDetector {

    private val rateDialogContextMarkers = listOf(
        "rate us",
        "rate this app",
        "rate our app",
        "enjoying our app",
        "enjoying the app",
        "love this app",
        "قيّم تطبيقنا",
        "قيم تطبيقنا",
        "تقييم التطبيق",
        "قيّمنا",
        "قيمنا",
        "رأيك يهمنا"
    )

    private val rateDismissButtonLabels = setOf(
        "later",
        "maybe later",
        "remind me later",
        "not now",
        "no thanks",
        "no, thanks",
        "لاحقاً",
        "لاحقا",
        "ليس الآن",
        "ليس الان",
        "لا شكراً",
        "لا شكرا",
        "ذكرني لاحقاً",
        "ذكرني لاحقا"
    )

    private val cookieBannerContextMarkers = listOf(
        "cookie",
        "cookies",
        "ملفات تعريف الارتباط",
        "الكوكيز",
        "كوكيز"
    )

    private val cookieAcceptButtonLabels = setOf(
        "accept",
        "accept all",
        "accept cookies",
        "allow all",
        "i agree",
        "got it",
        "موافق",
        "أوافق",
        "اوافق",
        "قبول",
        "قبول الكل",
        "حسناً",
        "حسنا"
    )

    private val browserPackages = setOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.brave.browser"
    )

    fun isBrowserPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return lower in browserPackages || lower.contains("browser") || lower.contains("chrome")
    }

    fun evaluateTree(
        packageName: String,
        observations: List<AnnoyanceNodeObservation>,
        screenWidth: Int,
        screenHeight: Int
    ): AnnoyanceMatch? {
        if (observations.isEmpty() || screenWidth <= 0 || screenHeight <= 0) return null

        val allNormalized = observations.mapNotNull { obs ->
            val t = AdDetectionRules.normalizeText(obs.text, maxLength = 120)
            val d = AdDetectionRules.normalizeText(obs.contentDescription, maxLength = 120)
            listOfNotNull(t, d).joinToString(" ").takeIf { it.isNotEmpty() }
        }

        val hasRateDialogContext = allNormalized.any { text ->
            rateDialogContextMarkers.any { marker -> text.contains(marker) }
        }
        if (hasRateDialogContext) {
            val dismissTarget = findMatchingButton(observations, rateDismissButtonLabels, screenWidth, screenHeight)
            if (dismissTarget != null) {
                return AnnoyanceMatch(dismissTarget.index, dismissTarget.bounds, kind = "RateUs")
            }
        }

        if (isBrowserPackage(packageName)) {
            val hasCookieContext = allNormalized.any { text ->
                cookieBannerContextMarkers.any { marker -> text.contains(marker) }
            }
            if (hasCookieContext) {
                val acceptTarget = findMatchingButton(observations, cookieAcceptButtonLabels, screenWidth, screenHeight)
                if (acceptTarget != null) {
                    return AnnoyanceMatch(acceptTarget.index, acceptTarget.bounds, kind = "CookieBanner")
                }
            }
        }

        return null
    }

    private fun findMatchingButton(
        observations: List<AnnoyanceNodeObservation>,
        allowedLabels: Set<String>,
        screenWidth: Int,
        screenHeight: Int
    ): AnnoyanceNodeObservation? {
        return observations.firstOrNull { obs ->
            if (!obs.isClickable || obs.bounds.isEmpty) return@firstOrNull false
            if (obs.bounds.width > screenWidth * 0.85f || obs.bounds.height > screenHeight * 0.25f) {
                return@firstOrNull false
            }
            val normText = AdDetectionRules.normalizeText(obs.text, maxLength = 40)
            val normDesc = AdDetectionRules.normalizeText(obs.contentDescription, maxLength = 40)
            (normText != null && normText in allowedLabels) || (normDesc != null && normDesc in allowedLabels)
        }
    }

    const val ANNOYANCE_COOLDOWN_MS = 3_500L
}
