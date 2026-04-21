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
    private val regionProgressDao: RegionProgressDao // 🌟 注入区域仓库
) : ViewModel() {

    val isTrackingEnabled = prefs.isTrackingEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isPreciseMode = prefs.isPreciseMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 依然供地图渲染使用的 List
    val blurryGrids: StateFlow<List<ExploredGrid>> = dao.getGridsByAccuracyFlow(0)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val preciseGrids: StateFlow<List<ExploredGrid>> = dao.getGridsByAccuracyFlow(1)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI 展示的全球总面积
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

    fun toggleTracking(enabled: Boolean) = viewModelScope.launch { prefs.setTrackingEnabled(enabled) }
    fun setPreciseMode(enabled: Boolean) = viewModelScope.launch { prefs.setPreciseMode(enabled) }

    // ==========================================
    // 🌟 新增：区域进度更新接口 (供 HomeScreen 越界时调用)
    // ==========================================
    fun recordRegionVisit(json: JSONObject, type: Int, name: String, currentExploredArea: Double) {
        viewModelScope.launch {
            val placeId = json.optString("place_id").takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
            val stats = com.velviagris.adventure.utils.GeoJsonHelper.calculateExplorationStats(emptyList(), emptyList(), json)
            val totalArea = stats.second

            // 1. 如果是第一次来，记录到数据库
            val region = RegionProgress(placeId, name, type, totalArea, currentExploredArea)
            regionProgressDao.insertRegionIfNotExists(region)

            // 2. 更新该区域的探索面积
            regionProgressDao.updateExploredArea(placeId, currentExploredArea)
        }
    }

    // ==========================================
    // 🌟 丝滑且无负担的成就引擎
    // ==========================================
    private val unlockedAchievementIds = achievementDao.getAllAchievementsFlow()
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        startAchievementEngine()
    }

    private fun startAchievementEngine() {
        viewModelScope.launch {
            // 🌟 性能提升 99% 的秘密：引擎不再监听几十万条数据的 List
            // 而是直接向底层数据库要一个聚合后的轻量级数字！
            val totalDistanceFlow = dailyStatDao.getTotalDistanceFlow().map { it ?: 0.0 }
            val cityCountFlow = regionProgressDao.getRegionCountFlow(2) // 2=市
            val countryCountFlow = regionProgressDao.getRegionCountFlow(4) // 4=国家

            combine(
                exploredAreaKm2,
                totalDistanceFlow,
                cityCountFlow,
                countryCountFlow
            ) { area, distance, cities, countries ->
                listOf(area, distance, cities.toDouble(), countries.toDouble())
            }.collect { values ->
                val area = values[0]
                val distance = values[1]
                val cities = values[2].toInt()
                val countries = values[3].toInt()

                val unlockedIds = unlockedAchievementIds.value

                // 1. 大地漫步者 (面积)
                checkAndUnlock("area_1", area >= 1.0, unlockedIds, "大地漫步者", "见微知著：在全球累计探索面积达到 1 km²", 1)
                checkAndUnlock("area_2", area >= 10.0, unlockedIds, "大地漫步者", "城市游侠：在全球累计探索面积达到 10 km²", 2)
                checkAndUnlock("area_3", area >= 100.0, unlockedIds, "大地漫步者", "广阔天地：在全球累计探索面积达到 100 km²", 3)

                // 2. 行者无疆 (距离)
                checkAndUnlock("distance_1", distance >= 10.0, unlockedIds, "行者无疆", "初识路途：累计移动距离达到 10 km", 1)
                checkAndUnlock("distance_2", distance >= 100.0, unlockedIds, "行者无疆", "百里游侠：累计移动距离达到 100 km", 2)

                // 🌟 3. 新增成就：城市收集者
                checkAndUnlock("city_1", cities >= 1, unlockedIds, "城市收集者", "初来乍到：在 1 座城市留下了足迹", 1)
                checkAndUnlock("city_2", cities >= 5, unlockedIds, "城市收集者", "走南闯北：在 5 座城市留下了足迹", 2)
                checkAndUnlock("city_3", cities >= 20, unlockedIds, "城市收集者", "神州漫步：在 20 座城市留下了足迹", 3)
                checkAndUnlock("city_4", cities >= 100, unlockedIds, "城市收集者", "百城领主：在 100 座城市留下了足迹", 4)

                // 🌟 4. 新增成就：环球旅行家
                checkAndUnlock("country_1", countries >= 1, unlockedIds, "环球旅行家", "本国居民：探索了 1 个国家", 1)
                checkAndUnlock("country_2", countries >= 3, unlockedIds, "环球旅行家", "跨越国界：探索了 3 个国家", 2)
                checkAndUnlock("country_3", countries >= 10, unlockedIds, "环球旅行家", "护照盖满：探索了 10 个国家", 3)
            }
        }
    }

    private suspend fun checkAndUnlock(id: String, met: Boolean, unlocked: Set<String>, title: String, desc: String, level: Int) {
        if (met && !unlocked.contains(id)) {
            achievementDao.insertAchievement(Achievement(id, title, desc, level, id, System.currentTimeMillis()))
        }
    }
}

// 别忘了把 factory 更新！
class HomeViewModelFactory(
    private val dao: ExploredGridDao,
    private val prefs: AppPreferences,
    private val achievementDao: AchievementDao,
    private val dailyStatDao: DailyStatDao,
    private val regionProgressDao: RegionProgressDao // 🌟
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(dao, prefs, achievementDao, dailyStatDao, regionProgressDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}