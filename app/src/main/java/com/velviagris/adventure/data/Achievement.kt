package com.velviagris.adventure.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "achievements")
data class Achievement(
    // 成就的唯一ID，例如 "explorer_area_10"
    @PrimaryKey
    val id: String,
    // 成就名称，如 "初级探险家"
    @ColumnInfo(name = "title")
    val title: String,
    // 获得原因/描述，如 "在全球累计探索面积达到 10 km²"
    @ColumnInfo(name = "description")
    val description: String,
    // 成就等级：1=木, 2=铜, 3=银 4=金, 5=钻
    @ColumnInfo(name = "level")
    val level: Int,
    // 预留的图标名称，以后可以根据这个名字加载本地 drawable 或网络图片
    @ColumnInfo(name = "icon_res")
    val iconRes: String,
    // 获得时间戳
    @ColumnInfo(name = "earned_time")
    val earnedTime: Long = System.currentTimeMillis()
)