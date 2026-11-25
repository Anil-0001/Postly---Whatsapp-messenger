package com.postly.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.postly.app.data.model.ScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {

    @Query("SELECT * FROM schedules WHERE status = :status ORDER BY scheduledTimeMillis ASC")
    fun getSchedulesByStatus(status: String): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules ORDER BY scheduledTimeMillis DESC")
    fun getAllSchedules(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE id = :id LIMIT 1")
    suspend fun getScheduleById(id: Long): ScheduleEntity?

    @Query("SELECT * FROM schedules WHERE status = 'PENDING' AND scheduledTimeMillis <= :thresholdMillis ORDER BY scheduledTimeMillis ASC")
    suspend fun getPendingDueSchedules(thresholdMillis: Long): List<ScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: ScheduleEntity): Long

    @Update
    suspend fun updateSchedule(schedule: ScheduleEntity)

    @Delete
    suspend fun deleteSchedule(schedule: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE status = 'PENDING'")
    suspend fun clearAllPending()

    @Query("UPDATE schedules SET status = 'DELETED' WHERE id = :id")
    suspend fun markDeleted(id: Long)

    @Query("UPDATE schedules SET status = 'DONE', executedAtMillis = :executedAt WHERE id = :id")
    suspend fun markDone(id: Long, executedAt: Long)

    @Query("UPDATE schedules SET status = 'FAILED', errorMessage = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)
}
