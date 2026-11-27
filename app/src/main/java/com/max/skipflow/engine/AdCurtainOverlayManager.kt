package com.max.skipflow.engine

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pure JVM-testable geometry helper guaranteeing the Ad Curtain never covers top ad corners.
 */
object AdCurtainLayoutPolicy {
    const val PORTRAIT_TOP_MARGIN_RATIO = 0.16f
    const val LANDSCAPE_TOP_MARGIN_RATIO = 0.22f
    const val HIDE_SETTLE_DELAY_MS = 50L

    fun topExclusionOffsetPx(screenWidth: Int, screenHeight: Int): Int {
        if (screenHeight <= 0) return 0
        val ratio = if (screenWidth > screenHeight) {
            LANDSCAPE_TOP_MARGIN_RATIO
        } else {
            PORTRAIT_TOP_MARGIN_RATIO
        }
        return (screenHeight * ratio).toInt().coerceAtLeast(0)
    }
}

/**
 * Displays a sleek Obsidian-Black & Electric-Blue semi-transparent curtain overlay
 * (`TYPE_ACCESSIBILITY_OVERLAY`) during confirmed full-screen Ad Activity countdowns:
 * 1. Positioned below the top corner strip (top 16% Portrait / 22% Landscape) so top-corner
 *    `>>` / `X` / `Skip` controls remain 100% unobstructed.
 * 2. The dark backdrop window uses `FLAG_NOT_TOUCHABLE` so automated/user taps never get swallowed;
 *    only the compact centered card's `"إغلاق الستار"` pill accepts dismiss taps.
 * 3. Supports synchronous `View.INVISIBLE` + 50ms frame-clear hooks before `takeScreenshot()` or `dispatchGesture()`.
 */
