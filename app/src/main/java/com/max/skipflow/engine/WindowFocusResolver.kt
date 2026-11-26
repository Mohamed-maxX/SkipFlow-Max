package com.max.skipflow.engine

/**
 * Lightweight JVM-testable representation of an AccessibilityWindowInfo entry
 * used to identify the real foreground `TYPE_APPLICATION` window instead of letting
 * system bars, screen recorders, keyboards, or overlays steal focus.
 */
data class WindowSnapshot(
    val packageName: String?,
    val windowType: Int,
    val isActive: Boolean,
    val isFocused: Boolean,
    val layer: Int,
    val bounds: BoundsRect
)

object WindowFocusResolver {

    // Mirrors AccessibilityWindowInfo constants so core focus logic runs cleanly on JVM tests.
    const val TYPE_APPLICATION = 1
    const val TYPE_INPUT_METHOD = 2
    const val TYPE_SYSTEM = 3
    const val TYPE_ACCESSIBILITY_OVERLAY = 4

    /**
     * Resolves the foreground application package exclusively from `TYPE_APPLICATION` windows,
     * ignoring `TYPE_SYSTEM`, `TYPE_ACCESSIBILITY_OVERLAY`, `TYPE_INPUT_METHOD`, and transient
     * floating overlay packages (`systemui`, `screenrecorder`, `recorder`, `keyboard`, `inputmethod`).
     */
    fun selectActiveApplicationPackage(
        windows: List<WindowSnapshot>,
        screenWidth: Int,
        screenHeight: Int,
        selfPackage: String = AdDetectionRules.SELF_PACKAGE
    ): String? {
        if (windows.isEmpty()) return null

        val nonTransientApps = windows.filter { snapshot ->
            snapshot.windowType == TYPE_APPLICATION &&
                !snapshot.packageName.isNullOrBlank() &&
                !isOverlayOnlyPackage(snapshot.packageName)
        }
        if (nonTransientApps.isEmpty()) return null

        val hasOtherRealApp = nonTransientApps.any {
            !it.packageName.equals(selfPackage, ignoreCase = true)
        }

        val candidates = nonTransientApps.filter { snapshot ->
            if (snapshot.packageName.equals(selfPackage, ignoreCase = true) && hasOtherRealApp) {
                isSubstantialAppWindow(snapshot.bounds, screenWidth, screenHeight)
            } else {
                true
            }
        }.ifEmpty { nonTransientApps }

        return candidates.sortedWith(
            compareByDescending<WindowSnapshot> { it.isActive }
                .thenByDescending { it.isFocused }
                .thenByDescending { it.layer }
                .thenByDescending { it.bounds.width.toLong() * it.bounds.height.toLong() }
        ).firstOrNull()?.packageName
    }

    private fun isOverlayOnlyPackage(packageName: String): Boolean {
        val lower = packageName.trim().lowercase()
        return lower == "com.android.systemui" ||
            AdDetectionRules.isInputMethodPackage(lower) ||
            lower.contains("screenrecorder") ||
            lower.contains("recorder") ||
            lower.contains("keyboard") ||
            lower.contains("inputmethod")
    }

    private fun isSubstantialAppWindow(
        bounds: BoundsRect,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0 || bounds.isEmpty) return true
        return bounds.width >= screenWidth * 0.40f && bounds.height >= screenHeight * 0.35f
    }
}
