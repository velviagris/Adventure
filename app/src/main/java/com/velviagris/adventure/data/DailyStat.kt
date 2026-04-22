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
//    var preciseGridsAdded: Int, // 当天新增高精网格数
//    var areaAddedKm2: Double // 当天新增面积
)