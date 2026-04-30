package com.velviagris.adventure.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "region_progress")
data class RegionProgress(
    // 使用 osm_id 作为绝对的物理主键
    @PrimaryKey
    @ColumnInfo(name = "osm_id")
    val osmId: Long,

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