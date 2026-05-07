package com.velviagris.adventure.ui.viewmodels

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.velviagris.adventure.data.Achievement
import com.velviagris.adventure.data.AdventureDatabase
import com.velviagris.adventure.data.DailyStat
import com.velviagris.adventure.data.ExploredGrid
import com.velviagris.adventure.data.RegionProgress
import com.velviagris.adventure.data.UserRecord
import com.velviagris.adventure.service.DailySummaryWorker
import com.velviagris.adventure.utils.AppLogger
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
    private val db: AdventureDatabase,
    private val prefs: AppPreferences
) : ViewModel() {

    val isDailySummaryEnabled = prefs.isDailySummaryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleDailySummary(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setDailySummaryEnabled(enabled)
            AppLogger.i("SettingsViewModel", "Daily summary toggled: enabled=$enabled")
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

                workManager.enqueueUniquePeriodicWork(
                    "DailySummaryWork",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    summaryRequest
                )
                AppLogger.i("SettingsViewModel", "Scheduled daily summary worker")
            } else {
                workManager.cancelUniqueWork("DailySummaryWork")
                AppLogger.i("SettingsViewModel", "Cancelled daily summary worker")
            }
        }
    }

    fun exportData(contentResolver: ContentResolver, uri: Uri, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    AppLogger.i("SettingsViewModel", "Starting backup export to $uri")
                    val rootObj = JSONObject()
                    rootObj.put("version", 2)

                    val gridsArray = JSONArray()
                    db.exploredGridDao().getAllGrids().forEach {
                        gridsArray.put(JSONObject().apply {
                            put("i", it.gridIndex)
                            put("a", it.accuracyLevel)
                            put("s", it.sourceType)
                            put("t", it.exploreTime)
                            put("v", it.visitCount) // 🌟 导出访问次数v
                        })
                    }
                    rootObj.put("grids", gridsArray)

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
                            put("osmId", it.osmId)
                            put("name", it.regionName)
                            put("type", it.regionType)
                            put("addressType", it.addressType)
                            put("totalArea", it.totalAreaKm2)
                            put("expArea", it.exploredAreaKm2)
                            put("firstTime", it.firstVisitTime)
                        })
                    }
                    rootObj.put("regions", regionsArray)

                    val record = db.userRecordDao().getRecord() ?: UserRecord()
                    rootObj.put("user_record", JSONObject().apply {
                        put("last", record.lastBlurryGridId)
                        put("maxDist", record.maxSingleMoveDistanceKm)
                    })

                    val wroteFile = contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(rootObj.toString().toByteArray())
                    } != null
                    if (!wroteFile) {
                        AppLogger.w("SettingsViewModel", "Backup export failed because output stream was unavailable")
                        return@withContext false
                    }
                    AppLogger.i("SettingsViewModel", "Backup export completed successfully")
                    true
                } catch (e: Exception) {
                    AppLogger.e("SettingsViewModel", "Backup export failed", e)
                    false
                }
            }
            onComplete(success)
        }
    }

    fun exportLogs(contentResolver: ContentResolver, uri: Uri, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                AppLogger.i("SettingsViewModel", "Starting log export to $uri")
                AppLogger.exportLogs(contentResolver, uri)
            }
            if (success) {
                AppLogger.i("SettingsViewModel", "Log export completed successfully")
            }
            onComplete(success)
        }
    }

    fun importData(contentResolver: ContentResolver, uri: Uri, onComplete: (Boolean, Int) -> Unit) {
        viewModelScope.launch {
            var importedGridCount = 0
            val success = withContext(Dispatchers.IO) {
                try {
                    AppLogger.i("SettingsViewModel", "Starting backup import from $uri")
                    val jsonString = contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?.trim()
                    if (jsonString.isNullOrEmpty()) return@withContext false

                    if (jsonString.startsWith("[")) {
                        val jsonArray = JSONArray(jsonString)
                        val gridsToImport = mutableListOf<ExploredGrid>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            gridsToImport.add(
                                ExploredGrid(
                                    gridIndex = obj.getString("i"),
                                    accuracyLevel = obj.getInt("a"),
                                    sourceType = obj.getInt("s"),
                                    exploreTime = obj.getLong("t"),
                                    visitCount = obj.optInt("v", 1)
                                )
                            )
                        }
                        db.exploredGridDao().insertGrids(gridsToImport)
                        importedGridCount = gridsToImport.size
                    } else if (jsonString.startsWith("{")) {
                        val rootObj = JSONObject(jsonString)

                        if (rootObj.has("grids")) {
                            val gridsArray = rootObj.getJSONArray("grids")
                            val gridsToImport = mutableListOf<ExploredGrid>()
                            for (i in 0 until gridsArray.length()) {
                                val obj = gridsArray.getJSONObject(i)
                                gridsToImport.add(
                                    ExploredGrid(
                                        gridIndex = obj.getString("i"),
                                        accuracyLevel = obj.getInt("a"),
                                        sourceType = obj.getInt("s"),
                                        exploreTime = obj.getLong("t")
                                    )
                                )
                            }
                            db.exploredGridDao().insertGrids(gridsToImport)
                            importedGridCount = gridsToImport.size
                        }

                        if (rootObj.has("achievements")) {
                            val arr = rootObj.getJSONArray("achievements")
                            val list = mutableListOf<Achievement>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                list.add(
                                    Achievement(
                                        id = obj.getString("id"),
                                        title = obj.getString("title"),
                                        description = obj.getString("desc"),
                                        level = obj.getInt("level"),
                                        iconRes = obj.getString("icon"),
                                        earnedTime = obj.getLong("time")
                                    )
                                )
                            }
                            db.achievementDao().insertAchievements(list)
                        }

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
                                val osmId = obj.optLong("osmId")
                                if (osmId != 0L){
                                    list.add(
                                        RegionProgress(
                                            // 舍弃旧格式数据：优先读取 osmId，如果是以前旧版备份里只有 id 字符串，不存入
                                            osmId = osmId,
                                            regionName = obj.getString("name"),
                                            regionType = obj.getInt("type"),
                                            addressType = obj.getString("addressType"),
                                            totalAreaKm2 = obj.getDouble("totalArea"),
                                            exploredAreaKm2 = obj.getDouble("expArea"),
                                            firstVisitTime = obj.getLong("firstTime")
                                        )
                                    )
                                }
                            }
                            db.regionProgressDao().insertRegions(list)
                        }
                        // 恢复 UserRecord 快照
                        if (rootObj.has("user_record")) {
                            val rObj = rootObj.getJSONObject("user_record")
                            db.userRecordDao().insertRecord(UserRecord(
                                lastBlurryGridId = rObj.optString("last", ""),
                                maxSingleMoveDistanceKm = rObj.optDouble("maxDist", 0.0)
                            ))
                        }
                    }
                    AppLogger.i(
                        "SettingsViewModel",
                        "Backup import completed successfully, importedGridCount=$importedGridCount"
                    )
                    true
                } catch (e: Exception) {
                    AppLogger.e("SettingsViewModel", "Backup import failed", e)
                    false
                }
            }
            onComplete(success, importedGridCount)
        }
    }
}
