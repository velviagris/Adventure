package com.velviagris.adventure.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ExploredGrid::class, Achievement::class, DailyStat::class, RegionProgress::class, UserRecord::class],
    version = 10,
    exportSchema = false
)
abstract class AdventureDatabase : RoomDatabase() {

    abstract fun exploredGridDao(): ExploredGridDao
    abstract fun achievementDao(): AchievementDao
    abstract fun dailyStatDao(): DailyStatDao
    abstract fun regionProgressDao(): RegionProgressDao
    abstract fun userRecordDao(): UserRecordDao


    companion object {
        @Volatile
        private var INSTANCE: AdventureDatabase? = null

        /**
         * Returns a singleton instance of the database provider.
         * 返回数据库提供程序的单例实例。
         */
        fun getDatabase(context: Context): AdventureDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AdventureDatabase::class.java,
                    "adventure_database" // Persistence storage filename. / 持久化存储文件名。
                )
                    // Configures fallback behavior during schema version mismatch.
                    // 配置模式版本不匹配时的回退行为。
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}