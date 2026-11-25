package com.max.skipflow.engine

import kotlin.random.Random

data class HumanTapPlan(
    val x: Float,
    val y: Float,
    val preDelayMs: Long,
    val strokeDurationMs: Long
)

/**
 * Generates natural human-like tap coordinates (2-5 px random offset around center,
 * clamped inside button bounds) and natural reaction delay (100-250 ms).
 */
object HumanTouchGenerator {

    const val MIN_OFFSET_PX = 2
    const val MAX_OFFSET_PX = 5
    const val MIN_DELAY_MS = 100L
    const val MAX_DELAY_MS = 250L
    const val MIN_STROKE_MS = 45L
    const val MAX_STROKE_MS = 80L

    fun generateTapPlan(
        bounds: BoundsRect,
        random: Random = Random.Default
    ): HumanTapPlan {
        val offsetX = randomSignedOffset(random)
        val offsetY = randomSignedOffset(random)

        val padX = if (bounds.width >= 8) 2 else 0
        val padY = if (bounds.height >= 8) 2 else 0

        val minX = (bounds.left + padX).coerceAtMost(bounds.centerX)
        val maxX = (bounds.right - padX).coerceAtLeast(bounds.centerX)
        val minY = (bounds.top + padY).coerceAtMost(bounds.centerY)
        val maxY = (bounds.bottom - padY).coerceAtLeast(bounds.centerY)

        val tapX = (bounds.centerX + offsetX).coerceIn(minX, maxX).toFloat()
        val tapY = (bounds.centerY + offsetY).coerceIn(minY, maxY).toFloat()

        val preDelay = random.nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1)
        val strokeDuration = random.nextLong(MIN_STROKE_MS, MAX_STROKE_MS + 1)

        return HumanTapPlan(
            x = tapX,
            y = tapY,
            preDelayMs = preDelay,
            strokeDurationMs = strokeDuration
        )
    }

    private fun randomSignedOffset(random: Random): Int {
        val magnitude = random.nextInt(MIN_OFFSET_PX, MAX_OFFSET_PX + 1)
        return if (random.nextBoolean()) magnitude else -magnitude
    }
}
