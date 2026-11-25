package com.max.skipflow.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeZoneEvaluatorTest {

    private val screenW = 1080
    private val screenH = 2400

    @Test
    fun `allows top right and top left close buttons in game ads`() {
        val topRight = BoundsRect(920, 90, 1040, 210)
        val topLeft = BoundsRect(40, 90, 160, 210)

        assertTrue(SafeZoneEvaluator.isWithinSafeZone(topRight, screenW, screenH, AdTargetType.GAME_AD_CLOSE))
        assertTrue(SafeZoneEvaluator.isWithinSafeZone(topLeft, screenW, screenH, AdTargetType.GAME_AD_CLOSE))
    }

    @Test
    fun `allows YouTube portrait skip ad button near lower corner of player`() {
        val ytPortraitSkip = BoundsRect(800, 560, 1060, 660)
        assertTrue(SafeZoneEvaluator.isWithinSafeZone(ytPortraitSkip, screenW, screenH, AdTargetType.YOUTUBE_SKIP_AD))
    }

    @Test
    fun `rejects center and bottom center install CTA banners`() {
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
