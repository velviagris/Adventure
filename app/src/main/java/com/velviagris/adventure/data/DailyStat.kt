package com.velviagris.adventure.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_stats")
data class DailyStat(
    @PrimaryKey
    @ColumnInfo(name = "date_string")
    val dateString: String, // 格式："2026-04-21"
    @ColumnInfo(name = "total_distance_km")
    var totalDistanceKm: Double =0.0, // 当天实际移动的距离
    @ColumnInfo(name = "new_grids_count")
    var newGridsCount: Int = 0, // 🌟 新增：当天发现了多少个全新网格
    @ColumnInfo(name = "is_tracking_active")
    var isTrackingActive: Boolean = false // 🌟 新增：当天是否有过任何定位/运行
)