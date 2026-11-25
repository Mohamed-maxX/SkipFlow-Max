package com.max.skipflow.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdDetectionRulesTest {

    @Test
    fun `matches YouTube skip button by view id and text`() {
        val byId = AdDetectionRules.matchCandidate(
            packageName = AdDetectionRules.YOUTUBE_PACKAGE,
            viewIdResourceName = "com.google.android.youtube:id/skip_ad_button",
            text = null,
            contentDescription = null,
            isClickableOrHasClickableParent = true
        )
        assertEquals(AdTargetType.YOUTUBE_SKIP_AD, byId)

        val byArabicText = AdDetectionRules.matchCandidate(
            packageName = AdDetectionRules.YOUTUBE_PACKAGE,
            viewIdResourceName = null,
            text = "تخطي الإعلان",
            contentDescription = null,
            isClickableOrHasClickableParent = true
        )
        assertEquals(AdTargetType.YOUTUBE_SKIP_AD, byArabicText)

        val byEnglishText = AdDetectionRules.matchCandidate(
            packageName = AdDetectionRules.YOUTUBE_PACKAGE,
            viewIdResourceName = null,
            text = "Skip Ads",
            contentDescription = null,
            isClickableOrHasClickableParent = true
        )
        assertEquals(AdTargetType.YOUTUBE_SKIP_AD, byEnglishText)
    }

    @Test
    fun `ignores countdown labels before skip becomes active`() {
        val countdownEn = AdDetectionRules.matchCandidate(
            packageName = AdDetectionRules.YOUTUBE_PACKAGE,
            viewIdResourceName = "com.google.android.youtube:id/skip_ad_button",
            text = "Skip in 5",
            contentDescription = null,
            isClickableOrHasClickableParent = true
        )
        assertNull(countdownEn)

        val countdownAr = AdDetectionRules.matchCandidate(
            packageName = "com.some.game",
            viewIdResourceName = null,
            text = "تخطي خلال 3 ثوان",
            contentDescription = null,
            isClickableOrHasClickableParent = true
        )
        assertNull(countdownAr)
    }

    @Test
    fun `matches YouTube premium popup dismiss safely without generic close`() {
        val dismissAr = AdDetectionRules.matchCandidate(
            packageName = AdDetectionRules.YOUTUBE_PACKAGE,
            viewIdResourceName = "com.google.android.youtube:id/mealbar_dismiss_button",
            text = "لا شكراً",
            contentDescription = null,
            isClickableOrHasClickableParent = true
        )
        assertEquals(AdTargetType.YOUTUBE_POPUP_DISMISS, dismissAr)

        val genericCloseInYouTube = AdDetectionRules.matchCandidate(
            packageName = AdDetectionRules.YOUTUBE_PACKAGE,
            viewIdResourceName = "com.google.android.youtube:id/close_button",
            text = "إغلاق",
            contentDescription = null,
            isClickableOrHasClickableParent = true
        )
        assertNull(genericCloseInYouTube)
    }

    @Test
    fun `matches game ad close and skip view ids across AdMob Unity and AppLovin`() {
        assertEquals(
            AdTargetType.GAME_AD_CLOSE,
            AdDetectionRules.matchCandidate(
                packageName = "com.example.game",
                viewIdResourceName = "com.example.game:id/ia_tv_close_button",
                text = null,
                contentDescription = null,
                isClickableOrHasClickableParent = true
            )
        )
        assertEquals(
            AdTargetType.GAME_AD_CLOSE,
            AdDetectionRules.matchCandidate(
                packageName = "com.example.game",
                viewIdResourceName = "com.example.game:id/unity_ads_close_button",
                text = null,
                contentDescription = null,
                isClickableOrHasClickableParent = true
            )
        )
        assertEquals(
            AdTargetType.GAME_AD_SKIP,
            AdDetectionRules.matchCandidate(
                packageName = "com.example.game",
                viewIdResourceName = "com.example.game:id/al_skip_button",
                text = null,
                contentDescription = null,
                isClickableOrHasClickableParent = true
            )
        )
    }

    @Test
    fun `identifies system packages and YouTube ad markers accurately`() {
        assertTrue(AdDetectionRules.isSystemPackage("com.android.systemui"))
        assertTrue(AdDetectionRules.isSystemPackage("com.android.settings"))
        assertFalse(AdDetectionRules.isSystemPackage(AdDetectionRules.YOUTUBE_PACKAGE))

        assertTrue(
            AdDetectionRules.isYouTubeAdIndicator(
                viewIdResourceName = "com.google.android.youtube:id/ad_progress_text",
                text = "Ad · 0:12",
                contentDescription = null
            )
        )
    }
}
