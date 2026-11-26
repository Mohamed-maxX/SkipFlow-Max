package com.max.skipflow.engine

/**
 * Dual-recognition rules (explicit View IDs + text/contentDescription matching +
 * AdActivity-restricted smart corner detector) for YouTube and mobile game ad networks.
 */
object AdDetectionRules {

    const val SELF_PACKAGE = "com.max.skipflow"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val PLAY_STORE_PACKAGE = "com.android.vending"
    const val GMS_PACKAGE = "com.google.android.gms"

    // Hardcoded Safe Ignore List: never scanned or tapped by the skip engine or corner detector.
    private val strictExcludedPackages = setOf(
        SELF_PACKAGE,
        PLAY_STORE_PACKAGE,
        GMS_PACKAGE,
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.chrome",
        "com.whatsapp",
        "com.facebook.katana",
        "com.instagram.android",
        "org.telegram.messenger",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.home",
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
        "com.coloros.safecenter",
        "org.lineageos.trebuchet"
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

    private val gameAdSkipViewIdContains = listOf(
        "tt_top_skip",
        "tt_reward_skip",
        "tt_full_skip",
        "tt_express_skip",
        "tt_video_ad_close",
        "skip_ad_button",
        "skip_button",
        "btn_skip",
        "ad_skip_button",
        "al_skip_button",
        "unity_ads_skip",
        "unityads_skip"
    )

    private val gameAdCloseViewIdContains = listOf(
        "tt_video_ad_close",
        "tt_close_iv",
        "tt_titlebar_close",
        "mbridge_windwv_close",
        "mbridge_windvane_close",
        "mbridge_iv_close",
        "al_close_button",
        "applovin_native_ad_close_button",
        "ia_tv_close_button",
        "ia_close_button",
        "inter_btn_close",
        "interstitial_close_button",
        "interstitial_control_button",
        "unity_ads_close",
        "unityads_close",
        "vungle_close",
        "mrec_close_button",
        "mraid_close_indicator",
        "close_ad_button",
        "ad_close_button",
        "ad_close",
        "close_button",
        "btn_close",
        "iv_close"
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

    // Explicit YouTube skip phrases
    private val skipContainsKeywords = listOf(
        "تخطي الإعلان",
        "تخطي الاعلان",
        "تخطي الإعلانات",
        "تخطي الاعلانات",
        "التخطي",
        "تخطي",
        "skip ads",
        "skip ad",
        "skip video",
        "skip"
    )

    // Explicit ad phrases safe even when Activity name is wrapped by the game
    private val explicitAdOnlySkipOrCloseKeywords = listOf(
        "المتابعة إلى التطبيق",
        "المتابعة الى التطبيق",
        "continue to app",
        "close ad",
        "إغلاق الإعلان",
        "اغلاق الاعلان",
        "skip ads",
        "skip ad",
        "skip video",
        "تخطي الإعلان",
        "تخطي الاعلان",
        "تخطي الإعلانات"
    )

    private val gameAdActivityCloseKeywords = listOf(
        "إغلاق",
        "اغلاق",
        "close",
        "dismiss",
        ">>",
        "> >",
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
        pattern = """(?i).*(\b(in|after|خلال|بعد)\s*\d+|\d+\s*(s|sec|secs|ث|ثانية|ثوان|ثواني)\b|\b\d{1,2}:\d{2}\b|^\d+$).*"""
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

    // Exact Ad SDK Activity identifiers (never matches generic UnityPlayerActivity, MainActivity, or LoadingActivity)
    private val realAdActivityKeywords = listOf(
        "ttrewardexpressvideoactivity",
        "ttrewardvideoactivity",
        "expressvideo",
        "adactivity",
        "interstitial",
        "reward",
        "fullscreenad",
        "pangle",
        "ttreward",
        "ttfullscreen",
        "applovin",
        "unityads",
        "adunitactivity",
        "vungle",
        "ironsource",
        "mbridge",
        "mbreward",
        "inmobi",
        "chartboost",
        "fyber",
        "bigo",
        "audiencenetworkactivity",
        "openadsdk"
    )

    private val excludedCornerIconKeywords = listOf(
        "account",
        "profile",
        "notification",
        "bell",
        "settings",
        "menu",
        "search",
        "back",
        "mute",
        "unmute",
        "volume",
        "sound",
        "audio",
        "speaker",
        "info",
        "privacy",
        "feedback",
        "report",
        "adchoices",
        "ad_choices",
        "why",
        "policy",
        "حساب",
        "ملف",
        "إشعار",
        "اشعار",
        "تنبيه",
        "جرس",
        "إعدادات",
        "اعدادات",
        "قائمة",
        "بحث",
        "رجوع",
        "عودة",
        "صوت",
        "كتم",
        "معلومات",
        "إبلاغ",
        "بلاغ",
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
        ">>",
        "> >",
        "×",
        "✕",
        "✖"
    )

    fun isStrictlyExcludedPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        if (lower in strictExcludedPackages) return true
        if (isLauncherPackage(lower)) return true
        if (lower.contains("screenrecorder")) return true
        return lower.startsWith("com.android.internal") ||
            lower.startsWith("com.android.providers.") ||
            lower.startsWith("com.android.camera") ||
            lower.startsWith("com.qualcomm.") ||
            lower.startsWith("com.mediatek.")
    }

    fun isLauncherPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return lower.contains("launcher") ||
            lower == "com.miui.home" ||
            lower == "org.lineageos.trebuchet"
    }

    // Backwards-compatible alias used by tests/callers
    fun isSystemPackage(packageName: String): Boolean = isStrictlyExcludedPackage(packageName)

    fun isInputMethodPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return lower in imePackages || lower.contains("inputmethod") || lower.contains("keyboard")
    }

