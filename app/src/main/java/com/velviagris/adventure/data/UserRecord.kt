package com.velviagris.adventure.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_records")
data class UserRecord(
    @PrimaryKey val id: Int = 1, // 永远为 1，确保单行记录
    @ColumnInfo(name = "last_blurry_grid_id")
    var lastBlurryGridId: String = "",
    @ColumnInfo(name = "max_single_move_distance_km")
    var maxSingleMoveDistanceKm: Double = 0.0, // 时空跃迁
    @ColumnInfo(name = "check_in_streak")
    var checkInStreak: Int = 0,               // 恒心守望
    @ColumnInfo(name = "new_exp_streak")
    var newExpStreak: Int = 0,                // 开拓狂热
    @ColumnInfo(name = "no_new_exp_streak")
    var noNewExpStreak: Int = 0,              // 宅家隐士
    @ColumnInfo(name = "max_visit_count")
    var maxVisitCount: Int = 0,               // 故地重游
    @ColumnInfo(name = "max_altitude_meters")
    var maxAltitudeMeters: Double = 0.0,
    @ColumnInfo(name = "top_speed_kmh")
    var topSpeedKmh: Double = 0.0
)