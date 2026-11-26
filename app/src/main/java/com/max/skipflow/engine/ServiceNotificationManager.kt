package com.max.skipflow.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.max.skipflow.MainActivity
import com.max.skipflow.R
import com.max.skipflow.SkipFlowApplication
import java.util.Locale

/**
 * Manages SkipFlow Max's persistent service notification so the user can monitor live status
 * or trigger/cancel a 10-minute Smart Snooze directly from the notification shade.
 */
object ServiceNotificationManager {

    const val CHANNEL_ID = "skipflow_status_channel"
    const val NOTIFICATION_ID = 4201

    const val ACTION_SNOOZE_10M = "com.max.skipflow.ACTION_SNOOZE_10M"
    const val ACTION_RESUME_ENGINE = "com.max.skipflow.ACTION_RESUME_ENGINE"

    fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "حالة محرك SkipFlow Max",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "يعرض حالة محرك تخطي الإعلانات وخيار الإيقاف المؤقت السريع"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun formatCountdown(remainingMs: Long): String {
        val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    fun syncNotification(context: Context, isServiceRunning: Boolean) {
        val appContext = context.applicationContext
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!isServiceRunning) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(appContext)
        val container = (appContext as? SkipFlowApplication)?.container ?: return
        val repo = container.skipFlowRepository
        val settings = repo.settings.value
        val snoozeRemainingMs = repo.getSnoozeRemainingMs()

        val openAppIntent = PendingIntent.getActivity(
            appContext,
            100,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, body, actionLabel, actionName) = when {
            snoozeRemainingMs > 0L -> Quad(
                "SkipFlow Max • إيقاف مؤقت (${formatCountdown(snoozeRemainingMs)})",
                "تم تعليق التخطي مؤقتاً • سيعود تلقائياً عند انتهاء المؤقت",
                "استئناف الآن",
                ACTION_RESUME_ENGINE
            )

            settings.engineEnabled -> Quad(
                "SkipFlow Max • نشط ويحمي شاشتك",
                "تخطيات اليوم: ${repo.stats.value.skippedToday} • إجمالي الوقت المحفوظ جاهز",
                "إيقاف مؤقت 10 دقائق",
                ACTION_SNOOZE_10M
            )

            else -> Quad(
                "SkipFlow Max • متوقف حالياً",
                "المحرك متوقف يدوياً • اضغط للاستئناف الفوري",
                "تشغيل المحرك",
                ACTION_RESUME_ENGINE
            )
        }

        val actionPendingIntent = PendingIntent.getBroadcast(
            appContext,
            if (actionName == ACTION_SNOOZE_10M) 201 else 202,
            Intent(appContext, SnoozeActionReceiver::class.java).apply {
                action = actionName
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_skipflow_tile)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_skipflow_tile, actionLabel, actionPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private data class Quad(
        val first: String,
        val second: String,
        val third: String,
        val fourth: String
    )
}
