package com.velviagris.adventure.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.abs

object RegionAreaFetcher {

    // 基于地球半径 (6371km) 的经纬度矩形面积微积分推导
    private fun calculateBBoxArea(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double): Double {
        val r = 6371.0 // 地球平均半径 km
        val latCenter = Math.toRadians((latMin + latMax) / 2.0)

        val dLat = Math.toRadians(abs(latMax - latMin))
        val heightKm = r * dLat

        val dLon = Math.toRadians(abs(lonMax - lonMin))
        // 纬度越高，经度之间的物理距离越短，所以要乘以 cos(中心纬度)
        val widthKm = r * dLon * cos(latCenter)

        return heightKm * widthKm
    }
}