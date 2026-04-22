package com.velviagris.adventure

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.velviagris.adventure.data.*
import com.velviagris.adventure.utils.AchievementRegistry
import com.velviagris.adventure.utils.AppPreferences
import com.velviagris.adventure.utils.GridHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

class HomeViewModel(
    private val context: Context,
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
                // 🌟 将所有当前进度打包进 Map
                val metrics = mapOf(
                    "area" to t1.first,
                    "distance" to t1.second,
                    "city" to t1.third.toDouble(),
                    "country" to t2.first.toDouble(),
                    "precise" to t2.second.toDouble(),
                    "blurry" to t2.third.toDouble()
                )

                // 🌟 核心：一句话调用公共引擎！
                AchievementRegistry.evaluateAndUnlock(
                    context,
                    metrics,
                    unlockedAchievementIds.value
                ) { newAch ->
                    achievementDao.insertAchievement(newAch)
                }
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
    private val context: Context,
    private val dao: ExploredGridDao,
    private val prefs: AppPreferences,
    private val achievementDao: AchievementDao,
    private val dailyStatDao: DailyStatDao,
    private val regionProgressDao: RegionProgressDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(context, dao, prefs, achievementDao, dailyStatDao, regionProgressDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}