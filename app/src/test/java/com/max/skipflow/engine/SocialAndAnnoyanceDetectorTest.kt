package com.max.skipflow.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialAndAnnoyanceDetectorTest {

    @Test
    fun `YouTube Shorts swipe triggers only on confirmed Shorts surface with sponsored marker`() {
        assertTrue(
            SocialAutoSwipeDetector.isFeedSurfaceConfirmed(
                packageName = AdDetectionRules.YOUTUBE_PACKAGE,
                viewIdResourceNames = listOf("com.google.android.youtube:id/reel_player_page_container"),
                visibleTexts = listOf("Sponsored")
            )
        )
        // Regular YouTube watch screen must NOT count as Shorts feed even if "Sponsored" text is visible
        assertFalse(
            SocialAutoSwipeDetector.isFeedSurfaceConfirmed(
                packageName = AdDetectionRules.YOUTUBE_PACKAGE,
                viewIdResourceNames = listOf("com.google.android.youtube:id/watch_player"),
                visibleTexts = listOf("Sponsored")
            )
        )
    }

    @Test
    fun `detects Arabic and English sponsored labels across Shorts, TikTok, and Reels`() {
        assertTrue(SocialAutoSwipeDetector.isSupportedSocialPackage("com.zhiliaoapp.musically"))
        assertTrue(SocialAutoSwipeDetector.isSupportedSocialPackage("com.instagram.android"))
        assertTrue(SocialAutoSwipeDetector.isSupportedSocialPackage("com.facebook.katana"))

        assertTrue(SocialAutoSwipeDetector.isSponsoredFeedLabel(null, "Sponsored", null))
        assertTrue(SocialAutoSwipeDetector.isSponsoredFeedLabel(null, "مُموّل", null))
        assertTrue(SocialAutoSwipeDetector.isSponsoredFeedLabel(null, "ممول", null))
        assertTrue(SocialAutoSwipeDetector.isSponsoredFeedLabel(null, "إعلان", null))
        assertTrue(SocialAutoSwipeDetector.isSponsoredFeedLabel(null, "ترويج", null))
        assertFalse(SocialAutoSwipeDetector.isSponsoredFeedLabel(null, "إعلانات المستخدم المفضلة", null))

        assertTrue(
            SocialAutoSwipeDetector.isFeedSurfaceConfirmed(
                packageName = "com.instagram.android",
                viewIdResourceNames = listOf("com.instagram.android:id/clips_viewer_view_pager"),
                visibleTexts = emptyList()
            )
        )

        val plan = SocialAutoSwipeDetector.buildSwipePlan(1080, 2400)
        assertEquals(2400 * 0.75f, plan.startY, 0.1f)
        assertEquals(2400 * 0.20f, plan.endY, 0.1f)
        assertEquals(220L, plan.durationMs)
    }

    @Test
    fun `AnnoyanceKiller dismisses Rate Us and browser Cookie banners only when context exists`() {
        val rateUsTree = listOf(
            AnnoyanceNodeObservation(0, "هل تستمتع بالتطبيق؟ قيّم تطبيقنا الآن", null, BoundsRect(100, 700, 980, 900), isClickable = false),
            AnnoyanceNodeObservation(1, "لاحقاً", null, BoundsRect(200, 1100, 480, 1220), isClickable = true),
            AnnoyanceNodeObservation(2, "تقييم 5 نجوم", null, BoundsRect(560, 1100, 880, 1220), isClickable = true)
        )
        val rateMatch = AnnoyanceKillerDetector.evaluateTree("com.example.game", rateUsTree, 1080, 2400)
        assertNotNull(rateMatch)
        assertEquals("RateUs", rateMatch!!.kind)
        assertEquals(1, rateMatch.nodeIndex)

        // Cookie banner ONLY allowed inside browsers
        val cookieTree = listOf(
            AnnoyanceNodeObservation(0, "We use cookies to improve your experience.", null, BoundsRect(40, 1700, 1040, 1950), isClickable = false),
            AnnoyanceNodeObservation(1, "Accept all", null, BoundsRect(600, 2000, 960, 2120), isClickable = true)
        )
        assertNull(AnnoyanceKillerDetector.evaluateTree("com.example.game", cookieTree, 1080, 2400))

        val browserMatch = AnnoyanceKillerDetector.evaluateTree("com.android.chrome", cookieTree, 1080, 2400)
        assertNotNull(browserMatch)
        assertEquals("CookieBanner", browserMatch!!.kind)
        assertEquals(1, browserMatch.nodeIndex)
    }
}
