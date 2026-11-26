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
    fun `allows top 16 percent left and right 22 percent corner icons between 18dp and 80dp`() {
        // 36dp x 36dp at density 3.0 = 108px x 108px
        val topRightIcon = BoundsRect(940, 80, 1048, 188)
        val topLeftIcon = BoundsRect(32, 80, 140, 188)
        // Center-ish X=20%..22% boundary (centerX = 230 / 1080 = 21.3%)
        val innerLeft21Pct = BoundsRect(176, 260, 284, 368)
        val centerTopIcon = BoundsRect(480, 80, 588, 188)
        val lowerIcon = BoundsRect(940, 500, 1048, 608)

        assertTrue(SafeZoneEvaluator.isCornerAdIconSize(topRightIcon, screenW, screenH, density))
        assertTrue(SafeZoneEvaluator.isCornerAdIconSize(topLeftIcon, screenW, screenH, density))
        assertTrue(SafeZoneEvaluator.isCornerAdIconSize(innerLeft21Pct, screenW, screenH, density))
        assertFalse(SafeZoneEvaluator.isCornerAdIconSize(centerTopIcon, screenW, screenH, density))
        assertFalse(SafeZoneEvaluator.isCornerAdIconSize(lowerIcon, screenW, screenH, density))
    }

    @Test
    fun `allows continue to app button and rejects center or full width install banners`() {
        val continueBtn = BoundsRect(240, 1950, 840, 2100)
        val centerBanner = BoundsRect(300, 1000, 780, 1200)
        val bottomCenterInstall = BoundsRect(360, 2050, 720, 2200)
        val fullWidthBanner = BoundsRect(20, 80, 1060, 200)

        assertTrue(SafeZoneEvaluator.isWithinSafeZone(continueBtn, screenW, screenH, AdTargetType.GAME_AD_CONTINUE))
        assertFalse(SafeZoneEvaluator.isWithinSafeZone(centerBanner, screenW, screenH, AdTargetType.GAME_AD_CLOSE))
        assertFalse(SafeZoneEvaluator.isWithinSafeZone(bottomCenterInstall, screenW, screenH, AdTargetType.GAME_AD_SKIP))
        assertFalse(SafeZoneEvaluator.isWithinSafeZone(fullWidthBanner, screenW, screenH, AdTargetType.GAME_AD_CLOSE))
    }

    @Test
    fun `allows YouTube popup dismiss button inside modal sheet region`() {
        val dismissBtn = BoundsRect(120, 1750, 480, 1870)
        assertTrue(SafeZoneEvaluator.isWithinSafeZone(dismissBtn, screenW, screenH, AdTargetType.YOUTUBE_POPUP_DISMISS))
    }

    @Test
    fun `supports Landscape corner regions 18pct x 26pct and YouTube bottom-right skip`() {
        val landW = 2400
        val landH = 1080

        // Landscape corner button pushed slightly down (y = 220..260 -> ~22% of 1080, x <= 18%)
        val landscapeTopLeftIcon = BoundsRect(40, 190, 148, 298) // center = (94, 244) -> x=3.9%, y=22.6% <= 26%
        val landscapeTopRightIcon = BoundsRect(2220, 180, 2328, 288) // center = (2274, 234) -> x=94.75% >= 82%, y=21.7% <= 26%
        val landscapeCenterTopInvalid = BoundsRect(1100, 180, 1208, 288) // x=48% not in 18% side band
        val landscapeTooLowInvalid = BoundsRect(40, 320, 148, 428) // centerY = 374 = 34.6% > 26%

        assertTrue(SafeZoneEvaluator.isCornerAdIconSize(landscapeTopLeftIcon, landW, landH, density))
        assertTrue(SafeZoneEvaluator.isCornerAdIconSize(landscapeTopRightIcon, landW, landH, density))
        assertFalse(SafeZoneEvaluator.isCornerAdIconSize(landscapeCenterTopInvalid, landW, landH, density))
        assertFalse(SafeZoneEvaluator.isCornerAdIconSize(landscapeTooLowInvalid, landW, landH, density))

        // YouTube landscape skip button ("التخطي" / "Skip") in bottom-right corner
        val youtubeLandscapeSkip = BoundsRect(2020, 860, 2340, 960)
        assertTrue(
            SafeZoneEvaluator.isWithinSafeZone(
                youtubeLandscapeSkip,
                landW,
                landH,
                AdTargetType.YOUTUBE_SKIP_AD
            )
        )
    }
}
