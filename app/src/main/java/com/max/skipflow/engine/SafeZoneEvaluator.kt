package com.max.skipflow.engine

/**
 * Anti-Misclick Safe Zone validator.
 * Ensures detected buttons have plausible control dimensions and lie within
 * standard ad Skip/Close screen regions rather than center/bottom-center CTA areas.
 */
object SafeZoneEvaluator {

    private const val MIN_BUTTON_SIZE_PX = 14
    private const val EDGE_MARGIN_PX = 6

    fun isWithinSafeZone(
        bounds: BoundsRect,
        screenWidth: Int,
        screenHeight: Int,
        targetType: AdTargetType
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0 || bounds.isEmpty) return false

        // Reject tiny invisible nodes or huge banners/install overlays
        if (bounds.width < MIN_BUTTON_SIZE_PX || bounds.height < MIN_BUTTON_SIZE_PX) return false

        val maxAllowedWidth = if (targetType == AdTargetType.YOUTUBE_POPUP_DISMISS) {
            screenWidth * 0.65f
        } else {
            screenWidth * 0.55f
        }
        val maxAllowedHeight = screenHeight * 0.18f
        if (bounds.width > maxAllowedWidth || bounds.height > maxAllowedHeight) return false

        val cx = bounds.centerX.toFloat()
        val cy = bounds.centerY.toFloat()

        // Ensure tap center is safely on screen away from physical/gesture edges
        if (cx < EDGE_MARGIN_PX || cx > screenWidth - EDGE_MARGIN_PX) return false
        if (cy < EDGE_MARGIN_PX || cy > screenHeight - EDGE_MARGIN_PX) return false

        if (targetType == AdTargetType.YOUTUBE_POPUP_DISMISS) {
            // YouTube Premium/offer dialogs and bottom sheets occupy the middle/lower modal band
            return cy in (screenHeight * 0.25f)..(screenHeight * 0.96f)
        }

        val xRatio = cx / screenWidth.toFloat()
        val yRatio = cy / screenHeight.toFloat()

        val inTopRight = xRatio >= 0.65f && yRatio <= 0.25f
        val inTopLeft = xRatio <= 0.35f && yRatio <= 0.25f
        val inBottomCorner = (xRatio >= 0.58f || xRatio <= 0.42f) && yRatio in 0.58f..0.94f

        val inYouTubePortraitPlayerSkip = targetType == AdTargetType.YOUTUBE_SKIP_AD &&
            (xRatio >= 0.55f || xRatio <= 0.45f) &&
            yRatio in 0.14f..0.48f

        return inTopRight || inTopLeft || inBottomCorner || inYouTubePortraitPlayerSkip
    }
}
