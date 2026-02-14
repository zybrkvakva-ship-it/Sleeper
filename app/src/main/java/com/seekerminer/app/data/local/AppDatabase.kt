package com.seekerminer.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.seekerminer.app.data.local.dao.PendingSessionDao
import com.seekerminer.app.data.local.dao.TaskDao
import com.seekerminer.app.data.local.dao.UpgradeDao
import com.seekerminer.app.data.local.dao.UserStatsDao

@Database(
    entities = [
        UserStatsEntity::class,
        UpgradeEntity::class,
        TaskEntity::class,
        PendingSessionEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userStatsDao(): UserStatsDao
    abstract fun upgradeDao(): UpgradeDao
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
                    "seekerminer_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
