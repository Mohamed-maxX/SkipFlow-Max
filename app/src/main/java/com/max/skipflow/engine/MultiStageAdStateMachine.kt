package com.max.skipflow.engine

import kotlin.math.abs

sealed interface StageDecision {
    data class ExecuteTap(
        val stageNumber: Int,
        val countAsNewAdSkip: Boolean
    ) : StageDecision

    data class Ignore(val reason: String) : StageDecision
}

/**
 * Handles multi-stage game ads (e.g. initial close/skip X followed by a second end-card X)
 * while debouncing duplicate taps on the same fading control and avoiding double-counting stats.
 */
class MultiStageAdStateMachine {

    private var activePackage: String? = null
    private var stageCount: Int = 0
    private var lastTapAtMs: Long = 0L
    private var lastBounds: BoundsRect? = null
    private var lastPopupDismissAtMs: Long = 0L

    fun evaluateCandidate(
        packageName: String,
        bounds: BoundsRect,
        targetType: AdTargetType,
        nowMs: Long
    ): StageDecision {
        if (targetType == AdTargetType.YOUTUBE_POPUP_DISMISS) {
            if (nowMs - lastPopupDismissAtMs < POPUP_COOLDOWN_MS) {
                return StageDecision.Ignore("popup_cooldown")
            }
            lastPopupDismissAtMs = nowMs
            return StageDecision.ExecuteTap(stageNumber = 1, countAsNewAdSkip = true)
        }

        val elapsedSinceLastTap = nowMs - lastTapAtMs
        if (elapsedSinceLastTap in 0 until MIN_TAP_GAP_MS) {
            return StageDecision.Ignore("tap_debounce")
        }

        val isSameSession = activePackage == packageName &&
            elapsedSinceLastTap in 0 until MULTI_STAGE_WINDOW_MS

        if (!isSameSession) {
            activePackage = packageName
            stageCount = 1
            lastTapAtMs = nowMs
            lastBounds = bounds
            return StageDecision.ExecuteTap(stageNumber = 1, countAsNewAdSkip = true)
        }

        val prev = lastBounds
        val sameSpot = prev != null &&
            abs(prev.centerX - bounds.centerX) <= SAME_SPOT_TOLERANCE_PX &&
            abs(prev.centerY - bounds.centerY) <= SAME_SPOT_TOLERANCE_PX

        if (sameSpot && elapsedSinceLastTap < SAME_SPOT_SETTLE_MS) {
            return StageDecision.Ignore("waiting_previous_button_dismiss")
        }

        if (stageCount >= MAX_STAGES_PER_AD) {
            return StageDecision.Ignore("max_stages_reached")
        }

        stageCount += 1
        lastTapAtMs = nowMs
        lastBounds = bounds
        return StageDecision.ExecuteTap(
            stageNumber = stageCount,
            countAsNewAdSkip = false
        )
    }

    fun reset() {
        activePackage = null
        stageCount = 0
        lastTapAtMs = 0L
        lastBounds = null
        lastPopupDismissAtMs = 0L
    }

    companion object {
        const val MIN_TAP_GAP_MS = 700L
        const val SAME_SPOT_SETTLE_MS = 1450L
        const val MULTI_STAGE_WINDOW_MS = 7500L
        const val POPUP_COOLDOWN_MS = 2500L
        const val SAME_SPOT_TOLERANCE_PX = 20
        const val MAX_STAGES_PER_AD = 3
    }
}
