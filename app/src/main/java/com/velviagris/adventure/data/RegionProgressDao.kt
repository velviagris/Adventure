package com.velviagris.adventure.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RegionProgressDao {
    // 插入新区域（如果 place_id 已经存在，则忽略，保留首次访问时间）
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRegionIfNotExists(region: RegionProgress)

    // 更新某个区域的已探索面积
    @Query("UPDATE region_progress SET exploredAreaKm2 = :exploredArea WHERE regionId = :regionId")
    suspend fun updateExploredArea(regionId: String, exploredArea: Double)

    // 🌟 专供成就引擎使用：极其轻量级的 COUNT 查询
    @Query("SELECT COUNT(*) FROM region_progress WHERE regionType = :type")
    fun getRegionCountFlow(type: Int): Flow<Int>
}