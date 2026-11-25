package com.postly.app.service

import android.content.Context
import android.util.Log
import com.postly.app.PostlyApplication
import com.postly.app.data.model.ScheduleEntity
import com.postly.app.data.model.ScheduleStatus
import java.text.SimpleDateFormat
import java.util.*

object RetryManager {
    private const val TAG = "RetryManager"

    suspend fun handleFailure(context: Context, schedule: ScheduleEntity, reason: String) {
        val app = context.applicationContext as PostlyApplication
        val db = app.database

        val current = db.scheduleDao().getScheduleById(schedule.id) ?: schedule
        val retryInterval = current.retryIntervalMinutes
        val currentRetry = current.retryCount + 1

        if (retryInterval > 0 && currentRetry <= current.maxRetries) {
            // Keep status PENDING and schedule next retry
            val cal = Calendar.getInstance().apply {
                add(Calendar.MINUTE, retryInterval)
                set(Calendar.SECOND, 30) // strictly at 30 seconds
                set(Calendar.MILLISECOND, 0)
            }
            val nextRetryTime = cal.timeInMillis
            val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(nextRetryTime))

            val updatedSchedule = current.copy(
                retryCount = currentRetry,
                status = ScheduleStatus.PENDING.name,
                scheduledTimeMillis = nextRetryTime,
                errorMessage = "Attempt $currentRetry/${current.maxRetries} failed: $reason (Next retry at $timeStr)"
            )
            db.scheduleDao().updateSchedule(updatedSchedule)
            AlarmScheduler.scheduleMessage(context, updatedSchedule)
            Log.d(TAG, "Scheduled retry #$currentRetry for schedule ${current.id} at $nextRetryTime ($reason)")
        } else {
            // All retry attempts exhausted or retry disabled -> Mark as FAILED
            val finalReason = if (current.retryIntervalMinutes > 0) {
                "Failed after ${current.maxRetries} attempts: $reason"
            } else {
                "Failed: $reason"
            }
            val failedSchedule = current.copy(
                status = ScheduleStatus.FAILED.name,
                errorMessage = finalReason,
                executedAtMillis = System.currentTimeMillis()
            )
            db.scheduleDao().updateSchedule(failedSchedule)
            Log.d(TAG, "Marked schedule ${current.id} as FAILED: $finalReason")
        }
    }
}
