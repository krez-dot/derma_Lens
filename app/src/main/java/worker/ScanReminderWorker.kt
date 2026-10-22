package com.dermalens.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dermalens.app.MainActivity

class ScanReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        sendScanReminderNotification()
        return Result.success()
    }

    private fun sendScanReminderNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scan Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminders to scan your skin regularly"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to open app when notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val messages = listOf(
            "Time to check your skin! 🔍 Open DermaLens for a quick scan.",
            "Your skin health matters! 💚 Scan today to track your progress.",
            "Don't forget your daily skin check! 📱 DermaLens is ready.",
            "Regular scans help track improvements! 📈 Scan now.",
            "Stay on top of your skin health! 🌟 Quick scan with DermaLens."
        )
        val randomMessage = messages.random()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // Status-bar icon: the logo's owl as a white silhouette (Android draws small icons from
            // their alpha only), tinted with the app's purple where the system allows colour.
            .setSmallIcon(com.dermalens.app.R.drawable.ic_stat_dermalens)
            .setColor(0xFF7C3AED.toInt())
            // The full-colour logo beside the message.
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(context.resources, com.dermalens.app.R.drawable.dermalens_logo))
            .setContentTitle("DermaLens Reminder 🌿")
            .setContentText(randomMessage)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "dermalens_scan_reminder"
        const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "scan_reminder_work"
    }
}