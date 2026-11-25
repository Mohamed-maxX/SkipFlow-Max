package com.max.skipflow.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdDetectionRulesTest {

    @Test
    fun `matches YouTube skip button by view ids and contains text or contentDescription`() {
        val containerById = AdDetectionRules.matchCandidate(
            packageName = AdDetectionRules.YOUTUBE_PACKAGE,
            viewIdResourceName = "com.google.android.youtube:id/skip_ad_button_container",
            text = null,
            contentDescription = null,
            isClickableOrHasClickableParent = true
        )
        assertEquals(AdTargetType.YOUTUBE_SKIP_AD, containerById)

        val modernTextById = AdDetectionRules.matchCandidate(
            packageName = AdDetectionRules.YOUTUBE_PACKAGE,
            viewIdResourceName = "com.google.android.youtube:id/modern_skip_ad_text",
            text = null,
            contentDescription = null,
            isClickableOrHasClickableParent = false
        )
        assertEquals(AdTargetType.YOUTUBE_SKIP_AD, modernTextById)

        val byContainsArabicText = AdDetectionRules.matchCandidate(
            packageName = AdDetectionRules.YOUTUBE_PACKAGE,
            viewIdResourceName = null,
            text = "اضغط هنا لتخطي الإعلان الآن",
            contentDescription = null,
            isClickableOrHasClickableParent = false
        )
        assertEquals(AdTargetType.YOUTUBE_SKIP_AD, byContainsArabicText)

        val byContainsAlTakhtiDesc = AdDetectionRules.matchCandidate(
            packageName = AdDetectionRules.YOUTUBE_PACKAGE,
            viewIdResourceName = null,
            text = null,
            contentDescription = "زر التخطي",
            isClickableOrHasClickableParent = false
        )
        assertEquals(AdTargetType.YOUTUBE_SKIP_AD, byContainsAlTakhtiDesc)

        val byContainsEnglishDesc = AdDetectionRules.matchCandidate(
            packageName = AdDetectionRules.YOUTUBE_PACKAGE,
            viewIdResourceName = null,
            text = null,
            contentDescription = "Tap to SKIP AD now",
            isClickableOrHasClickableParent = false
        )
        assertEquals(AdTargetType.YOUTUBE_SKIP_AD, byContainsEnglishDesc)
    }

    @Test
    fun `matches WebView and game SDK view IDs and text even when not marked clickable`() {
        assertEquals(
            AdTargetType.GAME_AD_CLOSE,
            AdDetectionRules.matchCandidate(
                packageName = "com.game.play",
                viewIdResourceName = "com.game.play:id/mbridge_windwv_close",
                text = null,
                contentDescription = null,
                isClickableOrHasClickableParent = false
            )
        )
        assertEquals(
            AdTargetType.GAME_AD_SKIP,
            AdDetectionRules.matchCandidate(
                packageName = "com.game.play",
                viewIdResourceName = "com.game.play:id/tt_reward_full_count_down",
                text = "Skip",
                contentDescription = null,
                isClickableOrHasClickableParent = false
            )
        )
        assertEquals(
            AdTargetType.GAME_AD_CLOSE,
            AdDetectionRules.matchCandidate(
                packageName = "com.game.play",
                viewIdResourceName = "com.game.play:id/inter_btn_close",
                text = null,
                contentDescription = null,
                isClickableOrHasClickableParent = false
            )
        )
        assertEquals(
            AdTargetType.GAME_AD_CLOSE,
            AdDetectionRules.matchCandidate(
                packageName = "com.game.play",
                viewIdResourceName = null,
                text = ">>",
                contentDescription = null,
                isClickableOrHasClickableParent = false
            )
        )
        assertEquals(
            AdTargetType.GAME_AD_CONTINUE,
            AdDetectionRules.matchCandidate(
                packageName = "com.game.play",
                viewIdResourceName = null,
                text = "المتابعة إلى التطبيق",
                contentDescription = null,
                isClickableOrHasClickableParent = false
            )
        )
        assertEquals(
            AdTargetType.GAME_AD_CONTINUE,
            AdDetectionRules.matchCandidate(
                packageName = "com.game.play",
                viewIdResourceName = null,
                text = null,
                contentDescription = "Continue to app",
                isClickableOrHasClickableParent = false
            )
        )
    }

    @Test
    fun `ignores countdown labels before skip becomes active`() {
        val countdownEn = AdDetectionRules.matchCandidate(
            packageName = AdDetectionRules.YOUTUBE_PACKAGE,
            viewIdResourceName = "com.google.android.youtube:id/modern_skip_ad_text",
            text = "Skip in 5",
            contentDescription = null,
            isClickableOrHasClickableParent = true
        )
        assertNull(countdownEn)

        val countdownPangle = AdDetectionRules.matchCandidate(
            packageName = "com.some.game",
            viewIdResourceName = "com.some.game:id/tt_reward_full_count_down",
            text = "15s",
            contentDescription = null,
            isClickableOrHasClickableParent = true
        )
        assertNull(countdownPangle)
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
    fun `detects game ad screen markers across SDKs and excludes mute or info corner icons`() {
        assertTrue(AdDetectionRules.isAdActivityName("com.bytedance.sdk.openadsdk.activity.TTRewardVideoActivity"))
        assertTrue(AdDetectionRules.isAdActivityName("com.applovin.adview.AppLovinFullscreenActivity"))
        assertTrue(AdDetectionRules.isAdActivityName("com.google.android.gms.ads.AdActivity"))
        assertTrue(AdDetectionRules.isAdActivityName("com.example.RewardedAdActivity"))
        assertTrue(AdDetectionRules.isAdActivityName("sg.bigo.ads.ad.interstitial.BigoInterstitialActivity"))
        assertFalse(AdDetectionRules.isAdActivityName("com.example.LoadingActivity"))

        assertTrue(AdDetectionRules.isGameAdTextMarker(null, "إعلان", null))
        assertTrue(AdDetectionRules.isGameAdTextMarker(null, "Install", null))
        assertTrue(AdDetectionRules.isGameAdTextMarker(null, "Download", null))
        assertTrue(AdDetectionRules.isGameAdTextMarker(null, "Open", null))
        assertTrue(AdDetectionRules.isGameAdTextMarker(null, "اعرف المزيد", null))
        assertFalse(AdDetectionRules.isGameAdTextMarker(null, "Score 1500", null))

        assertTrue(AdDetectionRules.isExcludedCornerIcon("com.game:id/tt_video_ad_mute", null, null))
        assertTrue(AdDetectionRules.isExcludedCornerIcon(null, null, "AdChoices"))
        assertTrue(AdDetectionRules.isExcludedCornerIcon(null, "12s", null))
        assertFalse(AdDetectionRules.isExcludedCornerIcon("com.game:id/tt_top_skip", null, null))
    }

    @Test
    fun `identifies system packages and self package accurately`() {
        assertTrue(AdDetectionRules.isSystemPackage(AdDetectionRules.SELF_PACKAGE))
        assertTrue(AdDetectionRules.isSystemPackage("com.android.systemui"))
        assertTrue(AdDetectionRules.isSystemPackage("com.android.settings"))
        assertFalse(AdDetectionRules.isSystemPackage(AdDetectionRules.YOUTUBE_PACKAGE))
        assertFalse(AdDetectionRules.isSystemPackage("com.supercell.clashroyale"))
    }
}
