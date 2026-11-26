package com.max.skipflow.engine

data class CornerCropRatios(
    val widthRatio: Float,
    val heightRatio: Float,
    val isLandscape: Boolean
)

/**
 * Pure JVM-testable geometry helpers for dynamic Portrait/Landscape dimensions,
 * orientation-aware corner crop ratios, bitmap-to-WindowMetrics alignment, and tap clamping.
 */
object DisplayGeometry {

    const val PORTRAIT_CORNER_WIDTH_RATIO = 0.22f
    const val PORTRAIT_CORNER_HEIGHT_RATIO = 0.16f
    const val LANDSCAPE_CORNER_WIDTH_RATIO = 0.18f
    const val LANDSCAPE_CORNER_HEIGHT_RATIO = 0.26f

    fun cornerRatiosFor(width: Int, height: Int): CornerCropRatios {
        val isLandscape = width > height
        return if (isLandscape) {
            CornerCropRatios(
                widthRatio = LANDSCAPE_CORNER_WIDTH_RATIO,
                heightRatio = LANDSCAPE_CORNER_HEIGHT_RATIO,
                isLandscape = true
            )
        } else {
            CornerCropRatios(
                widthRatio = PORTRAIT_CORNER_WIDTH_RATIO,
                heightRatio = PORTRAIT_CORNER_HEIGHT_RATIO,
                isLandscape = false
            )
        }
    }

    /**
     * Ensures WindowMetrics dimensions reflect live orientation when a background Service context
     * lags behind a rotated fullscreen application window or Configuration orientation change.
     */
    fun resolveDynamicDimensions(
        windowWidth: Int,
        windowHeight: Int,
        activeAppBounds: BoundsRect?,
        configIsLandscape: Boolean
    ): Pair<Int, Int> {
        if (windowWidth <= 0 || windowHeight <= 0) {
            return if (activeAppBounds != null && !activeAppBounds.isEmpty) {
                activeAppBounds.width to activeAppBounds.height
            } else {
                windowWidth to windowHeight
            }
        }

        val longSide = maxOf(windowWidth, windowHeight)
        val shortSide = minOf(windowWidth, windowHeight)

        if (activeAppBounds != null && !activeAppBounds.isEmpty) {
            val appLandscape = activeAppBounds.width > activeAppBounds.height
            val spansDisplay = maxOf(activeAppBounds.width, activeAppBounds.height) >= longSide * 0.85f
            if (spansDisplay) {
                return if (appLandscape) longSide to shortSide else shortSide to longSide
            }
        }

        val wmLandscape = windowWidth > windowHeight
        if (configIsLandscape != wmLandscape) {
            return if (configIsLandscape) longSide to shortSide else shortSide to longSide
        }

        return windowWidth to windowHeight
    }

    /**
     * Keeps scaleX/scaleY consistent if a screenshot Bitmap was captured in a rotated orientation
     * relative to WindowMetrics.
     */
    fun alignScreenToBitmap(
        screenWidth: Int,
        screenHeight: Int,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Pair<Int, Int> {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return bitmapWidth.coerceAtLeast(1) to bitmapHeight.coerceAtLeast(1)
        }
        val screenIsLandscape = screenWidth > screenHeight
        val bitmapIsLandscape = bitmapWidth > bitmapHeight
        return if (screenIsLandscape != bitmapIsLandscape) {
            screenHeight to screenWidth
        } else {
            screenWidth to screenHeight
        }
    }

    fun clampTapPoint(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Float, Float> {
        val maxX = (screenWidth - 1).coerceAtLeast(1).toFloat()
        val maxY = (screenHeight - 1).coerceAtLeast(1).toFloat()
        return x.coerceIn(1f, maxX) to y.coerceIn(1f, maxY)
    }
}
