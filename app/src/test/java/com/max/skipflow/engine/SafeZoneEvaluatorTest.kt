package com.max.skipflow.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeZoneEvaluatorTest {

    private val screenW = 1080
    private val screenH = 2400
    private val density = 3.0f

    @Test
    fun `allows YouTube skip ad anywhere visible including portrait lower right third`() {
        val portraitLowerRightThird = BoundsRect(800, 1700, 1050, 1820)
        val portraitMidScreen = BoundsRect(760, 1180, 1050, 1290)
        val portraitPlayerCorner = BoundsRect(800, 560, 1060, 660)

        assertTrue(SafeZoneEvaluator.isWithinSafeZone(portraitLowerRightThird, screenW, screenH, AdTargetType.YOUTUBE_SKIP_AD))
        assertTrue(SafeZoneEvaluator.isWithinSafeZone(portraitMidScreen, screenW, screenH, AdTargetType.YOUTUBE_SKIP_AD))
        assertTrue(SafeZoneEvaluator.isWithinSafeZone(portraitPlayerCorner, screenW, screenH, AdTargetType.YOUTUBE_SKIP_AD))
    }

    @Test
    fun `allows top 15 percent far-left and far-right textless corner icons between 24dp and 72dp`() {
        // 36dp x 36dp at density 3.0 = 108px x 108px
        val topRightIcon = BoundsRect(940, 80, 1048, 188)
        val topLeftIcon = BoundsRect(32, 80, 140, 188)
        val centerTopIcon = BoundsRect(480, 80, 588, 188)
        val lowerIcon = BoundsRect(940, 500, 1048, 608)

        assertTrue(SafeZoneEvaluator.isCornerAdIconSize(topRightIcon, screenW, screenH, density))
        assertTrue(SafeZoneEvaluator.isCornerAdIconSize(topLeftIcon, screenW, screenH, density))
        assertFalse(SafeZoneEvaluator.isCornerAdIconSize(centerTopIcon, screenW, screenH, density))
        assertFalse(SafeZoneEvaluator.isCornerAdIconSize(lowerIcon, screenW, screenH, density))
    }

    @Test
    fun `rejects center and bottom center install CTA banners in game ads`() {
        val centerBanner = BoundsRect(300, 1000, 780, 1200)
        val bottomCenterInstall = BoundsRect(360, 2050, 720, 2200)
        val fullWidthBanner = BoundsRect(20, 80, 1060, 200)

        assertFalse(SafeZoneEvaluator.isWithinSafeZone(centerBanner, screenW, screenH, AdTargetType.GAME_AD_CLOSE))
        assertFalse(SafeZoneEvaluator.isWithinSafeZone(bottomCenterInstall, screenW, screenH, AdTargetType.GAME_AD_SKIP))
        assertFalse(SafeZoneEvaluator.isWithinSafeZone(fullWidthBanner, screenW, screenH, AdTargetType.GAME_AD_CLOSE))
    }

    @Test
    fun `allows YouTube popup dismiss button inside modal sheet region`() {
        val dismissBtn = BoundsRect(120, 1750, 480, 1870)
        assertTrue(SafeZoneEvaluator.isWithinSafeZone(dismissBtn, screenW, screenH, AdTargetType.YOUTUBE_POPUP_DISMISS))
    }
}
