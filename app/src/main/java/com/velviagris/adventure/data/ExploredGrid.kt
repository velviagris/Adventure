package com.velviagris.adventure.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "explored_grids")
data class ExploredGrid(
    // 核心：空间网格的唯一ID（根据坐标计算）
    @PrimaryKey
    @ColumnInfo(name = "grid_index")
    val gridIndex: String,

    // 精度级别：0 = 模糊 (区/村级大网格), 1 = 精准 (街道级小网格)
    @ColumnInfo(name = "accuracy_level")
    val accuracyLevel: Int,

    // 来源：0 = 真实轨迹自己走出来的, 1 = 照片相册导入的
    @ColumnInfo(name = "source_type")
    val sourceType: Int = 0,

    // 探索时间戳
    @ColumnInfo(name = "explore_time")
    val exploreTime: Long = System.currentTimeMillis()
)