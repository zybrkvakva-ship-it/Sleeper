package com.sleeper.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sleeper.app.data.local.dao.PendingSessionDao
import com.sleeper.app.data.local.dao.TaskDao
import com.sleeper.app.data.local.dao.UserStatsDao

/** v11 → v12: добавлены genesisNftMint и genesisNftNumber в user_stats */
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE user_stats ADD COLUMN genesisNftMint TEXT")
        database.execSQL("ALTER TABLE user_stats ADD COLUMN genesisNftNumber INTEGER")
    }
}

/**
 * v12 → v13: TaskEntity расширена для полной механики заданий.
 *   - description: подзаголовок задания
 *   - bonusPercent: % буста за выполнение (из сервера)
 *   - actionType:   тип действия (CHECKIN/SHARE/STORY/OPEN_URL/AUTO/REFERRAL)
 *   - actionUrl:    URL для OPEN_URL / SHARE / STORY
 *   - canComplete:  флаг доступности от сервера
 */
private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE tasks ADD COLUMN description TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE tasks ADD COLUMN bonusPercent REAL NOT NULL DEFAULT 0.0")
        database.execSQL("ALTER TABLE tasks ADD COLUMN actionType TEXT NOT NULL DEFAULT 'CHECKIN'")
        database.execSQL("ALTER TABLE tasks ADD COLUMN actionUrl TEXT")
        database.execSQL("ALTER TABLE tasks ADD COLUMN canComplete INTEGER NOT NULL DEFAULT 1")
    }
}

@Database(
    entities = [
        UserStatsEntity::class,
        TaskEntity::class,
        PendingSessionEntity::class
    ],
    version = 13,
    exportSchema = true
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
                    .addMigrations(MIGRATION_11_12, MIGRATION_12_13)
                    // fallback только для версий ниже 11 (pre-production)
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
