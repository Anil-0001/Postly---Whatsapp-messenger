package com.postly.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class Platform(val displayName: String, val packageName: String) {
    WHATSAPP("Whatsapp", "com.whatsapp"),
    WHATSAPP_BUSINESS("Whatsapp business", "com.whatsapp.w4b"),
    TELEGRAM("Telegram", "org.telegram.messenger"),
    MESSENGER("Messenger", "com.facebook.orca"),
    INSTAGRAM("Instagram", "com.instagram.android"),
    EMAIL("Email", "com.google.android.gm"),
    CALL("Call", "")
}

enum class ScheduleStatus(val title: String) {
    PENDING("Pending"),
    DONE("Done"),
    DELETED("Deleted"),
    FAILED("Failed"),
    DRAFTS("Drafts")
}

enum class RepeatRule(val displayName: String) {
    NO_REPEAT("No Repeat"),
    HOURLY("Hourly"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    YEARLY("Yearly"),
    WEEKDAYS("Weekdays"),
    WEEKENDS("Weekends"),
    CUSTOM("Custom")
}

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val platform: String = Platform.WHATSAPP.name,
    val scheduleType: String = "Contacts / Groups",
    val recipientName: String = "",
    val recipientPhone: String = "",
    val title: String = "",
    val message: String = "",
    val scheduledTimeMillis: Long = System.currentTimeMillis(),
    val repeatRule: String = RepeatRule.NO_REPEAT.name,
    val askBeforeSending: Boolean = false,
    val status: String = ScheduleStatus.PENDING.name,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val executedAtMillis: Long? = null,
    val errorMessage: String? = null,
    val attachmentUri: String? = null,
    val attachmentType: String? = null,
    val attachmentName: String? = null,
    val attachmentSize: String? = null,
    val retryIntervalMinutes: Int = 2,
    val retryCount: Int = 0,
    val maxRetries: Int = 3
)
