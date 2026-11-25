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
    fun `multi-stage state machine handles end-card X without double counting`() {
        val machine = MultiStageAdStateMachine()
        val stage1Bounds = BoundsRect(950, 80, 1040, 170)
        val stage2Bounds = BoundsRect(40, 80, 130, 170)

        val d1 = machine.evaluateCandidate("com.game.one", stage1Bounds, AdTargetType.GAME_AD_CLOSE, 1000L)
        assertEquals(StageDecision.ExecuteTap(stageNumber = 1, countAsNewAdSkip = true), d1)

        // Rapid duplicate event at 1200ms should debounce
        val dup = machine.evaluateCandidate("com.game.one", stage1Bounds, AdTargetType.GAME_AD_CLOSE, 1200L)
        assertTrue(dup is StageDecision.Ignore)

        // Second stage end-card X appears at 2200ms in another corner
        val d2 = machine.evaluateCandidate("com.game.one", stage2Bounds, AdTargetType.GAME_AD_CLOSE, 2200L)
        assertEquals(StageDecision.ExecuteTap(stageNumber = 2, countAsNewAdSkip = false), d2)

        // New ad much later resets session
        val nextAd = machine.evaluateCandidate("com.game.one", stage1Bounds, AdTargetType.GAME_AD_CLOSE, 15_000L)
        assertEquals(StageDecision.ExecuteTap(stageNumber = 1, countAsNewAdSkip = true), nextAd)
    }
}
