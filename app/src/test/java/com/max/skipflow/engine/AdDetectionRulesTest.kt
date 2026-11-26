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
    }

    @Test
    fun `never matches anything inside strictly excluded packages like Play Store Chrome WhatsApp GMS`() {
        val excluded = listOf(
            "com.android.vending",
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.gms",
            "com.android.chrome",
            "com.whatsapp",
            "com.facebook.katana",
            "com.instagram.android",
            "org.telegram.messenger",
            "com.max.skipflow",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.some.customlauncher"
        )

        for (pkg in excluded) {
            assertTrue("$pkg must be strictly excluded", AdDetectionRules.isStrictlyExcludedPackage(pkg))
            assertNull(
                "$pkg must never yield a match",
                AdDetectionRules.matchCandidate(
                    packageName = pkg,
                    viewIdResourceName = "$pkg:id/close_button",
                    text = "Close",
                    contentDescription = "Skip",
                    isClickableOrHasClickableParent = true,
                    isAdActivityContext = true
                )
            )
        }
    }

    @Test
    fun `restricts generic close and skip matching in games strictly to real Ad Activities`() {
        // Inside normal game Activity (not AdActivity): generic X / Close / Skip must NOT match
        assertNull(
            AdDetectionRules.matchCandidate(
                packageName = "com.game.play",
                viewIdResourceName = null,
                text = ">>",
                contentDescription = null,
                isClickableOrHasClickableParent = true,
                isAdActivityContext = false
            )
        )
        assertNull(
            AdDetectionRules.matchCandidate(
                packageName = "com.game.play",
                viewIdResourceName = null,
                text = "Close",
                contentDescription = null,
                isClickableOrHasClickableParent = true,
                isAdActivityContext = false
            )
        )

        // Inside real AdActivity: generic close/skip & SDK IDs match cleanly
        assertEquals(
            AdTargetType.GAME_AD_CLOSE,
            AdDetectionRules.matchCandidate(
                packageName = "com.game.play",
                viewIdResourceName = null,
                text = ">>",
                contentDescription = null,
                isClickableOrHasClickableParent = false,
                isAdActivityContext = true
            )
        )
        assertEquals(
            AdTargetType.GAME_AD_CLOSE,
            AdDetectionRules.matchCandidate(
                packageName = "com.game.play",
                viewIdResourceName = "com.game.play:id/mbridge_windwv_close",
                text = null,
                contentDescription = null,
                isClickableOrHasClickableParent = false,
                isAdActivityContext = false
            )
        )
        assertEquals(
            AdTargetType.GAME_AD_CONTINUE,
            AdDetectionRules.matchCandidate(
                packageName = "com.game.play",
                viewIdResourceName = null,
                text = "المتابعة إلى التطبيق",
                contentDescription = null,
                isClickableOrHasClickableParent = false,
                isAdActivityContext = false
            )
        )
    }

    @Test
    fun `detects only real ad SDK activities and rejects game or store activities`() {
        val realAds = listOf(
            "com.google.android.gms.ads.AdActivity",
            "com.applovin.adview.AppLovinFullscreenActivity",
            "com.applovin.mediation.MaxFullscreenAdActivity",
            "com.bytedance.sdk.openadsdk.activity.TTRewardVideoActivity",
            "com.bytedance.sdk.openadsdk.activity.TTRewardExpressVideoActivity",
            "com.bytedance.sdk.openadsdk.activity.TTFullScreenExpressVideoActivity",
            "com.bytedance.sdk.openadsdk.activity.TTFullScreenVideoActivity",
            "com.mbridge.msdk.reward.player.MBRewardVideoActivity",
            "com.ironsource.sdk.controller.InterstitialActivity",
            "com.vungle.ads.internal.ui.VungleActivity",
            "com.facebook.ads.AudienceNetworkActivity",
            "sg.bigo.ads.ad.interstitial.BigoInterstitialActivity",
            "com.inmobi.ads.rendering.InMobiAdActivity",
            "com.fyber.inneractive.sdk.activities.InneractiveFullscreenAdActivity"
        )
        for (cls in realAds) {
            assertTrue("Expected real ad activity: $cls", AdDetectionRules.isRealAdActivity(cls))
        }
        assertFalse(AdDetectionRules.AD_ACTIVITY_KEYWORDS.any { it.contains("unityplayer") })
        assertTrue(
            AdDetectionRules.isTtRewardExpressActivity(
                "com.bytedance.sdk.openadsdk.activity.TTRewardExpressVideoActivity"
            )
        )

        val nonAds = listOf(
            "com.unity3d.player.UnityPlayerActivity",
            "com.example.UnityPlayerGameActivity",
            "com.android.vending.AssetBrowserActivity",
            "com.example.LoadingActivity",
            "com.example.MainActivity"
        )
        for (cls in nonAds) {
            assertFalse("Expected non-ad activity: $cls", AdDetectionRules.isRealAdActivity(cls))
        }

        assertTrue(AdDetectionRules.hasExplicitAdContextText("Sponsored", null))
        assertTrue(AdDetectionRules.hasExplicitAdContextText(null, "Learn More"))
        assertTrue(AdDetectionRules.hasExplicitAdContextText("Install", null))
        assertTrue(AdDetectionRules.hasExplicitAdContextText("اعرف المزيد", null))
        assertTrue(AdDetectionRules.hasExplicitAdContextText("إعلان", null))
        assertFalse(AdDetectionRules.hasExplicitAdContextText("Settings", "Pause Game"))
    }

    @Test
    fun `excludes account notification settings menu search back mute info corner icons`() {
        val forbiddenHints = listOf(
            Triple("com.vending:id/account_badge", null, null),
            Triple(null, null, "Notification bell"),
            Triple(null, null, "Profile"),
            Triple("com.game:id/btn_settings", null, null),
            Triple(null, null, "Menu"),
            Triple(null, null, "Search"),
            Triple("com.app:id/toolbar_back", null, null),
            Triple("com.game:id/tt_video_ad_mute", null, null),
            Triple(null, null, "Volume control"),
            Triple(null, null, "Sound"),
            Triple(null, null, "Privacy info"),
            Triple(null, null, "Report feedback"),
            Triple(null, "12s", null)
        )
        for ((id, text, desc) in forbiddenHints) {
            assertTrue(
                "Expected exclusion for ($id, $text, $desc)",
                AdDetectionRules.isExcludedCornerIcon(id, text, desc)
            )
        }
        assertFalse(AdDetectionRules.isExcludedCornerIcon("com.game:id/tt_top_skip", null, null))
    }

    @Test
    fun `ignores countdown labels and protects YouTube generic close`() {
        assertNull(
            AdDetectionRules.matchCandidate(
                packageName = AdDetectionRules.YOUTUBE_PACKAGE,
                viewIdResourceName = "com.google.android.youtube:id/modern_skip_ad_text",
                text = "Skip in 5",
                contentDescription = null,
                isClickableOrHasClickableParent = true
            )
        )
        assertNull(
            AdDetectionRules.matchCandidate(
                packageName = AdDetectionRules.YOUTUBE_PACKAGE,
                viewIdResourceName = "com.google.android.youtube:id/close_button",
                text = "إغلاق",
                contentDescription = null,
                isClickableOrHasClickableParent = true
            )
        )
    }

    @Test
    fun `flags transient systemui, screenrecorder, keyboard, and SkipFlow overlay packages`() {
        val transientPackages = listOf(
            "com.android.systemui",
            "com.miui.screenrecorder",
            "com.oplus.screenrecorder",
            "com.google.android.apps.recorder",
            "com.samsung.android.honeyboard",
            "com.google.android.inputmethod.latin",
            "com.custom.keyboard.app",
            "com.max.skipflow"
        )
        for (pkg in transientPackages) {
            assertTrue(
                "$pkg should be flagged as transient overlay/system package",
                AdDetectionRules.isTransientOverlayPackage(pkg)
            )
        }
        assertFalse(AdDetectionRules.isTransientOverlayPackage("com.game.play"))
        assertFalse(AdDetectionRules.isTransientOverlayPackage(AdDetectionRules.YOUTUBE_PACKAGE))
    }

    @Test
    fun `never matches Google Play or store redirect pills even with double chevron`() {
        val storePills = listOf(
            "Google Play >>",
            "Play Store >>",
            "GetApps >",
            "Install >>",
            "تثبيت >>"
        )
        for (pillText in storePills) {
            assertNull(
                "Must ignore store redirect pill: $pillText",
                AdDetectionRules.matchCandidate(
                    packageName = "com.game.play",
                    viewIdResourceName = null,
                    text = pillText,
                    contentDescription = null,
                    isClickableOrHasClickableParent = true,
                    isAdActivityContext = true
                )
            )
            assertTrue(AdDetectionRules.hasStoreRedirectKeyword(null, pillText, null))
        }

        // Plain ">>" without store text still matches inside real ad activity
        assertEquals(
            AdTargetType.GAME_AD_CLOSE,
            AdDetectionRules.matchCandidate(
                packageName = "com.game.play",
                viewIdResourceName = null,
                text = ">>",
                contentDescription = null,
                isClickableOrHasClickableParent = true,
                isAdActivityContext = true
            )
        )
    }
}
