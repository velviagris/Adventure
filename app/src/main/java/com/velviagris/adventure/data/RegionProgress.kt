package com.velviagris.adventure.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "region_progress")
data class RegionProgress(
    @PrimaryKey
    @ColumnInfo(name = "region_id")
    val regionId: String, // 使用 OSM 返回的 place_id 作为唯一键
    @ColumnInfo(name = "region_name")
    val regionName: String,
    @ColumnInfo(name = "region_type")
    val regionType: Int, // 1=区/县, 2=市, 3=省/州, 4=国家
    @ColumnInfo(name = "address_type")
    val addressType: String,
    @ColumnInfo(name = "total_area_km2")
    val totalAreaKm2: Double,
    @ColumnInfo(name = "explored_area_km2")
    var exploredAreaKm2: Double = 0.0,
    @ColumnInfo(name = "first_visit_time")
    val firstVisitTime: Long = System.currentTimeMillis()
)