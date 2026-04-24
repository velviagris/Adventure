package com.velviagris.adventure.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExploredGridDao {
    // 插入网格，如果网格已存在（比如高精度覆盖低精度），则直接替换
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrid(grid: ExploredGrid)

    // 返回 Flow：Jetpack Compose 的绝配！只要数据库数据变了，UI 自动刷新
    @Query("SELECT * FROM explored_grids WHERE accuracy_level = :level")
    fun getGridsByAccuracyFlow(level: Int): Flow<List<ExploredGrid>>

    // 用于首页展示：获取已探索网格的总数量
    @Query("SELECT COUNT(*) FROM explored_grids")
    fun getTotalExploredCountFlow(): Flow<Int>

    // 查询所有数据
    @Query("SELECT * FROM explored_grids")
    suspend fun getAllGrids(): List<ExploredGrid>

    @Insert(onConflict = OnConflictStrategy.REPLACE) // 导入时如果有重复，直接覆盖
    suspend fun insertGrids(grids: List<ExploredGrid>)

    // 获取指定时间戳之后探索的网格 (用于每日总结)
    @Query("SELECT * FROM explored_grids WHERE explore_time >= :startTime AND explore_time <= :endTime")
    suspend fun getGridsExploredByTime(startTime: Long, endTime: Long): List<ExploredGrid>

    // 🌟 专供成就引擎使用：不再返回几十万个网格的 List，只返回一个数字！
    @Query("SELECT COUNT(*) FROM explored_grids WHERE accuracy_level = :level")
    fun getGridCountByAccuracyFlow(level: Int): Flow<Int>

    // 🌟 查询某个网格是否已经存在（用于判断是否为全新探索）
    @Query("SELECT * FROM explored_grids WHERE grid_index = :index")
    suspend fun getGrid(index: String): ExploredGrid?

    // 🌟 给某个网格的访问次数 +1
    @Query("UPDATE explored_grids SET visit_count = visit_count + 1 WHERE grid_index = :index")
    suspend fun incrementGridVisit(index: String)

    // 🌟 专供成就引擎：获取所有网格中，访问次数最高的那一个
    @Query("SELECT MAX(visit_count) FROM explored_grids")
    fun getMaxVisitCountFlow(): Flow<Int?>
}