    /**
     * Packages belonging to system overlays, screen recorders, keyboards, or SkipFlow Max itself
     * whose TYPE_WINDOW_STATE_CHANGED events must never overwrite the foreground Activity/Package.
     */
    fun isTransientOverlayPackage(
        packageName: String?,
        selfPackage: String = SELF_PACKAGE
    ): Boolean {
        if (packageName.isNullOrBlank()) return false
        val lower = packageName.trim().lowercase()
        if (lower == "com.android.systemui") return true
        if (lower == selfPackage.lowercase()) return true
        if (lower in imePackages) return true
        return lower.contains("screenrecorder") ||
            lower.contains("recorder") ||
            lower.contains("keyboard") ||
            lower.contains("inputmethod")
    }

    fun isRealAdActivity(className: CharSequence?): Boolean {
        if (className.isNullOrBlank()) return false
        val lower = className.toString().lowercase()
        return realAdActivityKeywords.any { keyword -> lower.contains(keyword) }
    }

    fun isTtRewardExpressActivity(className: CharSequence?): Boolean {
        if (className.isNullOrBlank()) return false
        val lower = className.toString().lowercase()
        return lower.contains("ttrewardexpress") || lower.contains("ttreward")
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
        isClickableOrHasClickableParent: Boolean,
        isAdActivityContext: Boolean = false
    ): AdTargetType? {
        if (isStrictlyExcludedPackage(packageName)) return null

        val idLower = viewIdResourceName?.trim()?.lowercase()
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
                idLower = idLower,
                idSuffix = idSuffix,
                normText = normText,
                normDesc = normDesc,
                isAdActivityContext = isAdActivityContext
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
        idLower: String?,
        idSuffix: String?,
        normText: String?,
        normDesc: String?,
        isAdActivityContext: Boolean
    ): AdTargetType? {
        if (hasExcludedCornerKeyword(idLower, normText, normDesc)) {
            return null
        }

        // 1. Explicit Ad SDK View IDs always match
        if (idLower != null) {
            if (gameAdSkipViewIdContains.any { idLower.contains(it) }) {
                return AdTargetType.GAME_AD_SKIP
            }
            if (gameAdCloseViewIdContains.any { idLower.contains(it) } ||
                (idSuffix != null && (
                    (idSuffix.startsWith("al_") && (idSuffix.contains("close") || idSuffix.contains("skip"))) ||
                        (idSuffix.startsWith("unity") && (idSuffix.contains("close") || idSuffix.contains("skip"))) ||
                        (idSuffix.startsWith("tt_") && idSuffix.contains("close")) ||
                        (idSuffix.startsWith("mbridge_") && idSuffix.contains("close"))
                    ))
            ) {
                return if (idLower.contains("skip")) AdTargetType.GAME_AD_SKIP else AdTargetType.GAME_AD_CLOSE
            }
        }

        // 2. Explicit ad-specific phrases ("Continue to app", "Close Ad", "Skip Ad")
        if (matchesExplicitAdOnlyPhrase(normText) || matchesExplicitAdOnlyPhrase(normDesc)) {
            return if (normText?.contains("تخطي") == true ||
                normText?.contains("skip", ignoreCase = true) == true ||
                normDesc?.contains("تخطي") == true ||
                normDesc?.contains("skip", ignoreCase = true) == true
            ) {
                AdTargetType.GAME_AD_SKIP
            } else {
                AdTargetType.GAME_AD_CONTINUE
            }
        }

        // 3. Generic close/skip glyphs & labels ONLY when current Activity is a real Ad Activity
        if (!isAdActivityContext) {
            return null
        }

        if (matchesSkipContains(normText) || matchesSkipContains(normDesc)) {
            return AdTargetType.GAME_AD_SKIP
        }

        if (matchesGameCloseContains(normText) || matchesGameCloseContains(normDesc)) {
            return AdTargetType.GAME_AD_CLOSE
        }

        return null
    }

    fun hasExcludedCornerKeyword(
        viewIdResourceName: String?,
        text: CharSequence?,
        contentDescription: CharSequence?
    ): Boolean {
        val combined = listOfNotNull(
            viewIdResourceName?.lowercase(),
            text?.toString()?.lowercase(),
            contentDescription?.toString()?.lowercase()
        ).joinToString(" ")
        return combined.isNotEmpty() && excludedCornerIconKeywords.any { combined.contains(it) }
    }

    fun isExcludedCornerIcon(
        viewIdResourceName: String?,
        text: CharSequence?,
        contentDescription: CharSequence?
    ): Boolean {
        if (hasExcludedCornerKeyword(viewIdResourceName, text, contentDescription)) {
            return true
        }

        val normText = normalizeText(text, maxLength = 32)
        if (normText != null) {
            if (isCountdownText(normText)) return true
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

    private fun matchesExplicitAdOnlyPhrase(normalized: String?): Boolean {
        if (normalized == null) return false
        return explicitAdOnlySkipOrCloseKeywords.any { keyword -> normalized.contains(keyword, ignoreCase = true) }
    }

    private fun matchesGameCloseContains(normalized: String?): Boolean {
        if (normalized == null) return false
        if (normalized == "x") return true
        return gameAdActivityCloseKeywords.any { keyword -> normalized.contains(keyword, ignoreCase = true) }
    }
}
