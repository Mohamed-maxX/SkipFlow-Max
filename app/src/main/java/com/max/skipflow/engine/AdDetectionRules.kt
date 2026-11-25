package com.max.skipflow.engine

/**
 * Dual-recognition rules (text/contentDescription contains matching + SDK View IDs +
 * corner icon heuristics) for YouTube and mobile game ad networks (Pangle, TikTok Ads,
 * AdMob, Unity Ads, AppLovin, IronSource, Vungle, Mintegral).
 */
object AdDetectionRules {

    const val SELF_PACKAGE = "com.max.skipflow"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val ADMOB_AD_ACTIVITY_PACKAGE = "com.google.android.gms"

    private val systemIdlePackages = setOf(
        SELF_PACKAGE,
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.vending",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.oppo.launcher",
        "com.huawei.android.launcher",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.samsung.android.app.telephonyui",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.android.calculator2",
        "com.google.android.calculator",
        "com.miui.securitycenter",
        "com.coloros.safecenter"
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
        "skip_ad_button_container",
        "skip_ad_button_icon",
        "modern_skip_ad_text",
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
        "unityads_skip_button",
        "tt_top_skip",
        "tt_video_ad_close_layout",
        "tt_video_ad_close"
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
        "mraid_close_indicator",
        "tt_close_iv",
        "tt_titlebar_close",
        "mbridge_windvane_close",
        "mbridge_iv_close"
    )

    private val youtubeAdOverlayViewIdSuffixes = setOf(
        "ad_progress_text",
        "ad_countdown",
        "ad_overlay",
        "skip_ad_button",
        "skip_ad_button_container",
        "modern_skip_ad_text",
        "modern_skip_ad_button",
        "player_learn_more_button",
        "visit_advertiser_button",
        "ad_info_button",
        "ad_badge"
    )

    // Required YouTube & general skip phrases matched via case-insensitive contains()
    private val skipContainsKeywords = listOf(
        "تخطي الإعلان",
        "تخطي الاعلان",
        "تخطي الإعلانات",
        "تخطي الاعلانات",
        "التخطي",
        "تخطي",
        "skip ads",
        "skip ad",
        "skip"
    )

