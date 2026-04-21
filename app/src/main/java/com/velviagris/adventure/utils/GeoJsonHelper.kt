package com.velviagris.adventure.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

object GeoJsonHelper {

    // 存储本地下载的边界数据（实际开发建议存入 Room 或文件）
    private var cachedCityGeoJson: JSONObject? = null
    private var cachedCountryGeoJson: JSONObject? = null

    /**
     * 下载行政区划的 GeoJSON 边界
     */
    suspend fun downloadBoundary(lat: Double, lon: Double, zoom: Int): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                // 🌟 关键参数：polygon_geojson=1
                val urlString = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon&zoom=$zoom&polygon_geojson=1"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Adventure/1.0")

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    return@withContext JSONObject(response)
                }
            } catch (e: Exception) { e.printStackTrace() }
            null
        }
    }

    /**
     * 判断一个经纬度点是否在 GeoJSON 多边形内部 (Ray-casting Algorithm)
     */
    fun isPointInPolygon(lat: Double, lon: Double, geoJson: JSONObject): Boolean {
        try {
            val geometry = geoJson.optJSONObject("geojson") ?: return false
            val type = geometry.getString("type")
            val coordinates = geometry.getJSONArray("coordinates")

            return when (type) {
                "Polygon" -> checkPolygon(lat, lon, coordinates)
                "MultiPolygon" -> {
                    for (i in 0 until coordinates.length()) {
                        if (checkPolygon(lat, lon, coordinates.getJSONArray(i))) return true
                    }
                    false
                }
                else -> false
            }
        } catch (e: Exception) { return false }
    }

    private fun checkPolygon(lat: Double, lon: Double, rings: JSONArray): Boolean {
        // 通常第一个 ring 是外圈
        val ring = rings.getJSONArray(0)
        var intersectCount = 0
        for (i in 0 until ring.length() - 1) {
            val p1 = ring.getJSONArray(i)
            val p2 = ring.getJSONArray(i + 1)
            val p1Lon = p1.getDouble(0)
            val p1Lat = p1.getDouble(1)
            val p2Lon = p2.getDouble(0)
            val p2Lat = p2.getDouble(1)

            if (((p1Lat > lat) != (p2Lat > lat)) &&
                (lon < (p2Lon - p1Lon) * (lat - p1Lat) / (p2Lat - p1Lat) + p1Lon)) {
                intersectCount++
            }
        }
        return intersectCount % 2 != 0
    }

    // 🌟 1. 从本地文件系统读取缓存的 GeoJSON
    suspend fun getCachedBoundary(context: Context, level: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, "boundary_$level.json")
                if (file.exists()) {
                    JSONObject(file.readText())
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    // 🌟 2. 下载并保存到本地文件
    suspend fun downloadAndCacheBoundary(context: Context, lat: Double, lon: Double, zoom: Int, level: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val urlString = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon&zoom=$zoom&polygon_geojson=1"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Adventure/1.0")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }

                    // 将下载的纯文本字符串保存到手机内部存储中
                    val file = File(context.filesDir, "boundary_$level.json")
                    file.writeText(response)

                    return@withContext JSONObject(response)
                }
            } catch (e: Exception) { e.printStackTrace() }
            null
        }
    }

    // 🌟 3. 新增计算探索进度的核心方法
    fun calculateExplorationStats(
        blurry: List<com.velviagris.adventure.data.ExploredGrid>,
        precise: List<com.velviagris.adventure.data.ExploredGrid>,
        geoJson: JSONObject
    ): Pair<Double, Double> {
        val bbox = geoJson.optJSONArray("boundingbox") ?: return Pair(0.0, 1.0)
        val totalArea = calculateBBoxArea(
            bbox.getString(0).toDouble(), bbox.getString(1).toDouble(),
            bbox.getString(2).toDouble(), bbox.getString(3).toDouble()
        )

        var exploredInRegion = 0.0
        val blurrySet = blurry.map { it.gridIndex }.toSet()

        blurry.forEach { grid ->
            val coords = getGridCenter(grid.gridIndex)
            if (isPointInPolygon(coords.first, coords.second, geoJson)) {
                exploredInRegion += GridHelper.getGridAreaKm2(grid.gridIndex)
            }
        }

        precise.forEach { grid ->
            val parts = grid.gridIndex.split("_")
            val parentId = "14_${parts[1].toInt() / 16}_${parts[2].toInt() / 16}"
            if (!blurrySet.contains(parentId)) {
                val coords = getGridCenter(grid.gridIndex)
                if (isPointInPolygon(coords.first, coords.second, geoJson)) {
                    exploredInRegion += GridHelper.getGridAreaKm2(grid.gridIndex)
                }
            }
        }

        return Pair(exploredInRegion, totalArea)
    }

    // 辅助方法：获取网格中心点坐标
    private fun getGridCenter(gridIndex: String): Pair<Double, Double> {
        val parts = gridIndex.split("_")
        val zoom = parts[0].toInt()
        val x = parts[1].toDouble() + 0.5 // 取中心
        val y = parts[2].toDouble() + 0.5
        val n = 2.0.pow(zoom)
        val lon = x / n * 360.0 - 180.0
        val lat = atan(sinh(PI * (1.0 - 2.0 * y / n))) * 180.0 / PI
        return Pair(lat, lon)
    }

    // 复用之前的 BBox 面积计算逻辑
    private fun calculateBBoxArea(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double): Double {
        val r = 6371.0
        val latCenter = Math.toRadians((latMin + latMax) / 2.0)
        val height = r * Math.toRadians(abs(latMax - latMin))
        val width = r * Math.toRadians(abs(lonMax - lonMin)) * cos(latCenter)
        return height * width
    }
}