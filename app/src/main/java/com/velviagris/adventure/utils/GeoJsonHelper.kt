package com.velviagris.adventure.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.*

object GeoJsonHelper {

    // 建议将 client 定义为单例，避免重复创建线程池
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS) // 连接超时：建议增加到 10s
        .readTimeout(10, TimeUnit.SECONDS)    // 读取超时
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)       // 开启失败自动重试
        .build()

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
        } catch (e: Exception) {
            return false
        }
    }

    private fun checkPolygon(lat: Double, lon: Double, rings: JSONArray): Boolean {
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
                (lon < (p2Lon - p1Lon) * (lat - p1Lat) / (p2Lat - p1Lat) + p1Lon)
            ) {
                intersectCount++
            }
        }
        return intersectCount % 2 != 0
    }

    // 1. 从本地文件系统读取缓存的 GeoJSON
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

    // 2. 下载并保存到本地文件
    suspend fun downloadAndCacheBoundary(
        context: Context,
        lat: Double,
        lon: Double,
        zoom: Int,
        level: String
    ): JSONObject? {
        return withContext(Dispatchers.IO) {
            val urlString = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon&zoom=$zoom&polygon_geojson=1"

            val request = Request.Builder()
                .url(urlString)
                .header("User-Agent", "Adventure/1.0") // Nominatim 必须包含合规的 UA
//                .header("Accept-Language", "en-US,en;q=0.9") // 明确语言偏好
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // 处理非 200 响应（如 403 被封禁或 429 请求太频繁）
                        return@withContext null
                    }

                    val responseBody = response.body?.string() ?: return@withContext null

                    // 写入缓存
                    val file = File(context.filesDir, "boundary_$level.json")
                    file.writeText(responseBody)

                    return@withContext JSONObject(responseBody)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    // 🌟 3. 核心统计方法：用精确算法替换 BBox 矩形算法
    fun calculateExplorationStats(
        blurry: List<com.velviagris.adventure.data.ExploredGrid>,
        precise: List<com.velviagris.adventure.data.ExploredGrid>,
        geoJson: JSONObject
    ): Pair<Double, Double> {

        // 🌟 核心修改：计算真实的几何多边形面积
        val totalArea = calculateExactGeoJsonAreaKm2(geoJson)
        if (totalArea <= 0.0) return Pair(0.0, 1.0) // 容错处理

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

    // =========================================================
    // 🌟 GIS 纯正血统：地球球面多边形面积计算引擎
    // =========================================================

    private fun calculateExactGeoJsonAreaKm2(geoJson: JSONObject): Double {
        val geometry = geoJson.optJSONObject("geojson") ?: return 0.0
        val type = geometry.optString("type")
        val coordinates = geometry.optJSONArray("coordinates") ?: return 0.0

        var totalArea = 0.0
        try {
            when (type) {
                "Polygon" -> {
                    totalArea += calculateSinglePolygonArea(coordinates)
                }
                "MultiPolygon" -> {
                    for (i in 0 until coordinates.length()) {
                        totalArea += calculateSinglePolygonArea(coordinates.getJSONArray(i))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return totalArea
    }

    private fun calculateSinglePolygonArea(polygonArray: JSONArray): Double {
        if (polygonArray.length() == 0) return 0.0

        // 容错设计：OSM 的 GeoJSON 偶尔外圈和内圈顺序是乱的。
        // 我们遍历所有的闭合环，把最大的那个环作为外边框（陆地），其余较小的环全部视为洞穴（内陆湖泊/飞地）并减去面积。
        var maxArea = 0.0
        var totalHoleArea = 0.0

        for (i in 0 until polygonArray.length()) {
            val ringArea = abs(calculateRingArea(polygonArray.getJSONArray(i)))
            if (ringArea > maxArea) {
                if (maxArea > 0) totalHoleArea += maxArea // 之前的 max 其实是个洞穴
                maxArea = ringArea
            } else {
                totalHoleArea += ringArea
            }
        }
        return maxArea - totalHoleArea
    }

    /**
     * 基于地球半径域的线积分求积公式 (Spherical Polygon Area Line Integral)
     */
    private fun calculateRingArea(ring: JSONArray): Double {
        var area = 0.0
        val r = 6371.0088 // WGS84 地球平均半径 (km)
        val n = ring.length()
        if (n < 3) return 0.0

        for (i in 0 until n - 1) {
            val p1 = ring.getJSONArray(i)
            val p2 = ring.getJSONArray(i + 1)

            val lon1 = Math.toRadians(p1.getDouble(0))
            val lat1 = Math.toRadians(p1.getDouble(1))
            val lon2 = Math.toRadians(p2.getDouble(0))
            val lat2 = Math.toRadians(p2.getDouble(1))

            // 修正跨越国际日期变更线（180度经线）的边界
            var dLon = lon2 - lon1
            if (dLon > PI) dLon -= 2 * PI
            if (dLon < -PI) dLon += 2 * PI

            area += dLon * (sin(lat1) + sin(lat2)) / 2.0
        }
        return area * r * r
    }

    // 辅助方法：获取网格中心点坐标
    private fun getGridCenter(gridIndex: String): Pair<Double, Double> {
        val parts = gridIndex.split("_")
        val zoom = parts[0].toInt()
        val x = parts[1].toDouble() + 0.5
        val y = parts[2].toDouble() + 0.5
        val n = 2.0.pow(zoom)
        val lon = x / n * 360.0 - 180.0
        val lat = atan(sinh(PI * (1.0 - 2.0 * y / n))) * 180.0 / PI
        return Pair(lat, lon)
    }
}