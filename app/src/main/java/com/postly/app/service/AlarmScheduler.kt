package com.postly.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.postly.app.PostlyApplication
import com.postly.app.data.model.RepeatRule
import com.postly.app.data.model.ScheduleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    fun scheduleMessage(context: Context, schedule: ScheduleEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_TRIGGER_SCHEDULE
            putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, schedule.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = schedule.scheduledTimeMillis

        try {
            val showIntent = Intent(context, com.postly.app.MainActivity::class.java)
            val pendingShow = PendingIntent.getActivity(
                context,
                schedule.id.toInt(),
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // setAlarmClock is guaranteed to wake CPU and screen from deep sleep/Doze on Android 12-14
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingShow)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            Log.d(TAG, "Scheduled AlarmClock for id: ${schedule.id} at $triggerTime")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for exact alarm", e)
        }
    }

    fun cancelSchedule(context: Context, scheduleId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_TRIGGER_SCHEDULE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            scheduleId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Cancelled alarm for id: $scheduleId")
        }
    }

    fun handleRecurringSchedule(context: Context, schedule: ScheduleEntity) {
        val rule = try {
            RepeatRule.valueOf(schedule.repeatRule)
        } catch (e: Exception) {
            RepeatRule.NO_REPEAT
        }

        if (rule == RepeatRule.NO_REPEAT) return

        val nextTime = calculateNextTrigger(schedule.scheduledTimeMillis, rule)

        CoroutineScope(Dispatchers.IO).launch {
            val db = (context.applicationContext as PostlyApplication).database
            val newSchedule = schedule.copy(
                id = 0,
                scheduledTimeMillis = nextTime,
                status = "PENDING",
                executedAtMillis = null,
                errorMessage = null
            )
            val newId = db.scheduleDao().insertSchedule(newSchedule)
            val insertedSchedule = newSchedule.copy(id = newId)
            scheduleMessage(context, insertedSchedule)
        }
    }

    private fun calculateNextTrigger(currentTime: Long, rule: RepeatRule): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = currentTime }
        when (rule) {
            RepeatRule.HOURLY -> cal.add(Calendar.HOUR_OF_DAY, 1)
            RepeatRule.DAILY -> cal.add(Calendar.DAY_OF_YEAR, 1)
            RepeatRule.WEEKLY -> cal.add(Calendar.WEEK_OF_YEAR, 1)
            RepeatRule.MONTHLY -> cal.add(Calendar.MONTH, 1)
            RepeatRule.YEARLY -> cal.add(Calendar.YEAR, 1)
            RepeatRule.WEEKDAYS -> {
                do {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                } while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            }
            RepeatRule.WEEKENDS -> {
                do {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                } while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY && cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY)
            }
            else -> cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
