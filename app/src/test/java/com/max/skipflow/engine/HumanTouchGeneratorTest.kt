package com.max.skipflow.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class HumanTouchGeneratorTest {

    @Test
    fun `generates offsets between 2 and 5 px and natural delay between 100 and 250 ms`() {
        val bounds = BoundsRect(left = 800, top = 100, right = 1000, bottom = 220)
        val random = Random(42)

        repeat(50) {
            val plan = HumanTouchGenerator.generateTapPlan(bounds, random)
            val dx = abs(plan.x - bounds.centerX)
            val dy = abs(plan.y - bounds.centerY)

            assertTrue("dx=$dx out of 2..5", dx in 2f..5f)
            assertTrue("dy=$dy out of 2..5", dy in 2f..5f)
            assertTrue("delay=${plan.preDelayMs} out of 100..250", plan.preDelayMs in 100L..250L)
            assertTrue(plan.x >= bounds.left && plan.x <= bounds.right)
            assertTrue(plan.y >= bounds.top && plan.y <= bounds.bottom)
        }
    }

    @Test
    fun `multi-stage state machine enforces 1000ms cooldown and allows same-spot or second-stage retry`() {
        val machine = MultiStageAdStateMachine()
        val stage1Bounds = BoundsRect(40, 80, 140, 170)
        val stage2Bounds = BoundsRect(950, 80, 1040, 170)

        val d1 = machine.evaluateCandidate("com.game.one", stage1Bounds, AdTargetType.GAME_AD_ICON, 1000L)
        assertEquals(StageDecision.ExecuteTap(stageNumber = 1, countAsNewAdSkip = true), d1)

        // Rapid event within 1000ms cooldown must be ignored with tap_cooldown_1000ms
        val cooldownIgnored = machine.evaluateCandidate("com.game.one", stage1Bounds, AdTargetType.GAME_AD_ICON, 1900L)
        assertEquals(StageDecision.Ignore("tap_cooldown_1000ms"), cooldownIgnored)

        // After 1000ms cooldown, retry at the exact same top-left coordinates executes immediately (no waiting_previous_button block)
        val sameSpotRetry = machine.evaluateCandidate("com.game.one", stage1Bounds, AdTargetType.GAME_AD_ICON, 2050L)
        assertEquals(StageDecision.ExecuteTap(stageNumber = 2, countAsNewAdSkip = false), sameSpotRetry)

        // After another 1000ms cooldown, next stage end-card X executes without double counting
        val d3 = machine.evaluateCandidate("com.game.one", stage2Bounds, AdTargetType.GAME_AD_CLOSE, 3100L)
        assertEquals(StageDecision.ExecuteTap(stageNumber = 3, countAsNewAdSkip = false), d3)

        // New ad much later resets session
        val nextAd = machine.evaluateCandidate("com.game.one", stage1Bounds, AdTargetType.GAME_AD_ICON, 20_000L)
        assertEquals(StageDecision.ExecuteTap(stageNumber = 1, countAsNewAdSkip = true), nextAd)
    }
}
