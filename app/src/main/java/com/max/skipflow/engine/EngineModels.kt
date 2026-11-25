package com.max.skipflow.engine

/**
 * Pure JVM-compatible bounds rectangle used by safe-zone and human-tap logic
 * so core ad-skipping heuristics can be unit-tested without Android framework mocks.
 */
data class BoundsRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

enum class AdTargetType(val estimatedSavedSeconds: Long) {
    YOUTUBE_SKIP_AD(15L),
    YOUTUBE_POPUP_DISMISS(4L),
    GAME_AD_SKIP(20L),
    GAME_AD_CLOSE(20L)
}
