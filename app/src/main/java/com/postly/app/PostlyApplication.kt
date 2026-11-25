package com.postly.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.postly.app.data.local.PostlyDatabase

class PostlyApplication : Application() {

    val database: PostlyDatabase by lazy { PostlyDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Postly Dispatch Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Runs while dispatching scheduled messages and turns screen on"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }

            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Schedule Alerts & Delivery",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts before sending and delivery confirmations"
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertsChannel)
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "postly_dispatch_service"
        const val CHANNEL_ALERTS = "postly_alerts"
    }
}
