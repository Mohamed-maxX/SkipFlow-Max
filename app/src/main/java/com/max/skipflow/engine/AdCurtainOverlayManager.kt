package com.max.skipflow.engine

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Displays a sleek Obsidian-Black & Electric-Blue semi-transparent curtain overlay
 * (`TYPE_ACCESSIBILITY_OVERLAY`) during confirmed full-screen Ad Activity countdowns.
 * Includes a tap-to-dismiss failsafe so the user is never trapped, plus brief hide/restore hooks
 * when OpenCV/Tesseract needs to capture a clean screenshot of the underlying ad.
 */
class AdCurtainOverlayManager(
    private val serviceContext: Context
) {
    private val windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var curtainView: FrameLayout? = null

    @Volatile
    private var activeSessionKey: String? = null

    @Volatile
    private var dismissedSessionKey: String? = null

    @Volatile
    private var activeFlag: Boolean = false

    val isShowing: Boolean
        get() = activeFlag && curtainView != null

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
        if (activeFlag && activeSessionKey == sessionKey && curtainView != null) {
            return
        }

        activeSessionKey = sessionKey
        activeFlag = true

        mainHandler.post {
            if (!activeFlag || isDismissedForSession(sessionKey)) return@post
            val existing = curtainView
            if (existing != null) {
                existing.visibility = View.VISIBLE
                return@post
            }

            val density = serviceContext.resources.displayMetrics.density.coerceAtLeast(1f)
            val root = FrameLayout(serviceContext).apply {
                setBackgroundColor(Color.parseColor("#D905070A"))
                isClickable = true
                isFocusable = false
                setOnClickListener {
                    dismissedSessionKey = activeSessionKey
                    dismissInternalOnMain()
                    onUserDismissed()
                }
            }

            val cardPadH = (24f * density).toInt()
            val cardPadV = (20f * density).toInt()
            val card = LinearLayout(serviceContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(cardPadH, cardPadV, cardPadH, cardPadV)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 24f * density
                    setColor(Color.parseColor("#F00D131F"))
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(Typeface.DEFAULT_BOLD)
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.CENTER
                setPadding(0, (12f * density).toInt(), 0, (6f * density).toInt())
            }

            val subtitle = TextView(serviceContext).apply {
                text = "تم حجب الإعلان وكتم صوته حتى انتهاء العدّ التنازلي"
                setTextColor(Color.parseColor("#9AA6BA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (14f * density).toInt())
            }

            val failsafeHint = TextView(serviceContext).apply {
                text = "اضغط في أي مكان لإغلاق الستار فوراً"
                setTextColor(Color.parseColor("#0088FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(Typeface.DEFAULT_BOLD)
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.CENTER
            }

            card.addView(badge)
            card.addView(title)
            card.addView(subtitle)
            card.addView(failsafeHint)

            val cardParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                val margin = (24f * density).toInt()
                setMargins(margin, margin, margin, margin)
            }
            root.addView(card, cardParams)

            val windowParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            runCatching {
                windowManager.addView(root, windowParams)
                curtainView = root
            }.onFailure {
                activeFlag = false
            }
        }
    }

    fun dismiss(clearSessionDismissal: Boolean = false) {
        activeFlag = false
        if (clearSessionDismissal) {
            dismissedSessionKey = null
            activeSessionKey = null
        }
        mainHandler.post {
            dismissInternalOnMain()
        }
    }

    /**
     * Temporarily makes the curtain invisible when OpenCV/Tesseract needs to inspect the actual
     * underlying ad pixels via `takeScreenshot`, then restores visibility once capture finishes.
     */
    fun hideTemporarilyForCapture(onReadyForCapture: () -> Unit) {
        if (!activeFlag || curtainView == null) {
            onReadyForCapture()
            return
        }
        mainHandler.post {
            val view = curtainView
            if (view != null && view.visibility == View.VISIBLE) {
                view.visibility = View.INVISIBLE
                mainHandler.postDelayed({ onReadyForCapture() }, 70L)
            } else {
                onReadyForCapture()
            }
        }
    }

    fun restoreAfterCapture() {
        mainHandler.post {
            val view = curtainView ?: return@post
            val session = activeSessionKey
            if (activeFlag && (session == null || !isDismissedForSession(session))) {
                view.visibility = View.VISIBLE
            }
        }
    }

    private fun dismissInternalOnMain() {
        val view = curtainView ?: return
        runCatching { windowManager.removeViewImmediate(view) }
        curtainView = null
    }
}
