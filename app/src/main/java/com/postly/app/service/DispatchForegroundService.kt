package com.postly.app.service

import android.app.KeyguardManager
import android.app.Service
import android.content.ClipData
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
import com.postly.app.data.model.ScheduleEntity
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
        activeServiceInstance = this
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

            // Check network connectivity before starting automation
            if (!com.postly.app.util.NetworkUtils.isNetworkAvailable(applicationContext)) {
                Log.w(TAG, "Device is offline / mobile data or Wi-Fi is off. Triggering retry for schedule: ${schedule.id}")
                RetryManager.handleFailure(applicationContext, schedule, "Network issue: Mobile data or Wi-Fi is off")
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
                // Wait for PostlyAccessibilityService to swipe and enter PIN (up to 4.5 seconds)
                for (i in 1..9) {
                    delay(500)
                    if (!keyguardManager.isKeyguardLocked) {
                        Log.d(TAG, "Keyguard unlocked successfully")
                        break
                    }
                }
            }

            // Launch targeted WhatsApp
            launchTargetedWhatsApp(schedule)

            // Keep service alive to allow accessibility service to finish typing & clicking
            delay(20000)
            releaseWakeLock()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val unlockIntent = Intent(this, UnlockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val fullScreenPendingIntent = android.app.PendingIntent.getActivity(
            this,
            2002,
            unlockIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, PostlyApplication.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Postly Automator")
            .setContentText("Dispatching scheduled WhatsApp message...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun launchTargetedWhatsApp(schedule: ScheduleEntity) {
        try {
            val cleanPhone = schedule.recipientPhone.replace("+", "").replace(" ", "").replace("-", "")
            val targetPkg = if (schedule.platform.contains("business", ignoreCase = true)) "com.whatsapp.w4b" else "com.whatsapp"

            val intent = if (!schedule.attachmentUri.isNullOrBlank()) {
                val mediaUri = Uri.parse(schedule.attachmentUri)
                val mimeType = when (schedule.attachmentType) {
                    "IMAGE" -> "image/*"
                    "VIDEO" -> "video/*"
                    "AUDIO" -> "audio/*"
                    else -> "*/*"
                }

                try {
                    grantUriPermission(targetPkg, mediaUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.w(TAG, "grantUriPermission failed: ${e.message}")
                }

                val fullPhone = if (cleanPhone.length == 10 && (cleanPhone.startsWith("6") || cleanPhone.startsWith("7") || cleanPhone.startsWith("8") || cleanPhone.startsWith("9"))) "91$cleanPhone" else cleanPhone

                Intent(Intent.ACTION_SEND).apply {
                    setPackage(targetPkg)
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, mediaUri)
                    clipData = ClipData.newUri(contentResolver, "Attachment", mediaUri)
                    if (schedule.message.isNotBlank()) {
                        putExtra(Intent.EXTRA_TEXT, schedule.message)
                    }
                    if (fullPhone.isNotEmpty()) {
                        putExtra("jid", "$fullPhone@s.whatsapp.net")
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            } else if (cleanPhone.isNotEmpty()) {
                val fullPhone = if (cleanPhone.length == 10 && (cleanPhone.startsWith("6") || cleanPhone.startsWith("7") || cleanPhone.startsWith("8") || cleanPhone.startsWith("9"))) "91$cleanPhone" else cleanPhone
                val encodedMsg = URLEncoder.encode(schedule.message, "UTF-8")
                val uri = Uri.parse("whatsapp://send?phone=$fullPhone&text=$encodedMsg")
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
                Log.d(TAG, "Targeted WhatsApp launched (with attachment: ${!schedule.attachmentUri.isNullOrBlank()})")
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
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
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

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        activeServiceInstance = null
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "DispatchFGService"
        var activeServiceInstance: DispatchForegroundService? = null

        fun finishAndRelease() {
            try {
                activeServiceInstance?.let { service ->
                    service.releaseWakeLock()
                    service.stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in finishAndRelease", e)
            }
        }
    }
}
