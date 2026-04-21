package com.velviagris.adventure.utils

import kotlin.math.*

object GridHelper {

    fun getGridIndex(lat: Double, lng: Double, isPrecise: Boolean): String {
        val zoom = if (isPrecise) 18 else 14
        val latRad = lat * PI / 180.0
        val n = 2.0.pow(zoom)
        val xTile = ((lng + 180.0) / 360.0 * n).toInt()
        val yTile = ((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt()
        return "${zoom}_${xTile}_${yTile}"
    }

    private fun getGridPolygon(gridIndex: String, clockwise: Boolean = false): List<List<Double>> {
        val parts = gridIndex.split("_")
        if (parts.size != 3) return emptyList()

        val zoom = parts[0].toInt()
        val x = parts[1].toInt()
        val y = parts[2].toInt()

        val n = 2.0.pow(zoom)
        val north = tile2Lat(y, n)
        val south = tile2Lat(y + 1, n)
        val west = tile2Lon(x, n)
        val east = tile2Lon(x + 1, n)

        return if (clockwise) {
            listOf(
                listOf(west, north), listOf(east, north),
                listOf(east, south), listOf(west, south),
                listOf(west, north)
            )
        } else {
            listOf(
                listOf(west, north), listOf(west, south),
                listOf(east, south), listOf(east, north),
                listOf(west, north)
            )
        }
    }

    // 辅助计算：通过高精度 ID 推算出它的父级模糊网格 ID
    private fun getParentBlurryId(preciseIndex: String): String {
        val parts = preciseIndex.split("_")
        val x = parts[1].toInt()
        val y = parts[2].toInt()
        // 倍数关系：2^(18-14) = 16
        return "14_${x / 16}_${y / 16}"
    }

    // 🌟 核心算法 1：生成【全球深灰迷雾】（只挖不重叠的洞）
    fun buildGlobalAdventureGeoJson(blurry: List<com.velviagris.adventure.data.ExploredGrid>, precise: List<com.velviagris.adventure.data.ExploredGrid>): String {
        val outerRing = "[[-180.0, -85.0], [180.0, -85.0], [180.0, 85.0], [-180.0, 85.0], [-180.0, -85.0]]"

        val blurrySet = blurry.map { it.gridIndex }.toSet()

        // 挑出那些“没有父级模糊网格”的孤儿高精度网格
        val orphanPrecise = precise.filter { p -> !blurrySet.contains(getParentBlurryId(p.gridIndex)) }

        val blurryHoles = blurry.map { b ->
            val poly = getGridPolygon(b.gridIndex, clockwise = true)
            "[" + poly.joinToString(",") { "[${it[0]}, ${it[1]}]" } + "]"
        }

        val preciseHoles = orphanPrecise.map { p ->
            val poly = getGridPolygon(p.gridIndex, clockwise = true)
            "[" + poly.joinToString(",") { "[${it[0]}, ${it[1]}]" } + "]"
        }

        val allHoles = (blurryHoles + preciseHoles).joinToString(",")
        val coordinates = if (allHoles.isNotEmpty()) "[$outerRing, $allHoles]" else "[$outerRing]"

        return """{"type": "FeatureCollection", "features": [{"type": "Feature", "geometry": {"type": "Polygon", "coordinates": $coordinates}}]}"""
    }

    // 🌟 核心算法 2：生成【带洞的浅灰模糊补丁】
    fun buildBlurryPatchGeoJson(blurry: List<com.velviagris.adventure.data.ExploredGrid>, precise: List<com.velviagris.adventure.data.ExploredGrid>): String {
        if (blurry.isEmpty()) return """{"type": "FeatureCollection", "features": []}"""

        // 把高精度网格按照它们的“模糊父级”分组
        val preciseByParent = precise.groupBy { getParentBlurryId(it.gridIndex) }

        val features = blurry.joinToString(",") { b ->
            // 外圈：模糊网格自己 (逆时针)
            val outerRing = getGridPolygon(b.gridIndex, clockwise = false)
            val outerStr = "[" + outerRing.joinToString(",") { "[${it[0]}, ${it[1]}]" } + "]"

            // 挖洞：属于这个模糊网格里面的高精度网格 (顺时针)
            val children = preciseByParent[b.gridIndex] ?: emptyList()
            val holesStr = children.joinToString(",") { p ->
                val innerRing = getGridPolygon(p.gridIndex, clockwise = true)
                "[" + innerRing.joinToString(",") { "[${it[0]}, ${it[1]}]" } + "]"
            }

            val coords = if (holesStr.isNotEmpty()) "$outerStr, $holesStr" else outerStr
            """{"type": "Feature", "properties": {}, "geometry": {"type": "Polygon", "coordinates": [$coords]}}"""
        }
        return """{"type": "FeatureCollection", "features": [$features]}"""
    }

    /**
     * 计算某个网格的真实物理面积（单位：平方公里）
     * 采用地球椭球体和墨卡托投影反解公式，纬度越高，网格实际物理面积越小。
     */
    fun getGridAreaKm2(gridIndex: String): Double {
        val parts = gridIndex.split("_")
        if (parts.size != 3) return 0.0
        val zoom = parts[0].toInt()
        val y = parts[2].toInt()

        val n = 2.0.pow(zoom)
        // 计算该网格顶部的纬度（弧度）
        val latRad = atan(sinh(PI * (1.0 - 2.0 * y / n)))

        // 核心公式：基于地球赤道半径(6378137m)，计算该纬度下的像素分辨率，进而求得网格边长
        // 这里简化为将网格视为正方形（在微小尺度下误差可忽略）
        val earthRadius = 6378137.0
        val tileLengthMeters = (cos(latRad) * 2 * PI * earthRadius) / n

        val areaSquareMeters = tileLengthMeters * tileLengthMeters
        return areaSquareMeters / 1_000_000.0 // 转换为平方公里 (km²)
    }

    private fun tile2Lon(x: Int, n: Double) = x / n * 360.0 - 180.0
    private fun tile2Lat(y: Int, n: Double) = (atan(sinh(PI * (1.0 - 2.0 * y / n)))) * 180.0 / PI
}