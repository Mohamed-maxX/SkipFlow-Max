package com.max.skipflow.engine

/**
 * Anti-Misclick Safe Zone validator.
 * - For YouTube Skip Ad buttons: allows anywhere safely visible on screen (including portrait lower-right third).
 * - For Game Ads: validates corner regions & compact dimensions, including top-15% corner icon targets (24dp-72dp).
 */
object SafeZoneEvaluator {

    private const val MIN_BUTTON_SIZE_PX = 12
    private const val EDGE_MARGIN_PX = 2

    fun isWithinSafeZone(
        bounds: BoundsRect,
        screenWidth: Int,
        screenHeight: Int,
        targetType: AdTargetType
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0 || bounds.isEmpty) return false
        if (bounds.width < MIN_BUTTON_SIZE_PX || bounds.height < MIN_BUTTON_SIZE_PX) return false

        val cx = bounds.centerX.toFloat()
        val cy = bounds.centerY.toFloat()
        if (cx < EDGE_MARGIN_PX || cx > screenWidth - EDGE_MARGIN_PX) return false
        if (cy < EDGE_MARGIN_PX || cy > screenHeight - EDGE_MARGIN_PX) return false

        // YouTube Skip Ad button appears in the lower-right third in portrait, bottom-right in landscape, or Shorts overlay.
        // Remove quadrant restrictions so YouTube Skip Ad is never blocked by SafeZone.
        if (targetType == AdTargetType.YOUTUBE_SKIP_AD) {
            return bounds.width <= screenWidth * 0.85f && bounds.height <= screenHeight * 0.25f
        }

        if (targetType == AdTargetType.YOUTUBE_POPUP_DISMISS) {
            return bounds.width <= screenWidth * 0.65f &&
                bounds.height <= screenHeight * 0.16f &&
                cy in (screenHeight * 0.25f)..(screenHeight * 0.96f)
        }

        val maxAllowedWidth = screenWidth * 0.55f
        val maxAllowedHeight = screenHeight * 0.18f
        if (bounds.width > maxAllowedWidth || bounds.height > maxAllowedHeight) return false

        val xRatio = cx / screenWidth.toFloat()
        val yRatio = cy / screenHeight.toFloat()

        if (targetType == AdTargetType.GAME_AD_ICON) {
            val inTopBand = yRatio <= 0.15f
            val inFarLeftOrRight = xRatio <= 0.20f || xRatio >= 0.80f
            return inTopBand && inFarLeftOrRight
        }

        val inTopRight = xRatio >= 0.65f && yRatio <= 0.25f
        val inTopLeft = xRatio <= 0.35f && yRatio <= 0.25f
        val inBottomCorner = (xRatio >= 0.58f || xRatio <= 0.42f) && yRatio in 0.58f..0.94f

        return inTopRight || inTopLeft || inBottomCorner
    }

    fun isCornerAdIconSize(
        bounds: BoundsRect,
        screenWidth: Int,
        screenHeight: Int,
        density: Float
    ): Boolean {
        if (density <= 0f || bounds.isEmpty) return false
        val minPx = (20f * density).toInt()
        val maxPx = (88f * density).toInt()
        if (bounds.width !in minPx..maxPx || bounds.height !in minPx..maxPx) return false
        return isWithinSafeZone(bounds, screenWidth, screenHeight, AdTargetType.GAME_AD_ICON)
    }
}
