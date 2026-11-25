package com.postly.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.postly.app.data.model.ScheduleEntity

@Database(entities = [ScheduleEntity::class], version = 2, exportSchema = false)
abstract class PostlyDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        @Volatile
        private var INSTANCE: PostlyDatabase? = null

        fun getDatabase(context: Context): PostlyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PostlyDatabase::class.java,
                    "postly_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
