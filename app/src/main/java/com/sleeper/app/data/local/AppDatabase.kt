package com.sleeper.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sleeper.app.data.local.dao.PendingSessionDao
import com.sleeper.app.data.local.dao.TaskDao
import com.sleeper.app.data.local.dao.UserStatsDao

@Database(
    entities = [
        UserStatsEntity::class,
        TaskEntity::class,
        PendingSessionEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userStatsDao(): UserStatsDao
    abstract fun taskDao(): TaskDao
    abstract fun pendingSessionDao(): PendingSessionDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sleeper_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
