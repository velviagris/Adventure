package com.velviagris.adventure.utils

import kotlin.math.*

object GridHelper {

    /**
     * Computes the grid index based on geographic coordinates and resolution level.
     * 基于地理坐标及分辨率等级计算网格索引。
     */
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

    /**
     * Derives the parent (coarse resolution) grid identifier from a high-precision grid index.
     * 从高精度网格索引推导其父级（粗略分辨率）网格标识符。
     */
    private fun getParentBlurryId(preciseIndex: String): String {
        val parts = preciseIndex.split("_")
        val x = parts[1].toInt()
        val y = parts[2].toInt()
        // Resolution factor: 2^(18-14) = 16. / 分辨率缩放因子：2^(18-14) = 16。
        return "14_${x / 16}_${y / 16}"
    }

    /**
     * Core Algorithm 1: Generates Global Fog GeoJSON with non-overlapping boolean subtractions.
     * 核心算法 1：生成包含非重叠布尔减法的全球迷雾 GeoJSON。
     */
    fun buildGlobalAdventureGeoJson(blurry: List<com.velviagris.adventure.data.ExploredGrid>, precise: List<com.velviagris.adventure.data.ExploredGrid>): String {
        val outerRing = "[[-180.0, -85.0], [180.0, -85.0], [180.0, 85.0], [-180.0, 85.0], [-180.0, -85.0]]"

        val blurrySet = blurry.map { it.gridIndex }.toSet()

        // Filter high-precision grids that lack a corresponding explored parent grid (Orphaned grids).
        // 筛选缺乏对应已探索父网格的高精度网格（孤立网格）。
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

    /**
     * Core Algorithm 2: Generates regional "Blurry Patches" with interior precise grid subtractions.
     * 核心算法 2：生成包含内部高精度网格扣除项的区域性“模糊补丁”。
     */
    fun buildBlurryPatchGeoJson(blurry: List<com.velviagris.adventure.data.ExploredGrid>, precise: List<com.velviagris.adventure.data.ExploredGrid>): String {
        if (blurry.isEmpty()) return """{"type": "FeatureCollection", "features": []}"""

        // Group high-precision grid entities by their respective parent coarse grid identifiers.
        // 将高精度网格实体按其对应的父级粗略网格标识符进行分组。
        val preciseByParent = precise.groupBy { getParentBlurryId(it.gridIndex) }

        val features = blurry.joinToString(",") { b ->
            // Exterior boundary: The coarse grid polygon (Counter-clockwise). / 外部边界：粗略网格多边形（逆时针）。
            val outerRing = getGridPolygon(b.gridIndex, clockwise = false)
            val outerStr = "[" + outerRing.joinToString(",") { "[${it[0]}, ${it[1]}]" } + "]"

            // Interior subtractions: Precise grids encapsulated within the coarse grid (Clockwise). / 内部扣除：封装在该粗略网格内的高精度网格（顺时针）。
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
     * Computes the geodetic area of a grid cell (Unit: Square Kilometers).
     * Calculates the physical area based on Earth's ellipsoid and Mercator projection inverse formulas.
     * The effective area decreases as latitude increases.
     * 计算网格单元的大地面积（单位：平方公里）。
     * 基于地球椭球体及墨卡托投影反解公式计算物理面积。
     * 有效面积随纬度增加而减小。
     */
    fun getGridAreaKm2(gridIndex: String): Double {
        val parts = gridIndex.split("_")
        if (parts.size != 3) return 0.0
        val zoom = parts[0].toInt()
        val y = parts[2].toInt()

        val n = 2.0.pow(zoom)
        // Derive latitude at the grid's northern boundary (Radians). / 推导网格北边界的纬度（弧度）。
        val latRad = atan(sinh(PI * (1.0 - 2.0 * y / n)))

        // Computation: Determines pixel resolution at current latitude based on Earth's equatorial radius (6378137m).
        // Calculations assume local square geometry (approximation error is negligible at micro-scales).
        // 计算：基于地球赤道半径（6378137m）确定当前纬度的像素分辨率。
        // 计算假设局部为正方形几何结构（微观尺度下近似误差可忽略）。
        val earthRadius = 6378137.0
        val tileLengthMeters = (cos(latRad) * 2 * PI * earthRadius) / n

        val areaSquareMeters = tileLengthMeters * tileLengthMeters
        return areaSquareMeters / 1_000_000.0 // Unit conversion to Square Kilometers. / 转换为平方公里。
    }

    private fun tile2Lon(x: Int, n: Double) = x / n * 360.0 - 180.0
    private fun tile2Lat(y: Int, n: Double) = (atan(sinh(PI * (1.0 - 2.0 * y / n)))) * 180.0 / PI
}