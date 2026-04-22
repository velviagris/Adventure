package com.velviagris.adventure

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.velviagris.adventure.data.*
import com.velviagris.adventure.service.DailySummaryWorker
import com.velviagris.adventure.utils.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

class SettingsViewModel(
    private val db: AdventureDatabase, // 🌟 掌控整个数据库
    private val prefs: AppPreferences
) : ViewModel() {

    val isDailySummaryEnabled = prefs.isDailySummaryEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleDailySummary(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setDailySummaryEnabled(enabled)
            val workManager = WorkManager.getInstance(context)

            if (enabled) {
                val now = Calendar.getInstance()
                val target = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 21)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                if (target.before(now)) target.add(Calendar.DAY_OF_MONTH, 1)

                val initialDelay = target.timeInMillis - now.timeInMillis
                val summaryRequest = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .build()

                workManager.enqueueUniquePeriodicWork("DailySummaryWork", ExistingPeriodicWorkPolicy.UPDATE, summaryRequest)
            } else {
                workManager.cancelUniqueWork("DailySummaryWork")
            }
        }
    }

    // ==========================================
    // 🌟 V2 全库快照导出
    // ==========================================
    fun exportData(contentResolver: ContentResolver, uri: Uri, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val rootObj = JSONObject()
                    rootObj.put("version", 2) // 标记为 V2 备份

                    // 1. 导出网格
                    val gridsArray = JSONArray()
                    db.exploredGridDao().getAllGrids().forEach {
                        gridsArray.put(JSONObject().apply {
                            put("i", it.gridIndex)
                            put("a", it.accuracyLevel)
                            put("s", it.sourceType)
                            put("t", it.exploreTime)
                        })
                    }
                    rootObj.put("grids", gridsArray)

                    // 2. 导出成就
                    val achievementsArray = JSONArray()
                    db.achievementDao().getAllAchievements().forEach {
                        achievementsArray.put(JSONObject().apply {
                            put("id", it.id)
                            put("title", it.title)
                            put("desc", it.description)
                            put("level", it.level)
                            put("icon", it.iconRes)
                            put("time", it.earnedTime)
                        })
                    }
                    rootObj.put("achievements", achievementsArray)

                    // 3. 导出每日距离统计
                    val dailyStatsArray = JSONArray()
                    db.dailyStatDao().getAllDailyStats().forEach {
                        dailyStatsArray.put(JSONObject().apply {
                            put("date", it.dateString)
                            put("dist", it.totalDistanceKm)
                        })
                    }
                    rootObj.put("daily_stats", dailyStatsArray)

                    // 4. 导出行政区进度
                    val regionsArray = JSONArray()
                    db.regionProgressDao().getAllRegions().forEach {
                        regionsArray.put(JSONObject().apply {
                            put("id", it.regionId)
                            put("name", it.regionName)
                            put("type", it.regionType)
                            put("addressType", it.addressType)
                            put("totalArea", it.totalAreaKm2)
                            put("expArea", it.exploredAreaKm2)
                            put("firstTime", it.firstVisitTime)
                        })
                    }
                    rootObj.put("regions", regionsArray)

                    // 写入文件
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(rootObj.toString().toByteArray())
                    }
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
            onComplete(success)
        }
    }

    // ==========================================
    // 🌟 智能兼容导入 (支持 V1 旧数据和 V2 新数据)
    // ==========================================
    fun importData(contentResolver: ContentResolver, uri: Uri, onComplete: (Boolean, Int) -> Unit) {
        viewModelScope.launch {
            var importedGridCount = 0
            val success = withContext(Dispatchers.IO) {
                try {
                    val jsonString = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.trim()
                    if (jsonString.isNullOrEmpty()) return@withContext false

                    // 🌟 兼容性判定：如果以 "[" 开头，说明是老版本的 JSONArray 备份
                    if (jsonString.startsWith("[")) {
                        val jsonArray = JSONArray(jsonString)
                        val gridsToImport = mutableListOf<ExploredGrid>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            gridsToImport.add(ExploredGrid(
                                gridIndex = obj.getString("i"),
                                accuracyLevel = obj.getInt("a"),
                                sourceType = obj.getInt("s"),
                                exploreTime = obj.getLong("t")
                            ))
                        }
                        db.exploredGridDao().insertGrids(gridsToImport)
                        importedGridCount = gridsToImport.size

                    } else if (jsonString.startsWith("{")) {
                        // 🌟 V2 新版本完整备份恢复
                        val rootObj = JSONObject(jsonString)

                        // 1. 恢复网格
                        if (rootObj.has("grids")) {
                            val gridsArray = rootObj.getJSONArray("grids")
                            val gridsToImport = mutableListOf<ExploredGrid>()
                            for (i in 0 until gridsArray.length()) {
                                val obj = gridsArray.getJSONObject(i)
                                gridsToImport.add(ExploredGrid(
                                    gridIndex = obj.getString("i"),
                                    accuracyLevel = obj.getInt("a"),
                                    sourceType = obj.getInt("s"),
                                    exploreTime = obj.getLong("t")
                                ))
                            }
                            db.exploredGridDao().insertGrids(gridsToImport)
                            importedGridCount = gridsToImport.size
                        }

                        // 2. 恢复成就
                        if (rootObj.has("achievements")) {
                            val arr = rootObj.getJSONArray("achievements")
                            val list = mutableListOf<Achievement>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                list.add(Achievement(
                                    id = obj.getString("id"),
                                    title = obj.getString("title"),
                                    description = obj.getString("desc"),
                                    level = obj.getInt("level"),
                                    iconRes = obj.getString("icon"),
                                    earnedTime = obj.getLong("time")
                                ))
                            }
                            db.achievementDao().insertAchievements(list)
                        }

                        // 3. 恢复统计
                        if (rootObj.has("daily_stats")) {
                            val arr = rootObj.getJSONArray("daily_stats")
                            val list = mutableListOf<DailyStat>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                list.add(DailyStat(obj.getString("date"), obj.getDouble("dist")))
                            }
                            db.dailyStatDao().insertDailyStats(list)
                        }

                        // 4. 恢复区域
                        if (rootObj.has("regions")) {
                            val arr = rootObj.getJSONArray("regions")
                            val list = mutableListOf<RegionProgress>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                list.add(RegionProgress(
                                    regionId = obj.getString("id"),
                                    regionName = obj.getString("name"),
                                    regionType = obj.getInt("type"),
                                    addressType = obj.getString("addressType"),
                                    totalAreaKm2 = obj.getDouble("totalArea"),
                                    exploredAreaKm2 = obj.getDouble("expArea"),
                                    firstVisitTime = obj.getLong("firstTime")
                                ))
                            }
                            db.regionProgressDao().insertRegions(list)
                        }
                    }
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
            onComplete(success, importedGridCount)
        }
    }
}