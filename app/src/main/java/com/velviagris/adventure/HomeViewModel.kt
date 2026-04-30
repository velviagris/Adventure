package com.velviagris.adventure

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.velviagris.adventure.data.*
import com.velviagris.adventure.utils.AchievementRegistry
import com.velviagris.adventure.utils.AppLogger
import com.velviagris.adventure.utils.AppPreferences
import com.velviagris.adventure.utils.GeoJsonHelper
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
    private val regionProgressDao: RegionProgressDao,
    private val userRecordDao: UserRecordDao
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

    fun toggleTracking(enabled: Boolean) = viewModelScope.launch {
        prefs.setTrackingEnabled(enabled)
        AppLogger.i("HomeViewModel", "Tracking toggled: enabled=$enabled")
    }

    fun setPreciseMode(enabled: Boolean) = viewModelScope.launch {
        prefs.setPreciseMode(enabled)
        AppLogger.i("HomeViewModel", "Precise mode changed: enabled=$enabled")
    }

    fun recordRegionVisit(json: JSONObject, type: Int, name: String, currentExploredArea: Double) {
        viewModelScope.launch {
            // 获取原生的 osm_id，如果获取不到则放弃入库
            val osmId = json.optLong("osm_id", -1L)
            if (osmId == -1L) return@launch

            val addressType = json.optString("addresstype", "region")

            val stats = GeoJsonHelper.calculateExplorationStats(emptyList(), emptyList(), json)
            val totalArea = stats.second

            // 存入真实类型
            val region = RegionProgress(
                osmId = osmId,
                regionName = name,
                regionType = type,
                addressType = addressType,
                totalAreaKm2 = totalArea,
                exploredAreaKm2 = currentExploredArea
            )
            regionProgressDao.insertRegionIfNotExists(region)
            regionProgressDao.updateExploredArea(osmId, name, totalArea, currentExploredArea)

            AppLogger.d(
                "HomeViewModel",
                "Region progress updated: osmId=$osmId exploredArea=$currentExploredArea totalArea=$totalArea"
            )
        }
    }

    private val unlockedAchievementIds = achievementDao.getAllAchievementsFlow()
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        startAchievementEngine()
    }

    // ==========================================
    // 🌟 新增：计算连续天数的辅助算法
    // ==========================================
    private fun computeStreaks(statsList: List<DailyStat>): Triple<Int, Int, Int> {
        if (statsList.isEmpty()) return Triple(0, 0, 0)

        val statMap = statsList.associateBy { it.dateString }
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val todayStr = sdf.format(java.util.Date())

        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
        val yesterdayStr = sdf.format(cal.time)

        // 1. 签到牌 (如果今天没签，从昨天开始倒推)
        var checkInStreak = 0
        var c1 = java.util.Calendar.getInstance()
        if (statMap[todayStr]?.isTrackingActive != true) c1.time = sdf.parse(yesterdayStr)!!
        while (true) {
            val d = sdf.format(c1.time)
            if (statMap[d]?.isTrackingActive == true) {
                checkInStreak++
                c1.add(java.util.Calendar.DAY_OF_MONTH, -1)
            } else break
        }

        // 2. 连续新探索牌
        var newExpStreak = 0
        var c2 = java.util.Calendar.getInstance()
        if ((statMap[todayStr]?.newGridsCount ?: 0) == 0) c2.time = sdf.parse(yesterdayStr)!!
        while (true) {
            val d = sdf.format(c2.time)
            if ((statMap[d]?.newGridsCount ?: 0) > 0) {
                newExpStreak++
                c2.add(java.util.Calendar.DAY_OF_MONTH, -1)
            } else break
        }

        // 3. 宅家隐士牌 (倒推没有新网格的天数)
        var noNewExpStreak = 0
        val firstDateStr = statsList.last().dateString // 倒序排列的最后一个就是第一次记录的日期
        val firstDate = sdf.parse(firstDateStr)!!
        var c3 = java.util.Calendar.getInstance()

        while (!c3.time.before(firstDate)) {
            val d = sdf.format(c3.time)
            // 没开 App 或者 虽然开了但没发现新网格，都算作没新探索
            if ((statMap[d]?.newGridsCount ?: 0) == 0) {
                noNewExpStreak++
                c3.add(java.util.Calendar.DAY_OF_MONTH, -1)
            } else break
        }

        return Triple(checkInStreak, newExpStreak, noNewExpStreak)
    }

    // ==========================================
    // 🌟 升级版：全知全能的成就引擎
    // ==========================================
    private fun startAchievementEngine() {
        viewModelScope.launch {
            // 准备这三个全新的数据流
            val dailyStatsFlow = dailyStatDao.getAllDailyStatsFlow()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
            val maxVisitCountFlow = dao.getMaxVisitCountFlow().map { it ?: 1 }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)
            val userRecordFlow = userRecordDao.getRecordFlow().map { it ?: UserRecord() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserRecord())

            val statsFlow1 = combine(exploredAreaKm2, totalDistanceKm, cityCount) { a, d, c -> Triple(a, d, c) }
            val statsFlow2 = combine(countryCount, preciseGrids, blurryGrids) { c, p, b -> Triple(c, p.size, b.size) }

            // 🌟 核心修改：利用 combine 最多支持 5 个参数的特性，把所有流一网打尽！
            combine(
                statsFlow1,
                statsFlow2,
                dailyStatsFlow,
                maxVisitCountFlow,
                userRecordFlow
            ) { t1, t2, dailyStats, maxVisit, userRecord ->

                // 1. 动态推算今天为止的连续天数
                val (checkInStreak, newExpStreak, noNewExpStreak) = computeStreaks(dailyStats)

                // 2. 将所有当前进度打包进大 Map
                val metrics = mapOf(
                    "area" to t1.first,
                    "distance" to t1.second,
                    "city" to t1.third.toDouble(),
                    "country" to t2.first.toDouble(),
                    "precise" to t2.second.toDouble(),
                    "blurry" to t2.third.toDouble(),
                    "streak_checkin" to checkInStreak.toDouble(),
                    "streak_newexp" to newExpStreak.toDouble(),
                    "streak_noexp" to noNewExpStreak.toDouble(),
                    "visit" to maxVisit.toDouble(),
                    "teleport" to userRecord.maxSingleMoveDistanceKm
                )

                // 3. 一句话调用公共引擎！
                AchievementRegistry.evaluateAndUnlock(
                    context,
                    metrics,
                    unlockedAchievementIds.value
                ) { newAch ->
                    achievementDao.insertAchievement(newAch)
                }
            }.collect() // 🌟 别忘了加上 .collect()，否则冷流不会启动！
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
    private val regionProgressDao: RegionProgressDao,
    private val userRecordDao: UserRecordDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(context, dao, prefs, achievementDao, dailyStatDao, regionProgressDao, userRecordDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
