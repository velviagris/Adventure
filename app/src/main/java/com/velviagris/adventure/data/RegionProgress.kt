package com.velviagris.adventure.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "region_progress")
data class RegionProgress(
    @PrimaryKey
    val regionId: String, // 使用 OSM 返回的 place_id 作为唯一键
    val regionName: String,
    val regionType: Int, // 1=区/县, 2=市, 3=省/州, 4=国家
    val totalAreaKm2: Double,
    var exploredAreaKm2: Double = 0.0,
    val firstVisitTime: Long = System.currentTimeMillis()
)