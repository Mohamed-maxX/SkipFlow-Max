package com.max.skipflow.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayStoreRedirectGuardTest {

    @Test
    fun `bounces back once when Play Store opens within 3 seconds of game skip attempt`() {
        val guard = PlayStoreRedirectGuard()
        guard.recordGameOrAdSkipAttempt("com.example.game", nowMs = 10_000L)

        // Play Store opens 1.2s after tap -> bounce back ONCE
        assertTrue(guard.shouldBounceBack(AdDetectionRules.PLAY_STORE_PACKAGE, bounceBackEnabled = true, nowMs = 11_200L))
        // Second event from Play Store for same attempt must NOT loop
        assertFalse(guard.shouldBounceBack(AdDetectionRules.PLAY_STORE_PACKAGE, bounceBackEnabled = true, nowMs = 11_500L))
    }

    @Test
    fun `bounces back from android ResolverActivity or store chooser dialog during active ad`() {
        val guard = PlayStoreRedirectGuard()
        guard.markActiveAdContext("com.example.game", nowMs = 20_000L)

        // Store chooser opens under android / ResolverActivity while inside active ad
        assertTrue(
            guard.shouldBounceBack(
                enteredPackage = "android",
                bounceBackEnabled = true,
                nowMs = 21_000L,
                enteredClassName = "com.android.internal.app.ResolverActivity",
                hasStoreChooserContent = true
            )
        )
        // Rapid duplicate chooser event inside cooldown does NOT double press Back
        assertFalse(
            guard.shouldBounceBack(
                enteredPackage = "android",
                bounceBackEnabled = true,
                nowMs = 21_400L,
                enteredClassName = "com.android.internal.app.ChooserActivity"
            )
        )

        // Later ad event -> chooser dialog detected via store text triggers Back again
        guard.markActiveAdContext("com.example.game", nowMs = 25_000L)
        assertTrue(
            guard.shouldBounceBack(
                enteredPackage = "com.android.intentresolver",
                bounceBackEnabled = true,
                nowMs = 25_800L,
                enteredClassName = "ChooserActivity",
                hasStoreChooserContent = true
            )
        )
    }

    @Test
    fun `does not bounce back after 3 seconds or when user disables toggle or exits via launcher`() {
        val guard = PlayStoreRedirectGuard()
        guard.recordGameOrAdSkipAttempt("com.example.game", nowMs = 10_000L)

        // Disabled in settings
        assertFalse(guard.shouldBounceBack(AdDetectionRules.PLAY_STORE_PACKAGE, bounceBackEnabled = false, nowMs = 10_500L))

        // Past 3000ms window
        assertFalse(guard.shouldBounceBack(AdDetectionRules.PLAY_STORE_PACKAGE, bounceBackEnabled = true, nowMs = 13_500L))

        // Launcher reset clears pending redirect guard & active ad context
        guard.recordGameOrAdSkipAttempt("com.example.game", nowMs = 20_000L)
        guard.markActiveAdContext("com.example.game", nowMs = 20_000L)
        guard.clearOnLauncherOrUserExit()
        assertFalse(guard.shouldBounceBack(AdDetectionRules.PLAY_STORE_PACKAGE, bounceBackEnabled = true, nowMs = 20_500L))
        assertFalse(
            guard.shouldBounceBack(
                enteredPackage = "android",
                bounceBackEnabled = true,
                nowMs = 20_500L,
                enteredClassName = "ResolverActivity"
            )
        )
    }

    @Test
    fun `records and consumes Fake-X candidate metadata when redirect bounce-back triggers`() {
        val guard = PlayStoreRedirectGuard()
        val trapBounds = BoundsRect(960, 40, 1050, 130)
        guard.recordGameOrAdSkipAttempt(
            packageName = "com.example.game",
            nowMs = 30_000L,
            activityName = "com.bytedance.sdk.openadsdk.activity.TTRewardExpressVideoActivity",
            bounds = trapBounds,
            screenWidth = 1080,
            screenHeight = 2400,
            viewId = "com.example.game:id/fake_close_btn"
        )

        assertTrue(
            guard.shouldBounceBack(
                enteredPackage = AdDetectionRules.PLAY_STORE_PACKAGE,
                bounceBackEnabled = true,
                nowMs = 31_100L
            )
        )

        val tappedInfo = guard.consumeRedirectedTapInfo()
        assertNotNull(tappedInfo)
        assertEquals("com.example.game", tappedInfo!!.packageName)
        assertEquals("com.example.game:id/fake_close_btn", tappedInfo.viewId)
        assertEquals(trapBounds.centerX / 1080f, tappedInfo.normCenterX, 0.001f)
        assertEquals(trapBounds.centerY / 2400f, tappedInfo.normCenterY, 0.001f)
        assertNull(guard.consumeRedirectedTapInfo())
    }
}
