package com.postly.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.postly.app.PostlyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Device rebooted. Rescheduling pending alarms...")
            val app = context.applicationContext as PostlyApplication
            CoroutineScope(Dispatchers.IO).launch {
                val pendingList = app.database.scheduleDao().getPendingDueSchedules(Long.MAX_VALUE)
                val now = System.currentTimeMillis()
                for (schedule in pendingList) {
                    if (schedule.scheduledTimeMillis > now) {
                        AlarmScheduler.scheduleMessage(context, schedule)
                    }
                }
            }
        }
    }
}