    private val gameCloseKeywords = listOf(
        "إغلاق الإعلان",
        "اغلاق الاعلان",
        "إغلاق",
        "اغلاق",
        "close ad",
        "close",
        "dismiss",
        "×",
        "✕",
        "✖"
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

    private val youtubeAdTextMarkers = listOf(
        "ad ·",
        "إعلان ·",
        "اعلان ·",
        "skip ad",
        "skip ads",
        "تخطي الإعلان",
        "تخطي الاعلان",
        "التخطي",
        "visit advertiser",
        "زيارة موقع المعلن",
        "why this ad"
    )

    private val adActivityKeywords = listOf(
        "adactivity",
        "openadsdk",
        "pangle",
        "applovin",
        "unity",
        "ironsource",
        "vungle",
        "mbridge",
        "mintegral",
        "admob",
        "inmobi",
        "chartboost",
        "fyber",
        "interstitial",
        "rewarded"
    )

    private val gameAdScreenWordRegex = Regex("""(?i)\bad\b""")

    private val gameAdScreenMarkers = listOf(
        "إعلان",
        "اعلان",
        "اعرف المزيد",
        "learn more",
        "sponsored",
        "ممول",
        "مموّل"
    )

    private val excludedCornerIconKeywords = listOf(
        "mute",
        "unmute",
        "volume",
        "sound",
        "audio",
        "speaker",
        "صوت",
        "كتم",
        "feedback",
        "report",
        "info",
        "privacy",
        "adchoices",
        "ad_choices",
        "why",
        "policy",
        "إبلاغ",
        "بلاغ",
        "معلومات",
        "خيارات الإعلان"
    )

    private val positiveCornerIconKeywords = listOf(
        "skip",
        "close",
        "dismiss",
        "next",
        "forward",
        "تخطي",
        "إغلاق",
        ">>"
    )

    fun isSystemPackage(packageName: String): Boolean {
        if (packageName in systemIdlePackages) return true
        return packageName.startsWith("com.android.internal") ||
            packageName.startsWith("com.android.providers.") ||
            packageName.startsWith("com.android.camera") ||
            packageName.startsWith("com.qualcomm.") ||
            packageName.startsWith("com.mediatek.")
    }

    fun isInputMethodPackage(packageName: String): Boolean {
        return packageName in imePackages || packageName.contains("inputmethod") || packageName.contains("keyboard")
    }

    fun isAdActivityName(classNameOrPackage: CharSequence?): Boolean {
        if (classNameOrPackage.isNullOrBlank()) return false
        val lower = classNameOrPackage.toString().lowercase()
        return adActivityKeywords.any { keyword -> lower.contains(keyword) }
    }

    fun isGameAdTextMarker(
        viewIdResourceName: String?,
        text: CharSequence?,
        contentDescription: CharSequence?
    ): Boolean {
        val idLower = viewIdResourceName?.lowercase()
        if (idLower != null && (
                idLower.contains(":id/tt_") ||
                    idLower.contains(":id/al_") ||
                    idLower.contains(":id/unity") ||
                    idLower.contains(":id/ia_") ||
                    idLower.contains(":id/mbridge") ||
                    idLower.contains(":id/vungle") ||
                    idLower.contains("applovin") ||
                    idLower.contains("openadsdk")
                )
        ) {
            return true
        }
        return containsAdScreenMarker(text) || containsAdScreenMarker(contentDescription)
    }

    private fun containsAdScreenMarker(raw: CharSequence?): Boolean {
        if (raw.isNullOrBlank()) return false
        val lower = raw.toString().trim().lowercase()
        if (lower.length > 80) return false
        if (gameAdScreenMarkers.any { marker -> lower.contains(marker) }) return true
        return gameAdScreenWordRegex.containsMatchIn(lower)
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

    fun normalizeText(raw: CharSequence?, maxLength: Int = 64): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.toString()
            .replace('\u00A0', ' ')
            .replace('\u200F', ' ')
            .replace('\u200E', ' ')
            .trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase()
        return cleaned.takeIf { it.isNotEmpty() && it.length <= maxLength }
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
        return matchesYouTubeAdMarker(normText) || matchesYouTubeAdMarker(normDesc)
    }

    private fun matchesYouTubeAdMarker(normalized: String?): Boolean {
        if (normalized == null) return false
        if (youtubeAdTextMarkers.any { marker -> normalized.contains(marker) }) return true
        return matchesSkipContains(normalized)
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

        if (matchesSkipContains(normText) || matchesSkipContains(normDesc)) {
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
            if (idSuffix in gameAdSkipViewIdSuffixes ||
                (idSuffix.startsWith("tt_") && idSuffix.contains("skip"))
            ) {
                return AdTargetType.GAME_AD_SKIP
            }
            if (idSuffix in gameAdCloseViewIdSuffixes ||
                (idSuffix.startsWith("al_") && (idSuffix.contains("close") || idSuffix.contains("skip"))) ||
                (idSuffix.startsWith("unity") && (idSuffix.contains("close") || idSuffix.contains("skip"))) ||
                (idSuffix.startsWith("tt_") && idSuffix.contains("close")) ||
                (idSuffix.startsWith("mbridge_") && idSuffix.contains("close"))
            ) {
                return if (idSuffix.contains("skip")) AdTargetType.GAME_AD_SKIP else AdTargetType.GAME_AD_CLOSE
            }
        }

        if (matchesSkipContains(normText) || matchesSkipContains(normDesc)) {
            return AdTargetType.GAME_AD_SKIP
        }

        if (!isClickable) {
            return null
        }

        if (matchesGameCloseContains(normText) || matchesGameCloseContains(normDesc)) {
            return AdTargetType.GAME_AD_CLOSE
        }

        return null
    }

    fun isExcludedCornerIcon(
        viewIdResourceName: String?,
        text: CharSequence?,
        contentDescription: CharSequence?
    ): Boolean {
        val combined = listOfNotNull(
            viewIdResourceName?.lowercase(),
            text?.toString()?.lowercase(),
            contentDescription?.toString()?.lowercase()
        ).joinToString(" ")

        if (combined.isNotEmpty() && excludedCornerIconKeywords.any { combined.contains(it) }) {
            return true
        }

        val normText = normalizeText(text, maxLength = 32)
        if (normText != null) {
            if (isCountdownText(normText)) return true
            // Avoid mistapping long corner labels that are not close/skip symbols
            val isExplicitPositive = positiveCornerIconKeywords.any { normText.contains(it) } ||
                normText in setOf("x", "×", "✕", "✖", ">", ">>")
            if (normText.length > 4 && !isExplicitPositive) {
                return true
            }
        }
        return false
    }

    fun hasPositiveCornerIconHint(
        viewIdResourceName: String?,
        text: CharSequence?,
        contentDescription: CharSequence?
    ): Boolean {
        val combined = listOfNotNull(
            viewIdResourceName?.lowercase(),
            text?.toString()?.lowercase(),
            contentDescription?.toString()?.lowercase()
        ).joinToString(" ")
        return positiveCornerIconKeywords.any { combined.contains(it) }
    }

    private fun matchesSkipContains(normalized: String?): Boolean {
        if (normalized == null) return false
        return skipContainsKeywords.any { keyword -> normalized.contains(keyword, ignoreCase = true) }
    }

    private fun matchesGameCloseContains(normalized: String?): Boolean {
        if (normalized == null) return false
        if (normalized == "x") return true
        return gameCloseKeywords.any { keyword -> normalized.contains(keyword, ignoreCase = true) }
    }
}
