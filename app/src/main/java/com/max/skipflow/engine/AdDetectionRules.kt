package com.max.skipflow.engine

/**
 * Dual-recognition rules (text labels + SDK View IDs) for YouTube, AdMob, Unity Ads,
 * AppLovin, and common mobile game interstitial ad controls.
 */
object AdDetectionRules {

    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val ADMOB_AD_ACTIVITY_PACKAGE = "com.google.android.gms"

    private val systemIdlePackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.oppo.launcher",
        "com.huawei.android.launcher"
    )

    private val imePackages = setOf(
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey",
        "com.baidu.input"
    )

    private val youtubeSkipViewIdSuffixes = setOf(
        "skip_ad_button",
        "skip_ad_button_text",
        "skip_ad_button_icon",
        "modern_skip_ad_button",
        "ad_skip_button"
    )

    private val youtubeDismissViewIdSuffixes = setOf(
        "mealbar_dismiss_button",
        "dismiss_button",
        "promo_dismiss_button"
    )

    private val gameAdSkipViewIdSuffixes = setOf(
        "skip_ad_button",
        "skip_button",
        "btn_skip",
        "ad_skip_button",
        "al_skip_button",
        "unity_ads_skip_button",
        "unityads_skip_button"
    )

    private val gameAdCloseViewIdSuffixes = setOf(
        "ia_tv_close_button",
        "ia_close_button",
        "ad_close_button",
        "close_ad_button",
        "close_button",
        "btn_close",
        "al_close_button",
        "applovin_native_ad_close_button",
        "mrec_close_button",
        "interstitial_close_button",
        "interstitial_control_button",
        "unity_ads_close_button",
        "unityads_close_button",
        "vungle_close",
        "mraid_close_indicator"
    )

    private val youtubeAdOverlayViewIdSuffixes = setOf(
        "ad_progress_text",
        "ad_countdown",
        "ad_overlay",
        "skip_ad_button",
        "modern_skip_ad_button",
        "player_learn_more_button",
        "visit_advertiser_button",
        "ad_info_button",
        "ad_badge"
    )

    private val skipExactTexts = setOf(
        "skip ad",
        "skip ads",
        "skip",
        "skip video",
        "تخطي الإعلان",
        "تخطي الاعلان",
        "تخطي الإعلانات",
        "تخطي الاعلانات",
        "تخطي"
    )

    private val gameCloseExactTexts = setOf(
        "إغلاق",
        "اغلاق",
        "إغلاق الإعلان",
        "اغلاق الاعلان",
        "close",
        "close ad",
        "dismiss",
        "×",
        "✕",
        "✖",
        "x"
    )

    private val youtubePopupDismissTexts = setOf(
        "لا شكراً",
        "لا شكرا",
        "لا، شكراً",
        "لا، شكرا",
        "ليس الآن",
        "ليس الان",
        "no thanks",
        "no, thanks",
        "not now",
        "dismiss"
    )

    private val countdownRegex = Regex(
        pattern = """(?i).*(\b(in|after|خلال|بعد)\s*\d+|\d+\s*(s|sec|secs|ث|ثانية|ثوان|ثواني)\b|^\d+$).*"""
    )

    private val youtubeAdTextMarkers = setOf(
        "ad ·",
        "إعلان ·",
        "اعلان ·",
        "skip ad",
        "skip ads",
        "تخطي الإعلان",
        "تخطي الاعلان",
        "visit advertiser",
        "زيارة موقع المعلن",
        "why this ad"
    )

    fun isSystemPackage(packageName: String): Boolean {
        if (packageName in systemIdlePackages) return true
        return packageName.startsWith("com.android.internal") ||
            packageName.startsWith("com.qualcomm.") ||
            packageName.startsWith("com.mediatek.")
    }

    fun isInputMethodPackage(packageName: String): Boolean {
        return packageName in imePackages || packageName.contains("inputmethod") || packageName.contains("keyboard")
    }

    fun extractViewIdSuffix(viewIdResourceName: String?): String? {
        if (viewIdResourceName.isNullOrBlank()) return null
        val slashOrColon = viewIdResourceName.indexOf(":id/")
        val raw = if (slashOrColon >= 0) {
            viewIdResourceName.substring(slashOrColon + 4)
        } else {
            viewIdResourceName.substringAfterLast('/')
        }
        return raw.trim().lowercase().takeIf { it.isNotEmpty() }
    }

    fun normalizeText(raw: CharSequence?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.toString()
            .replace('\u00A0', ' ')
            .replace('\u200F', ' ')
            .replace('\u200E', ' ')
            .trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase()
        return cleaned.takeIf { it.isNotEmpty() && it.length <= 48 }
    }

    fun isCountdownText(normalizedText: String): Boolean {
        return countdownRegex.matches(normalizedText)
    }

    fun isYouTubeAdIndicator(
        viewIdResourceName: String?,
        text: CharSequence?,
        contentDescription: CharSequence?
    ): Boolean {
        val idSuffix = extractViewIdSuffix(viewIdResourceName)
        if (idSuffix != null && idSuffix in youtubeAdOverlayViewIdSuffixes) {
            return true
        }
        val normText = normalizeText(text)
        val normDesc = normalizeText(contentDescription)
        return matchesAdMarker(normText) || matchesAdMarker(normDesc)
    }

    private fun matchesAdMarker(normalized: String?): Boolean {
        if (normalized == null) return false
        if (normalized in skipExactTexts) return true
        return youtubeAdTextMarkers.any { marker -> normalized.contains(marker) }
    }

    fun matchCandidate(
        packageName: String,
        viewIdResourceName: String?,
        text: CharSequence?,
        contentDescription: CharSequence?,
        isClickableOrHasClickableParent: Boolean
    ): AdTargetType? {
        val idSuffix = extractViewIdSuffix(viewIdResourceName)
        val normText = normalizeText(text)
        val normDesc = normalizeText(contentDescription)

        if (normText != null && isCountdownText(normText)) return null
        if (normDesc != null && isCountdownText(normDesc)) return null

        return if (packageName == YOUTUBE_PACKAGE) {
            matchYouTubeCandidate(
                idSuffix = idSuffix,
                normText = normText,
                normDesc = normDesc,
                isClickable = isClickableOrHasClickableParent
            )
        } else {
            matchGameOrGeneralAdCandidate(
                idSuffix = idSuffix,
                normText = normText,
                normDesc = normDesc,
                isClickable = isClickableOrHasClickableParent
            )
        }
    }

    private fun matchYouTubeCandidate(
        idSuffix: String?,
        normText: String?,
        normDesc: String?,
        isClickable: Boolean
    ): AdTargetType? {
        if (idSuffix != null && idSuffix in youtubeSkipViewIdSuffixes) {
            return AdTargetType.YOUTUBE_SKIP_AD
        }

        if (isExactSkipLabel(normText) || isExactSkipLabel(normDesc)) {
            return AdTargetType.YOUTUBE_SKIP_AD
        }

        val isDismissText = normText in youtubePopupDismissTexts || normDesc in youtubePopupDismissTexts
        if (idSuffix != null && idSuffix in youtubeDismissViewIdSuffixes && (isDismissText || isClickable)) {
            if (idSuffix == "mealbar_dismiss_button" || isDismissText) {
                return AdTargetType.YOUTUBE_POPUP_DISMISS
            }
        }

        if (isClickable && isDismissText) {
            return AdTargetType.YOUTUBE_POPUP_DISMISS
        }

        return null
    }

    private fun matchGameOrGeneralAdCandidate(
        idSuffix: String?,
        normText: String?,
        normDesc: String?,
        isClickable: Boolean
    ): AdTargetType? {
        if (idSuffix != null) {
            if (idSuffix in gameAdSkipViewIdSuffixes) {
                return AdTargetType.GAME_AD_SKIP
            }
            if (idSuffix in gameAdCloseViewIdSuffixes ||
                idSuffix.startsWith("al_") && (idSuffix.contains("close") || idSuffix.contains("skip")) ||
                idSuffix.startsWith("unity") && (idSuffix.contains("close") || idSuffix.contains("skip"))
            ) {
                return if (idSuffix.contains("skip")) AdTargetType.GAME_AD_SKIP else AdTargetType.GAME_AD_CLOSE
            }
        }

        if (isExactSkipLabel(normText) || isExactSkipLabel(normDesc)) {
            return AdTargetType.GAME_AD_SKIP
        }

        if (!isClickable) {
            return null
        }

        val isCloseLabel = (normText != null && normText in gameCloseExactTexts) ||
            (normDesc != null && normDesc in gameCloseExactTexts)
        if (isCloseLabel) {
            return AdTargetType.GAME_AD_CLOSE
        }

        return null
    }

    private fun isExactSkipLabel(normalized: String?): Boolean {
        if (normalized == null) return false
        if (normalized in skipExactTexts) return true
        return normalized.startsWith("skip ad") || normalized.startsWith("تخطي الإعلان") || normalized.startsWith("تخطي الاعلان")
    }
}
