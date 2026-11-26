package com.max.skipflow.engine

/**
 * Anti-Misclick Safe Zone validator with dynamic Portrait/Landscape orientation support:
 * - For YouTube Skip Ad buttons: allows anywhere safely visible on screen in both Portrait and
 *   Landscape (including bottom-right corner `"التخطي"` / `"Skip"`).
 * - For Game Ads:
 *   - Explicit "Continue to app" / "Close ad" labels allow safe clickable control sizes anywhere visible.
 *   - Smart Corner Detector (`GAME_AD_ICON`) adapts dynamically:
 *     - Portrait: top 16% height x first/last 22% width.
 *     - Landscape: top 26% height x first/last 18% width (accounts for shorter height & notches).
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

        val ratios = DisplayGeometry.cornerRatiosFor(screenWidth, screenHeight)
        val isLandscape = ratios.isLandscape

        if (targetType == AdTargetType.YOUTUBE_SKIP_AD) {
            val maxHeightRatio = if (isLandscape) 0.40f else 0.25f
            return bounds.width <= screenWidth * 0.85f && bounds.height <= screenHeight * maxHeightRatio
        }

        if (targetType == AdTargetType.GAME_AD_CONTINUE) {
            val maxHeightRatio = if (isLandscape) 0.28f else 0.18f
            return bounds.width <= screenWidth * 0.85f && bounds.height <= screenHeight * maxHeightRatio
        }

        if (targetType == AdTargetType.YOUTUBE_POPUP_DISMISS) {
            val maxHeightRatio = if (isLandscape) 0.28f else 0.16f
            return bounds.width <= screenWidth * 0.65f &&
                bounds.height <= screenHeight * maxHeightRatio &&
                cy in (screenHeight * 0.25f)..(screenHeight * 0.96f)
        }

        val maxAllowedWidth = screenWidth * (if (isLandscape) 0.45f else 0.55f)
        val maxAllowedHeight = screenHeight * (if (isLandscape) 0.30f else 0.18f)
        if (bounds.width > maxAllowedWidth || bounds.height > maxAllowedHeight) return false

        val xRatio = cx / screenWidth.toFloat()
        val yRatio = cy / screenHeight.toFloat()

        if (targetType == AdTargetType.GAME_AD_ICON) {
            val inTopBand = yRatio <= ratios.heightRatio
            val inFarLeftOrRight = xRatio <= ratios.widthRatio || xRatio >= (1f - ratios.widthRatio)
            return inTopBand && inFarLeftOrRight
        }

        val topCornerY = if (isLandscape) 0.28f else 0.25f
        val sideCornerX = if (isLandscape) 0.24f else 0.35f
        val inTopRight = xRatio >= (1f - sideCornerX) && yRatio <= topCornerY
        val inTopLeft = xRatio <= sideCornerX && yRatio <= topCornerY
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
        val minPx = (18f * density).toInt()
        val maxPx = (80f * density).toInt()
        if (bounds.width !in minPx..maxPx || bounds.height !in minPx..maxPx) return false
        return isWithinSafeZone(bounds, screenWidth, screenHeight, AdTargetType.GAME_AD_ICON)
    }
}
