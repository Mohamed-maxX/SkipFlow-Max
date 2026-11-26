package com.max.skipflow.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowFocusResolverTest {

    @Test
    fun `selects active TYPE_APPLICATION window and ignores systemui, overlay, ime, and screenrecorder`() {
        val windows = listOf(
            WindowSnapshot(
                packageName = "com.android.systemui",
                windowType = WindowFocusResolver.TYPE_SYSTEM,
                isActive = true,
                isFocused = true,
                layer = 90,
                bounds = BoundsRect(0, 0, 1080, 120)
            ),
            WindowSnapshot(
                packageName = "com.miui.screenrecorder",
                windowType = WindowFocusResolver.TYPE_APPLICATION,
                isActive = true,
                isFocused = false,
                layer = 85,
                bounds = BoundsRect(20, 400, 220, 480)
            ),
            WindowSnapshot(
                packageName = "com.max.skipflow",
                windowType = WindowFocusResolver.TYPE_ACCESSIBILITY_OVERLAY,
                isActive = false,
                isFocused = false,
                layer = 80,
                bounds = BoundsRect(100, 70, 980, 150)
            ),
            WindowSnapshot(
                packageName = "com.google.android.inputmethod.latin",
                windowType = WindowFocusResolver.TYPE_INPUT_METHOD,
                isActive = false,
                isFocused = false,
                layer = 75,
                bounds = BoundsRect(0, 1500, 1080, 2400)
            ),
            WindowSnapshot(
                packageName = "com.game.puzzle",
                windowType = WindowFocusResolver.TYPE_APPLICATION,
                isActive = true,
                isFocused = true,
                layer = 10,
                bounds = BoundsRect(0, 0, 1080, 2400)
            )
        )

        val activePkg = WindowFocusResolver.selectActiveApplicationPackage(
            windows = windows,
            screenWidth = 1080,
            screenHeight = 2400
        )
        assertEquals("com.game.puzzle", activePkg)
    }

    @Test
    fun `detects SkipFlow Max when fullscreen MainActivity is the foreground TYPE_APPLICATION window`() {
        val windows = listOf(
            WindowSnapshot(
                packageName = "com.android.systemui",
                windowType = WindowFocusResolver.TYPE_SYSTEM,
                isActive = false,
                isFocused = false,
                layer = 90,
                bounds = BoundsRect(0, 0, 1080, 100)
            ),
            WindowSnapshot(
                packageName = "com.max.skipflow",
                windowType = WindowFocusResolver.TYPE_APPLICATION,
                isActive = true,
                isFocused = true,
                layer = 15,
                bounds = BoundsRect(0, 0, 1080, 2400)
            )
        )

        val activePkg = WindowFocusResolver.selectActiveApplicationPackage(
            windows = windows,
            screenWidth = 1080,
            screenHeight = 2400
        )
        assertEquals("com.max.skipflow", activePkg)
    }

    @Test
    fun `returns null when only TYPE_SYSTEM or TYPE_INPUT_METHOD windows are present`() {
        val windows = listOf(
            WindowSnapshot(
                packageName = "com.android.systemui",
                windowType = WindowFocusResolver.TYPE_SYSTEM,
                isActive = true,
                isFocused = true,
                layer = 50,
                bounds = BoundsRect(0, 0, 1080, 2400)
            ),
            WindowSnapshot(
                packageName = "com.samsung.android.honeyboard",
                windowType = WindowFocusResolver.TYPE_INPUT_METHOD,
                isActive = true,
                isFocused = false,
                layer = 40,
                bounds = BoundsRect(0, 1500, 1080, 2400)
            )
        )

        val activePkg = WindowFocusResolver.selectActiveApplicationPackage(
            windows = windows,
            screenWidth = 1080,
            screenHeight = 2400
        )
        assertNull(activePkg)
    }
}
