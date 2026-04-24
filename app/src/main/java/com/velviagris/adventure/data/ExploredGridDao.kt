package com.velviagris.adventure.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExploredGridDao {
    /**
     * Persists a grid entity into the local database. 
     * Replaces the existing record if a primary key conflict occurs.
     * 将网格实体持久化到本地数据库。若发生主键冲突，则替换现有记录。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrid(grid: ExploredGrid)

    /**
     * Retrieves a reactive stream of grid entities filtered by their resolution level.
     * Observes database changes and facilitates automatic UI synchronization.
     * 获取按分辨率等级过滤的网格实体响应式流。观察数据库变更并支持 UI 自动同步。
     */
    @Query("SELECT * FROM explored_grids WHERE accuracy_level = :level")
    fun getGridsByAccuracyFlow(level: Int): Flow<List<ExploredGrid>>

    /**
     * Monitors the total count of explored grid entities.
     * 监控已探索网格实体的总计数。
     */
    @Query("SELECT COUNT(*) FROM explored_grids")
    fun getTotalExploredCountFlow(): Flow<Int>

    /**
     * Fetches a comprehensive list of all stored grid entities.
     * 获取所有已存储网格实体的完整列表。
     */
    @Query("SELECT * FROM explored_grids")
    suspend fun getAllGrids(): List<ExploredGrid>

    /**
     * Batch inserts a collection of grid entities. Replaces existing records on conflict.
     * 批量插入网格实体集合。发生冲突时替换现有记录。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrids(grids: List<ExploredGrid>)

    /**
     * Retrieves grid entities discovered within a specified temporal range.
     * Primarily used for generating periodic summaries.
     * 获取在指定时间范围内发现的网格实体。主要用于生成周期性总结。
     */
    @Query("SELECT * FROM explored_grids WHERE explore_time >= :startTime AND explore_time <= :endTime")
    suspend fun getGridsExploredByTime(startTime: Long, endTime: Long): List<ExploredGrid>

    /**
     * Monitors the total count of grid entities for a specific resolution level.
     * Optimized for achievement evaluation logic.
     * 监控特定分辨率等级的网格实体总计数。针对成就评估逻辑进行了优化。
     */
    @Query("SELECT COUNT(*) FROM explored_grids WHERE accuracy_level = :level")
    fun getGridCountByAccuracyFlow(level: Int): Flow<Int>

    /**
     * Verifies the existence of a grid entity by its index identifier.
     * Provides determination for initial exploration detection.
     * 通过索引标识符验证网格实体是否存在。为初始探索检测提供判定。
     */
    @Query("SELECT * FROM explored_grids WHERE grid_index = :index")
    suspend fun getGrid(index: String): ExploredGrid?

    /**
     * Atomically increments the revisit frequency for a specific grid entity.
     * 原子递增特定网格实体的重访频次。
     */
    @Query("UPDATE explored_grids SET visit_count = visit_count + 1 WHERE grid_index = :index")
    suspend fun incrementGridVisit(index: String)

    /**
     * Monitors the maximum revisit frequency across all grid entities.
     * Required for peak-value metric evaluation in the achievement engine.
     * 监控所有网格实体中的最大重访频次。成就引擎评估峰值指标所需。
     */
    @Query("SELECT MAX(visit_count) FROM explored_grids")
    fun getMaxVisitCountFlow(): Flow<Int?>
}