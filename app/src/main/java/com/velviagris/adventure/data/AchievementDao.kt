package com.velviagris.adventure.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {
    // 按照获得时间从新到旧排列，Flow 会自动监听变化
    @Query("SELECT * FROM achievements ORDER BY earned_time DESC")
    fun getAllAchievementsFlow(): Flow<List<Achievement>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAchievement(achievement: Achievement)
}