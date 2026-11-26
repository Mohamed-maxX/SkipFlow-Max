package com.max.skipflow.engine

sealed interface StageDecision {
    data class ExecuteTap(
        val stageNumber: Int,
        val countAsNewAdSkip: Boolean
    ) : StageDecision

    data class Ignore(val reason: String) : StageDecision
}

/**
 * Handles multi-stage game ads (e.g. initial close/skip `>>` or `X` followed by a second end-card `X`)
 * with a fast 1000ms cooldown and without freezing same-spot retries (`>>` -> `X` or missed taps).
 */
class MultiStageAdStateMachine {

    private var activePackage: String? = null
    private var stageCount: Int = 0
    private var lastTapAtMs: Long = 0L
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
        if (lastTapAtMs > 0L && elapsedSinceLastTap in 0 until TAP_COOLDOWN_MS) {
            return StageDecision.Ignore("tap_cooldown_1000ms")
        }

        val isSameSession = activePackage == packageName &&
            lastTapAtMs > 0L &&
            elapsedSinceLastTap in 0 until MULTI_STAGE_WINDOW_MS

        if (!isSameSession) {
            activePackage = packageName
            stageCount = 1
            lastTapAtMs = nowMs
            return StageDecision.ExecuteTap(stageNumber = 1, countAsNewAdSkip = true)
        }

        if (stageCount >= MAX_STAGES_PER_AD) {
            return StageDecision.Ignore("max_stages_reached")
        }

        stageCount += 1
        lastTapAtMs = nowMs
        return StageDecision.ExecuteTap(
            stageNumber = stageCount,
            countAsNewAdSkip = false
        )
    }

    fun remainingCooldownMs(nowMs: Long): Long {
        if (lastTapAtMs <= 0L) return 0L
        val elapsed = nowMs - lastTapAtMs
        return if (elapsed in 0 until TAP_COOLDOWN_MS) TAP_COOLDOWN_MS - elapsed else 0L
    }

    fun reset() {
        activePackage = null
        stageCount = 0
        lastTapAtMs = 0L
        lastPopupDismissAtMs = 0L
    }

    companion object {
        const val TAP_COOLDOWN_MS = 1000L
        const val MULTI_STAGE_WINDOW_MS = 12_000L
        const val POPUP_COOLDOWN_MS = 2500L
        const val MAX_STAGES_PER_AD = 8
    }
}
