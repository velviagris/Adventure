package com.velviagris.adventure.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.abs

object RegionAreaFetcher {

    /**
     * 向 OSM Nominatim 接口请求逆向地理信息，并计算该区域的近似总面积 (平方公里)
     * @param zoom: 10 代表市级 (City)，12 代表区/县级 (District/County)，14 代表镇/街道 (Town/Suburb)
     */
    suspend fun fetchRegionTotalAreaKm2(lat: Double, lon: Double, zoom: Int): Double? {
        return withContext(Dispatchers.IO) {
            try {
                val urlString = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon&zoom=$zoom"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection

                // ⚠️ 极其重要：OSM 官方要求必须带 User-Agent，否则会返回 403 拒绝访问
                connection.setRequestProperty("User-Agent", "Adventure/1.0 (Android)")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)

                    // 获取边界框：[lat_min, lat_max, lon_min, lon_max]
                    val bbox = json.getJSONArray("boundingbox")
                    val latMin = bbox.getString(0).toDouble()
                    val latMax = bbox.getString(1).toDouble()
                    val lonMin = bbox.getString(2).toDouble()
                    val lonMax = bbox.getString(3).toDouble()

                    // 计算这个边界框的物理面积 (平方公里)
                    return@withContext calculateBBoxArea(latMin, latMax, lonMin, lonMax)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return@withContext null
        }
    }

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