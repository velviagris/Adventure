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
    @Query("UPDATE region_progress SET region_name = :regionName, explored_area_km2 = :exploredArea, total_area_km2 = :totalArea WHERE osm_id = :osmId")
    suspend fun updateExploredArea(osmId: Long, regionName: String, totalArea: Double, exploredArea: Double)

    // 🌟 专供成就引擎使用：极其轻量级的 COUNT 查询
    @Query("SELECT COUNT(DISTINCT region_name) FROM region_progress WHERE region_type = :type")
    fun getRegionCountFlow(type: Int): Flow<Int>

    // 🌟 用于全库备份
    @Query("SELECT * FROM region_progress")
    suspend fun getAllRegions(): List<RegionProgress>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegions(regions: List<RegionProgress>)
}