package com.velviagris.adventure.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_records")
data class UserRecord(
    @PrimaryKey val id: Int = 1, // 永远为 1，确保单行记录
    @ColumnInfo(name = "last_blurry_grid_id")
    var lastBlurryGridId: String = "", // 🌟 用于判定是否“跨越边界”的核心状态
    @ColumnInfo(name = "max_single_move_distance_km")
    var maxSingleMoveDistanceKm: Double = 0.0, // 🌟 两次定位间的最大瞬间移动距离
    @ColumnInfo(name = "max_altitude_meters")
    var maxAltitudeMeters: Double = 0.0, // 未来可用于“登峰造极”成就
    @ColumnInfo(name = "top_speed_kmh")
    var topSpeedKmh: Double = 0.0 // 未来可用于“速度狂飙”成就
)