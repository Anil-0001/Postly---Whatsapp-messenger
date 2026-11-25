package com.postly.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.postly.app.PostlyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

class DispatchForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scheduleId = intent?.getLongExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, -1L) ?: -1L
        if (scheduleId == -1L) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        acquireWakeLock()

        serviceScope.launch {
            val app = applicationContext as PostlyApplication
            val schedule = app.database.scheduleDao().getScheduleById(scheduleId)

            if (schedule == null) {
                releaseWakeLock()
                stopSelf()
                return@launch
            }

            // Assign pending task to Accessibility Service
            PostlyAccessibilityService.pendingTask = schedule

            // Launch WhatsApp
            launchWhatsApp(schedule.recipientPhone, schedule.message)

            // Keep service alive momentarily to let accessibility service complete
            delay(15000)
            releaseWakeLock()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, PostlyApplication.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Postly Automator")
            .setContentText("Dispatching scheduled WhatsApp message...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun launchWhatsApp(phoneNumber: String, message: String) {
        try {
            val cleanPhone = phoneNumber.replace("+", "").replace(" ", "").replace("-", "")
            val intent = if (cleanPhone.isNotEmpty()) {
                val encodedMsg = URLEncoder.encode(message, "UTF-8")
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMsg")
                Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            } else {
                packageManager.getLaunchIntentForPackage("com.whatsapp")?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            }

            if (intent != null) {
                startActivity(intent)
                Log.d(TAG, "Launched WhatsApp successfully")
            } else {
                Log.e(TAG, "WhatsApp is not installed on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch WhatsApp", e)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Postly:DispatchWakeLock"
        ).apply {
            acquire(30000)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "DispatchFGService"
    }
}
