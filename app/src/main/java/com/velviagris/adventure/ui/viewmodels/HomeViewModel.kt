package com.velviagris.adventure.ui.viewmodels

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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

    val totalDistanceKm: StateFlow<Double> = dailyStatDao.getTotalDistanceFlow()
        .map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val cityCount: StateFlow<Int> = regionProgressDao.getRegionCountFlow(2)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val countryCount: StateFlow<Int> = regionProgressDao.getRegionCountFlow(4)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // 🌟 暴露全局记录流给 UI，让 UI 能直接读取连续天数和极值
    val userRecordFlow: StateFlow<UserRecord> = userRecordDao.getRecordFlow().map { it ?: UserRecord() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserRecord())

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
            val osmId = json.optLong("osm_id", -1L)
            if (osmId == -1L) return@launch

            val addressType = json.optString("addresstype", "region")
            val stats = GeoJsonHelper.calculateExplorationStats(emptyList(), emptyList(), json)
            val totalArea = stats.second

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
        }
    }

    private val unlockedAchievementIds = achievementDao.getAllAchievementsFlow()
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        startAchievementEngine()
    }

    private fun computeStreaks(statsList: List<DailyStat>): Triple<Int, Int, Int> {
        if (statsList.isEmpty()) return Triple(0, 0, 0)

        val statMap = statsList.associateBy { it.dateString }
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = sdf.format(Date())

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -1)
        val yesterdayStr = sdf.format(cal.time)

        var checkInStreak = 0
        var c1 = Calendar.getInstance()
        if (statMap[todayStr]?.isTrackingActive != true) c1.time = sdf.parse(yesterdayStr)!!
        while (true) {
            val d = sdf.format(c1.time)
            if (statMap[d]?.isTrackingActive == true) {
                checkInStreak++
                c1.add(Calendar.DAY_OF_MONTH, -1)
            } else break
        }

        var newExpStreak = 0
        var c2 = Calendar.getInstance()
        if ((statMap[todayStr]?.newGridsCount ?: 0) == 0) c2.time = sdf.parse(yesterdayStr)!!
        while (true) {
            val d = sdf.format(c2.time)
            if ((statMap[d]?.newGridsCount ?: 0) > 0) {
                newExpStreak++
                c2.add(Calendar.DAY_OF_MONTH, -1)
            } else break
        }

        var noNewExpStreak = 0
        val firstDateStr = statsList.last().dateString
        val firstDate = sdf.parse(firstDateStr)!!
        var c3 = Calendar.getInstance()

        while (!c3.time.before(firstDate)) {
            val d = sdf.format(c3.time)
            if ((statMap[d]?.newGridsCount ?: 0) == 0) {
                noNewExpStreak++
                c3.add(Calendar.DAY_OF_MONTH, -1)
            } else break
        }

        return Triple(checkInStreak, newExpStreak, noNewExpStreak)
    }

    private fun startAchievementEngine() {
        viewModelScope.launch {
            val dailyStatsFlow = dailyStatDao.getAllDailyStatsFlow()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
            val maxVisitCountFlow = dao.getMaxVisitCountFlow().map { it ?: 1 }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

            val statsFlow1 = combine(exploredAreaKm2, totalDistanceKm, cityCount) { a, d, c -> Triple(a, d, c) }
            val statsFlow2 = combine(countryCount, preciseGrids, blurryGrids) { c, p, b -> Triple(c, p.size, b.size) }

            combine(
                statsFlow1,
                statsFlow2,
                dailyStatsFlow,
                maxVisitCountFlow,
                userRecordFlow
            ) { t1, t2, dailyStats, maxVisit, userRecord ->

                val (checkInStreak, newExpStreak, noNewExpStreak) = computeStreaks(dailyStats)

                // 🌟 将这些状态回写持久化，成为单一事实来源
                val updatedRecord = userRecord.copy(
                    checkInStreak = checkInStreak,
                    newExpStreak = newExpStreak,
                    noNewExpStreak = noNewExpStreak,
                    maxVisitCount = maxVisit
                )
                userRecordDao.insertRecord(updatedRecord)

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

                AchievementRegistry.evaluateAndUnlock(context, metrics, unlockedAchievementIds.value) { newAch ->
                    achievementDao.insertAchievement(newAch)
                }
            }.collect()
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