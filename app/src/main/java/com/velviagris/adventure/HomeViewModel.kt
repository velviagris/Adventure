package com.velviagris.adventure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.velviagris.adventure.data.*
import com.velviagris.adventure.utils.AppPreferences
import com.velviagris.adventure.utils.GridHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

class HomeViewModel(
    private val dao: ExploredGridDao,
    private val prefs: AppPreferences,
    private val achievementDao: AchievementDao,
    private val dailyStatDao: DailyStatDao,
    private val regionProgressDao: RegionProgressDao
) : ViewModel() {

    val isTrackingEnabled = prefs.isTrackingEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isPreciseMode = prefs.isPreciseMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val blurryGrids: StateFlow<List<ExploredGrid>> = dao.getGridsByAccuracyFlow(0)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val preciseGrids: StateFlow<List<ExploredGrid>> = dao.getGridsByAccuracyFlow(1)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val exploredAreaKm2: StateFlow<Double> = combine(dao.getGridsByAccuracyFlow(0), dao.getGridsByAccuracyFlow(1)) { blurry, precise ->
        val blurrySet = blurry.map { it.gridIndex }.toSet()
        val orphanPrecise = precise.filter { p ->
            val parentId = "14_${p.gridIndex.split("_")[1].toInt() / 16}_${p.gridIndex.split("_")[2].toInt() / 16}"
            !blurrySet.contains(parentId)
        }
        val blurryArea = blurry.sumOf { GridHelper.getGridAreaKm2(it.gridIndex) }
        val preciseArea = orphanPrecise.sumOf { GridHelper.getGridAreaKm2(it.gridIndex) }
        blurryArea + preciseArea
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // 🌟 新增：将距离、城市数、国家数暴露为 StateFlow，供成就页显示进度
    val totalDistanceKm: StateFlow<Double> = dailyStatDao.getTotalDistanceFlow()
        .map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val cityCount: StateFlow<Int> = regionProgressDao.getRegionCountFlow(2)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val countryCount: StateFlow<Int> = regionProgressDao.getRegionCountFlow(4)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun toggleTracking(enabled: Boolean) = viewModelScope.launch { prefs.setTrackingEnabled(enabled) }
    fun setPreciseMode(enabled: Boolean) = viewModelScope.launch { prefs.setPreciseMode(enabled) }

    fun recordRegionVisit(json: JSONObject, type: Int, name: String, currentExploredArea: Double) {
        viewModelScope.launch {
            val addressObj = json.optJSONObject("address")
            val state = addressObj?.optString("state") ?: "UnknownState"
            val country = addressObj?.optString("country") ?: "UnknownCountry"

            // 提取 OSM 真实的地理实体类型，如果没找到默认给个 "region"
            val addressType = json.optString("addresstype", "region")

            val stableRegionId = "${type}_${name}_${state}_${country}"

            val stats = com.velviagris.adventure.utils.GeoJsonHelper.calculateExplorationStats(emptyList(), emptyList(), json)
            val totalArea = stats.second

            // 存入真实类型
            val region = RegionProgress(
                regionId = stableRegionId,
                regionName = name,
                regionType = type,
                addressType = addressType,
                totalAreaKm2 = totalArea,
                exploredAreaKm2 = currentExploredArea
            )
            regionProgressDao.insertRegionIfNotExists(region)
            regionProgressDao.updateExploredArea(stableRegionId, currentExploredArea)
        }
    }

    private val unlockedAchievementIds = achievementDao.getAllAchievementsFlow()
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        startAchievementEngine()
    }

    private fun startAchievementEngine() {
        viewModelScope.launch {
            // 🌟 修复：由于 combine 最多支持合并 5 个流，我们将其分为两组，确保网格数量也被监听！
            val statsFlow1 = combine(exploredAreaKm2, totalDistanceKm, cityCount) { a, d, c -> Triple(a, d, c) }
            val statsFlow2 = combine(countryCount, preciseGrids, blurryGrids) { c, p, b -> Triple(c, p.size, b.size) }

            combine(statsFlow1, statsFlow2) { t1, t2 ->
                val area = t1.first
                val distance = t1.second
                val cities = t1.third
                val countries = t2.first
                val preciseCount = t2.second // 🌟 重新找回高精网格数！
                val blurryCount = t2.third   // 🌟 重新找回模糊网格数！

                val unlockedIds = unlockedAchievementIds.value

                // 1. 大地漫步者 (面积)
                checkAndUnlock("area_1", area >= 1.0, unlockedIds, "大地漫步者", "见微知著：在全球累计探索面积达到 1 km²", 1)
                checkAndUnlock("area_2", area >= 10.0, unlockedIds, "大地漫步者", "城市游侠：在全球累计探索面积达到 10 km²", 2)
                checkAndUnlock("area_3", area >= 100.0, unlockedIds, "大地漫步者", "广阔天地：在全球累计探索面积达到 100 km²", 3)
                checkAndUnlock("area_4", area >= 1000.0, unlockedIds, "大地漫步者", "洲际旅人：在全球累计探索面积达到 1000 km²", 4)
                checkAndUnlock("area_5", area >= 10000.0, unlockedIds, "大地漫步者", "世界引擎：在全球累计探索面积达到 10000 km²", 5)

                // 2. 像素猎人 (高精网格)
                checkAndUnlock("precise_1", preciseCount >= 100, unlockedIds, "像素猎人", "像素学徒：解锁 100 个高精网格", 1)
                checkAndUnlock("precise_2", preciseCount >= 1000, unlockedIds, "像素猎人", "寻迹者：解锁 1000 个高精网格", 2)
                checkAndUnlock("precise_3", preciseCount >= 5000, unlockedIds, "像素猎人", "细嗅蔷薇：解锁 5000 个高精网格", 3)
                checkAndUnlock("precise_4", preciseCount >= 20000, unlockedIds, "像素猎人", "全视之眼：解锁 20000 个高精网格", 4)
                checkAndUnlock("precise_5", preciseCount >= 100000, unlockedIds, "像素猎人", "夸克微雕师：解锁 100000 个高精网格", 5)

                // 3. 迷雾先锋 (模糊网格)
                checkAndUnlock("blurry_1", blurryCount >= 10, unlockedIds, "迷雾先锋", "启程：解锁 10 个模糊大网格", 1)
                checkAndUnlock("blurry_2", blurryCount >= 50, unlockedIds, "迷雾先锋", "破雾者：解锁 50 个模糊大网格", 2)
                checkAndUnlock("blurry_3", blurryCount >= 200, unlockedIds, "迷雾先锋", "乘风破浪：解锁 200 个模糊大网格", 3)
                checkAndUnlock("blurry_4", blurryCount >= 1000, unlockedIds, "迷雾先锋", "巡天者：解锁 1000 个模糊大网格", 4)
                checkAndUnlock("blurry_5", blurryCount >= 5000, unlockedIds, "迷雾先锋", "苍穹之影：解锁 5000 个模糊大网格", 5)

                // 4. 行者无疆 (距离)
                checkAndUnlock("distance_1", distance >= 10.0, unlockedIds, "行者无疆", "初识路途：累计移动距离达到 10 km", 1)
                checkAndUnlock("distance_2", distance >= 100.0, unlockedIds, "行者无疆", "百里游侠：累计移动距离达到 100 km", 2)
                checkAndUnlock("distance_3", distance >= 500.0, unlockedIds, "行者无疆", "千里独行：累计移动距离达到 500 km", 3)
                checkAndUnlock("distance_4", distance >= 2000.0, unlockedIds, "行者无疆", "万里跋涉：累计移动距离达到 2000 km", 4)
                checkAndUnlock("distance_5", distance >= 10000.0, unlockedIds, "行者无疆", "夸父追日：累计移动距离达到 10000 km", 5)

                // 5. 城市收集者
                checkAndUnlock("city_1", cities >= 1, unlockedIds, "城市收集者", "初来乍到：在 1 座城市留下了足迹", 1)
                checkAndUnlock("city_2", cities >= 5, unlockedIds, "城市收集者", "走南闯北：在 5 座城市留下了足迹", 2)
                checkAndUnlock("city_3", cities >= 20, unlockedIds, "城市收集者", "神州漫步：在 20 座城市留下了足迹", 3)
                checkAndUnlock("city_4", cities >= 100, unlockedIds, "城市收集者", "百城领主：在 100 座城市留下了足迹", 4)
                checkAndUnlock("city_5", cities >= 500, unlockedIds, "城市收集者", "大旅行家：在 500 座城市留下了足迹", 5)

                // 6. 环球旅行家
                checkAndUnlock("country_1", countries >= 1, unlockedIds, "环球旅行家", "本国居民：探索了 1 个国家", 1)
                checkAndUnlock("country_2", countries >= 3, unlockedIds, "环球旅行家", "跨越国界：探索了 3 个国家", 2)
                checkAndUnlock("country_3", countries >= 10, unlockedIds, "环球旅行家", "护照盖满：探索了 10 个国家", 3)
                checkAndUnlock("country_4", countries >= 50, unlockedIds, "环球旅行家", "四海为家：探索了 50 个国家", 4)
                checkAndUnlock("country_5", countries >= 197, unlockedIds, "环球旅行家", "地球漫游者：探索了 197 个国家", 5)
            }
        }
    }

    private suspend fun checkAndUnlock(id: String, met: Boolean, unlocked: Set<String>, title: String, desc: String, level: Int) {
        if (met && !unlocked.contains(id)) {
            achievementDao.insertAchievement(Achievement(id, title, desc, level, id, System.currentTimeMillis()))
        }
    }
}

class HomeViewModelFactory(
    private val dao: ExploredGridDao,
    private val prefs: AppPreferences,
    private val achievementDao: AchievementDao,
    private val dailyStatDao: DailyStatDao,
    private val regionProgressDao: RegionProgressDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(dao, prefs, achievementDao, dailyStatDao, regionProgressDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}