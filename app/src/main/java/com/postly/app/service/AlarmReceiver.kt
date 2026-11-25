package com.postly.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.postly.app.MainActivity
import com.postly.app.PostlyApplication
import com.postly.app.data.model.ScheduleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER_SCHEDULE) return

        val scheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L)
        if (scheduleId == -1L) return

        Log.d(TAG, "Alarm received for schedule: $scheduleId")

        // 1. Force the physical screen to turn ON immediately from sleep/Doze
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val screenWakeLock = powerManager.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
                "Postly:AlarmScreenWake"
            )
            screenWakeLock.acquire(15000)
            Log.d(TAG, "Physical screen wake lock acquired with ACQUIRE_CAUSES_WAKEUP")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring screen wake lock", e)
        }

        // 2. Launch UnlockScreenActivity immediately to dismiss keyguard & turn screen on
        try {
            val unlockIntent = Intent(context, com.postly.app.UnlockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(unlockIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting UnlockScreenActivity from receiver", e)
        }

        // 3. Use goAsync to keep CPU awake while checking schedule and starting foreground service
        val pendingResult = goAsync()
        val app = context.applicationContext as PostlyApplication

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val schedule = app.database.scheduleDao().getScheduleById(scheduleId)
                if (schedule != null) {
                    if (schedule.askBeforeSending) {
                        showConfirmationNotification(context, schedule)
                    } else {
                        startDispatchService(context, schedule)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in AlarmReceiver onReceive", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun startDispatchService(context: Context, schedule: ScheduleEntity) {
        val serviceIntent = Intent(context, DispatchForegroundService::class.java).apply {
            putExtra(EXTRA_SCHEDULE_ID, schedule.id)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun showConfirmationNotification(context: Context, schedule: ScheduleEntity) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openIntent = Intent(context, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            context,
            schedule.id.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val confirmSendIntent = Intent(context, DispatchForegroundService::class.java).apply {
            putExtra(EXTRA_SCHEDULE_ID, schedule.id)
        }
        val pendingSend = PendingIntent.getService(
            context,
            (schedule.id + 100000).toInt(),
            confirmSendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val recipientDisplay = schedule.recipientName.ifBlank { schedule.recipientPhone }
        val notification = NotificationCompat.Builder(context, PostlyApplication.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Postly: Ready to send")
            .setContentText("Send to $recipientDisplay: \"${schedule.message.take(40)}\"")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingOpen)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_media_play, "Send Now", pendingSend)
            .build()

        notificationManager.notify(schedule.id.toInt(), notification)
    }

    companion object {
        const val ACTION_TRIGGER_SCHEDULE = "com.postly.app.ACTION_TRIGGER_SCHEDULE"
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
        private const val TAG = "AlarmReceiver"
    }
}
