package com.max.skipflow.engine

data class SocialFeedSwipePlan(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long = SWIPE_DURATION_MS
) {
    companion object {
        const val SWIPE_DURATION_MS = 220L
    }
}

/**
 * Pure JVM-testable detector for sponsored short-form feed items inside
 * YouTube Shorts, TikTok, Instagram Reels, and Facebook Reels.
 */
object SocialAutoSwipeDetector {

    private val tiktokPackages = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically.go"
    )

    private val instagramPackages = setOf(
        "com.instagram.android"
    )

    private val facebookPackages = setOf(
        "com.facebook.katana",
        "com.facebook.lite"
    )

    private val sponsoredMarkers = listOf(
        "sponsored",
        "مُموّل",
        "ممول",
        "إعلان",
        "اعلان",
        "ترويج",
        "promoted",
        "paid partnership"
    )

    private val youtubeShortsContainerViewIdMarkers = listOf(
        "reel_player_page_container",
        "reel_watch_fragment_root",
        "reel_recycler",
        "shorts_video",
        "reel_watch_player"
    )

    private val instagramReelsViewIdMarkers = listOf(
        "clips_viewer",
        "clips_video",
        "reels_viewer",
        "clips_media"
    )

    private val facebookReelsViewIdMarkers = listOf(
        "reels",
        "fb_shorts",
        "video_reels"
    )

    private val sponsoredViewIdSuffixes = setOf(
        "ad_badge",
        "sponsored_label",
        "sponsored_tag",
        "ad_tag",
        "promoted_label"
    )

    fun isSupportedSocialPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return lower == AdDetectionRules.YOUTUBE_PACKAGE ||
            lower in tiktokPackages ||
            lower in instagramPackages ||
            lower in facebookPackages
    }

    fun isFeedSurfaceConfirmed(
        packageName: String,
        viewIdResourceNames: List<String>,
        visibleTexts: List<String>
    ): Boolean {
        val lowerPkg = packageName.lowercase()
        return when {
            lowerPkg in tiktokPackages -> true
            lowerPkg == AdDetectionRules.YOUTUBE_PACKAGE -> {
                viewIdResourceNames.any { id ->
                    val idLower = id.lowercase()
                    youtubeShortsContainerViewIdMarkers.any { idLower.contains(it) }
                }
            }
            lowerPkg in instagramPackages -> {
                viewIdResourceNames.any { id ->
                    val idLower = id.lowercase()
                    instagramReelsViewIdMarkers.any { idLower.contains(it) }
                } || visibleTexts.any { it.trim().equals("Reels", ignoreCase = true) || it.trim() == "ريلز" }
            }
            lowerPkg in facebookPackages -> {
                viewIdResourceNames.any { id ->
                    val idLower = id.lowercase()
                    facebookReelsViewIdMarkers.any { idLower.contains(it) }
                } || visibleTexts.any { it.trim().equals("Reels", ignoreCase = true) || it.trim() == "ريلز" }
            }
            else -> false
        }
    }

    fun isSponsoredFeedLabel(
        viewIdResourceName: String?,
        text: CharSequence?,
        contentDescription: CharSequence?
    ): Boolean {
        val suffix = AdDetectionRules.extractViewIdSuffix(viewIdResourceName)
        if (suffix != null && suffix in sponsoredViewIdSuffixes) {
            return true
        }
        return matchesSponsoredText(text) || matchesSponsoredText(contentDescription)
    }

    private fun matchesSponsoredText(raw: CharSequence?): Boolean {
        val norm = AdDetectionRules.normalizeText(raw, maxLength = 44) ?: return false
        val normalizedArabic = norm
            .replace("\u064F", "")
            .replace("\u0651", "")
            .replace("\u064E", "")
            .replace("\u0650", "")
        return sponsoredMarkers.any { marker ->
            val cleanMarker = marker
                .replace("\u064F", "")
                .replace("\u0651", "")
            if (cleanMarker == "إعلان" || cleanMarker == "اعلان") {
                normalizedArabic == "إعلان" ||
                    normalizedArabic == "اعلان" ||
                    normalizedArabic.startsWith("إعلان ") ||
                    normalizedArabic.startsWith("اعلان ") ||
                    normalizedArabic.contains("· إعلان") ||
                    normalizedArabic.contains("· اعلان") ||
                    normalizedArabic.contains("إعلان ممول")
            } else {
                normalizedArabic == cleanMarker || normalizedArabic.contains(cleanMarker)
            }
        }
    }

    fun buildSwipePlan(
        screenWidth: Int,
        screenHeight: Int,
        jitterX: Float = 0f
    ): SocialFeedSwipePlan {
        val clampedJitter = jitterX.coerceIn(-16f, 16f)
        val x = (screenWidth * 0.5f + clampedJitter).coerceIn(20f, (screenWidth - 20).coerceAtLeast(20).toFloat())
        val startY = (screenHeight * 0.75f).coerceIn(10f, (screenHeight - 10).coerceAtLeast(10).toFloat())
        val endY = (screenHeight * 0.20f).coerceIn(10f, (screenHeight - 10).coerceAtLeast(10).toFloat())
        return SocialFeedSwipePlan(
            startX = x,
            startY = startY,
            endX = x,
            endY = endY,
            durationMs = SocialFeedSwipePlan.SWIPE_DURATION_MS
        )
    }

    const val SWIPE_COOLDOWN_MS = 2_500L
}