class AdCurtainOverlayManager(
    private val serviceContext: Context
) {
    private val windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var veilView: FrameLayout? = null

    @Volatile
    private var controlCardView: LinearLayout? = null

    @Volatile
    private var activeSessionKey: String? = null

    @Volatile
    private var dismissedSessionKey: String? = null

    @Volatile
    private var activeFlag: Boolean = false

    @Volatile
    private var lastHideOrDismissElapsedMs: Long = 0L

    val isShowing: Boolean
        get() = (activeFlag || veilView != null || controlCardView != null)

    fun isDismissedForSession(sessionKey: String): Boolean {
        return dismissedSessionKey != null && dismissedSessionKey == sessionKey
    }

    fun resetSessionDismissalIfChanged(sessionKey: String?) {
        if (sessionKey != dismissedSessionKey) {
            dismissedSessionKey = null
        }
    }

    fun show(
        sessionKey: String,
        onUserDismissed: () -> Unit
    ) {
        if (isDismissedForSession(sessionKey)) {
            return
        }
        if (activeFlag && activeSessionKey == sessionKey && veilView != null) {
            return
        }

        activeSessionKey = sessionKey
        activeFlag = true

        runOnMain {
            if (!activeFlag || isDismissedForSession(sessionKey)) return@runOnMain
            val existingVeil = veilView
            val existingCard = controlCardView
            if (existingVeil != null && existingCard != null) {
                existingVeil.visibility = View.VISIBLE
                existingCard.visibility = View.VISIBLE
                return@runOnMain
            }

            dismissInternalOnMain()

            val (screenW, screenH) = queryDisplaySizePx()
            val topMarginPx = AdCurtainLayoutPolicy.topExclusionOffsetPx(screenW, screenH)
            val veilHeightPx = (screenH - topMarginPx).coerceAtLeast(120)
            val density = serviceContext.resources.displayMetrics.density.coerceAtLeast(1f)

            // 1. Non-touchable translucent veil starting BELOW the top 16%/22% ad corner region
            val veil = FrameLayout(serviceContext).apply {
                setBackgroundColor(Color.parseColor("#D405070A"))
                isClickable = false
                isFocusable = false
            }
            val veilParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                veilHeightPx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = topMarginPx
            }

            // 2. Compact centered card whose ONLY interactive zone is the centered card / close pill
            val cardPadH = (22f * density).toInt()
            val cardPadV = (18f * density).toInt()
            val card = LinearLayout(serviceContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(cardPadH, cardPadV, cardPadH, cardPadV)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 24f * density
                    setColor(Color.parseColor("#F20D131F"))
                    setStroke(
                        (1.5f * density).toInt().coerceAtLeast(1),
                        Color.parseColor("#CC0088FF")
                    )
                }
            }

            val badge = TextView(serviceContext).apply {
                text = "AD SHIELD"
                setTextColor(Color.parseColor("#00E5FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding((12f * density).toInt(), (4f * density).toInt(), (12f * density).toInt(), (4f * density).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 99f * density
                    setColor(Color.parseColor("#260088FF"))
                    setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#6600E5FF"))
                }
            }

            val title = TextView(serviceContext).apply {
                text = "SkipFlow Max • جاري التخطي..."
                setTextColor(Color.parseColor("#F4F7FC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(Typeface.DEFAULT_BOLD)
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.CENTER
                setPadding(0, (10f * density).toInt(), 0, (4f * density).toInt())
            }

            val subtitle = TextView(serviceContext).apply {
                text = "تم كتم الصوت وحجب وسط الشاشة مؤقتاً"
                setTextColor(Color.parseColor("#9AA6BA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (12f * density).toInt())
            }

            val closePill = TextView(serviceContext).apply {
                text = "إغلاق الستار"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(Typeface.DEFAULT_BOLD)
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.CENTER
                setPadding((18f * density).toInt(), (8f * density).toInt(), (18f * density).toInt(), (8f * density).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 99f * density
                    setColor(Color.parseColor("#FF0088FF"))
                }
                isClickable = true
                setOnClickListener {
                    dismissedSessionKey = activeSessionKey
                    activeFlag = false
                    dismissInternalOnMain()
                    onUserDismissed()
                }
            }

            card.addView(badge)
            card.addView(title)
            card.addView(subtitle)
            card.addView(closePill)

            val cardParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            runCatching {
                windowManager.addView(veil, veilParams)
                veilView = veil
                windowManager.addView(card, cardParams)
                controlCardView = card
            }.onFailure {
                dismissInternalOnMain()
                activeFlag = false
            }
        }
    }

    fun dismiss(clearSessionDismissal: Boolean = false) {
        val wasAttached = isShowing
        activeFlag = false
        if (clearSessionDismissal) {
            dismissedSessionKey = null
            activeSessionKey = null
        }
        if (wasAttached) {
            lastHideOrDismissElapsedMs = SystemClock.elapsedRealtime()
        }
        runOnMain {
            veilView?.visibility = View.INVISIBLE
            controlCardView?.visibility = View.INVISIBLE
            dismissInternalOnMain()
        }
    }

    /**
     * Immediately sets curtain views to `View.INVISIBLE`, waits `50ms` for the WindowManager/SurfaceFlinger
     * frame to clear cleanly, and invokes [onFrameCleared] (used before `takeScreenshot` and `dispatchGesture`).
     */
    fun hideTemporarilyForCapture(onReadyForCapture: () -> Unit) {
        hideAndRunAfterFrameClear(onReadyForCapture)
    }

    fun hideAndRunAfterFrameClear(onFrameCleared: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        val hasViews = veilView != null || controlCardView != null
        if (!hasViews) {
            val sinceDismiss = now - lastHideOrDismissElapsedMs
            if (lastHideOrDismissElapsedMs > 0L && sinceDismiss in 0 until AdCurtainLayoutPolicy.HIDE_SETTLE_DELAY_MS) {
                mainHandler.postDelayed(
                    { onFrameCleared() },
                    AdCurtainLayoutPolicy.HIDE_SETTLE_DELAY_MS - sinceDismiss
                )
            } else {
                onFrameCleared()
            }
            return
        }
        runOnMain {
            val veil = veilView
            val card = controlCardView
            val wasVisible = (veil?.visibility == View.VISIBLE) || (card?.visibility == View.VISIBLE)
            veil?.visibility = View.INVISIBLE
            card?.visibility = View.INVISIBLE
            if (wasVisible) {
                lastHideOrDismissElapsedMs = SystemClock.elapsedRealtime()
                mainHandler.postDelayed({ onFrameCleared() }, AdCurtainLayoutPolicy.HIDE_SETTLE_DELAY_MS)
            } else {
                val sinceHide = SystemClock.elapsedRealtime() - lastHideOrDismissElapsedMs
                if (lastHideOrDismissElapsedMs > 0L && sinceHide in 0 until AdCurtainLayoutPolicy.HIDE_SETTLE_DELAY_MS) {
                    mainHandler.postDelayed(
                        { onFrameCleared() },
                        AdCurtainLayoutPolicy.HIDE_SETTLE_DELAY_MS - sinceHide
                    )
                } else {
                    onFrameCleared()
                }
            }
        }
    }

    fun restoreAfterCapture() {
        runOnMain {
            val session = activeSessionKey
            if (activeFlag && (session == null || !isDismissedForSession(session))) {
                veilView?.visibility = View.VISIBLE
                controlCardView?.visibility = View.VISIBLE
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun queryDisplaySizePx(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun dismissInternalOnMain() {
        veilView?.let { v ->
            v.visibility = View.INVISIBLE
            runCatching { windowManager.removeViewImmediate(v) }
        }
        veilView = null
        controlCardView?.let { c ->
            c.visibility = View.INVISIBLE
            runCatching { windowManager.removeViewImmediate(c) }
        }
        controlCardView = null
    }
}
