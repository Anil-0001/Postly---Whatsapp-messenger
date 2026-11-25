package com.postly.app.service

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.postly.app.PostlyApplication
import com.postly.app.UnlockScreenActivity
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

            // Assign pending task to Accessibility Service and reset state
            PostlyAccessibilityService.pendingTask = schedule
            PostlyAccessibilityService.currentStep = AutomationStep.START

            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val isLocked = keyguardManager.isKeyguardLocked

            if (isLocked) {
                Log.d(TAG, "Screen is locked! Launching UnlockScreenActivity to turn screen on & dismiss keyguard...")
                val unlockIntent = Intent(applicationContext, UnlockScreenActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(unlockIntent)
                delay(2500) // Allow Accessibility Service time to swipe and enter PIN
            }

            // Launch targeted WhatsApp
            launchTargetedWhatsApp(schedule.recipientPhone, schedule.message, schedule.platform)

            // Keep service alive to allow accessibility service to finish typing & clicking
            delay(20000)
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

    private fun launchTargetedWhatsApp(phoneNumber: String, message: String, platform: String) {
        try {
            val cleanPhone = phoneNumber.replace("+", "").replace(" ", "").replace("-", "")
            val targetPkg = if (platform.contains("business", ignoreCase = true)) "com.whatsapp.w4b" else "com.whatsapp"

            val intent = if (cleanPhone.isNotEmpty()) {
                val encodedMsg = URLEncoder.encode(message, "UTF-8")
                val uri = Uri.parse("whatsapp://send?phone=$cleanPhone&text=$encodedMsg")
                Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            } else {
                packageManager.getLaunchIntentForPackage(targetPkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            }

            if (intent != null) {
                startActivity(intent)
                Log.d(TAG, "Targeted WhatsApp opened for: $cleanPhone")
            } else {
                Log.e(TAG, "WhatsApp is not installed on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch targeted WhatsApp", e)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "Postly:DispatchWakeLock"
        ).apply {
            acquire(45000)
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
