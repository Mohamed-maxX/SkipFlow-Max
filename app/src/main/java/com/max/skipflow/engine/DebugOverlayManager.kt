package com.max.skipflow.engine

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

object DebugStatusFormatter {

    fun shortActivityName(rawClassName: String?, fallbackPackage: String?): String {
        val candidate = rawClassName?.takeIf { it.isNotBlank() } ?: fallbackPackage.orEmpty()
        if (candidate.isBlank()) return "-"
        val shortName = candidate.substringAfterLast('.')
        return if (shortName.length > 26) shortName.take(25) + "…" else shortName
    }

    fun formatLine(
        activityShortName: String,
        isAdScreen: Boolean,
        screenshotStatus: String,
        detail: String
    ): String {
        val adLabel = if (isAdScreen) "نعم" else "لا"
        return "$activityShortName | إعلان: $adLabel | لقطة: $screenshotStatus | $detail"
    }
}

/**
 * Non-touchable (`FLAG_NOT_TOUCHABLE`) semi-transparent debug bar shown at top-center
 * when `SYSTEM_ALERT_WINDOW` is granted and the Settings toggle is enabled.
 */
class DebugOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: TextView? = null
    private var lastRenderedLine: String? = null

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    fun updateStatus(
        enabled: Boolean,
        activityName: String?,
        packageName: String?,
        isAdScreen: Boolean,
        screenshotStatus: String,
        detail: String
    ) {
        if (!enabled || !canDrawOverlay()) {
            hide()
            return
        }

        val line = DebugStatusFormatter.formatLine(
            activityShortName = DebugStatusFormatter.shortActivityName(activityName, packageName),
            isAdScreen = isAdScreen,
            screenshotStatus = screenshotStatus,
            detail = detail
        )

        if (line == lastRenderedLine && overlayView != null) return
        lastRenderedLine = line

        mainHandler.post {
            showOrUpdateOnMain(line)
        }
    }

    fun hide() {
        lastRenderedLine = null
        mainHandler.post {
            val view = overlayView ?: return@post
            runCatching { windowManager.removeViewImmediate(view) }
            overlayView = null
        }
    }

    private fun showOrUpdateOnMain(line: String) {
        val existing = overlayView
        if (existing != null) {
            existing.text = line
            return
        }

        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val padH = (12f * density).toInt()
        val padV = (5f * density).toInt()
        val maxW = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()

        val pill = TextView(context).apply {
            text = line
            setTextColor(Color.parseColor("#F4F7FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = maxW
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(Color.parseColor("#BF0D131F"))
                setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#9900E5FF"))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (28f * density).toInt()
        }

        runCatching {
            windowManager.addView(pill, params)
            overlayView = pill
        }
    }
}
