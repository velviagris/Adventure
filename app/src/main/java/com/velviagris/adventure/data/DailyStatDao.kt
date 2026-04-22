package com.velviagris.adventure.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyStatDao {
    @Query("SELECT * FROM daily_stats WHERE date_string = :date")
    suspend fun getDailyStat(date: String): DailyStat?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyStat(stat: DailyStat)

    // 🌟 这个 Flow 极其重要：用于给成就引擎和 UI 实时提供“历史总距离”
    @Query("SELECT SUM(total_distance_km) FROM daily_stats")
    fun getTotalDistanceFlow(): Flow<Double?>

    // 🌟 用于全库备份
    @Query("SELECT * FROM daily_stats")
    suspend fun getAllDailyStats(): List<DailyStat>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyStats(stats: List<DailyStat>)
}