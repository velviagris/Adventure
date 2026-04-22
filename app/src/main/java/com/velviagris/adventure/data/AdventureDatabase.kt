package com.velviagris.adventure.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ExploredGrid::class, Achievement::class, DailyStat::class, RegionProgress::class],
    version = 6,
    exportSchema = false
)
abstract class AdventureDatabase : RoomDatabase() {

    abstract fun exploredGridDao(): ExploredGridDao
    abstract fun achievementDao(): AchievementDao
    abstract fun dailyStatDao(): DailyStatDao
    abstract fun regionProgressDao(): RegionProgressDao

    companion object {
        @Volatile
        private var INSTANCE: AdventureDatabase? = null

        fun getDatabase(context: Context): AdventureDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AdventureDatabase::class.java,
                    "adventure_database" // 数据库文件名
                )
                    // 🌟 允许破坏性迁移：数据库结构改变时自动重建表（适合开发阶段）
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}