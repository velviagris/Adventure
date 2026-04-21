package com.velviagris.adventure

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.velviagris.adventure.data.ExploredGrid
import com.velviagris.adventure.data.ExploredGridDao
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
import java.util.Calendar.*
import java.util.concurrent.TimeUnit

class SettingsViewModel(private val dao: ExploredGridDao, private val prefs: AppPreferences) : ViewModel() {

    // 🌟 暴露 UI 状态
    val isDailySummaryEnabled = prefs.isDailySummaryEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 🌟 处理开关事件并调度 WorkManager
    fun toggleDailySummary(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setDailySummaryEnabled(enabled)
            val workManager = WorkManager.getInstance(context)

            if (enabled) {
                // 计算当前到今晚 21:00 的时间差
                val now = getInstance()
                val target = getInstance().apply {
                    set(HOUR_OF_DAY, 21)
                    set(MINUTE, 0)
                    set(SECOND, 0)
                    set(MILLISECOND, 0)
                }

                // 如果现在已经过了 21 点，那就设定为明天的 21 点
                if (target.before(now)) {
                    target.add(DAY_OF_MONTH, 1)
                }

                val initialDelay = target.timeInMillis - now.timeInMillis

                // 设置每 24 小时执行一次的周期性任务
                val summaryRequest = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .build()

                workManager.enqueueUniquePeriodicWork(
                    "DailySummaryWork",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    summaryRequest
                )
            } else {
                // 关闭开关则取消任务
                workManager.cancelUniqueWork("DailySummaryWork")
            }
        }
    }

    // 🌟 导出逻辑：数据库 -> JSON字符串 -> 文件流
    fun exportData(contentResolver: ContentResolver, uri: Uri, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val allGrids = dao.getAllGrids()
                    val jsonArray = JSONArray()
                    allGrids.forEach { grid ->
                        val obj = JSONObject().apply {
                            put("i", grid.gridIndex)      // 压缩字段名减小体积
                            put("a", grid.accuracyLevel)
                            put("s", grid.sourceType)
                            put("t", grid.exploreTime)
                        }
                        jsonArray.put(obj)
                    }

                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(jsonArray.toString().toByteArray())
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

    // 🌟 导入逻辑：文件流 -> JSON解析 -> 数据库
    fun importData(contentResolver: ContentResolver, uri: Uri, onComplete: (Boolean, Int) -> Unit) {
        viewModelScope.launch {
            var importedCount = 0
            val success = withContext(Dispatchers.IO) {
                try {
                    val jsonString = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (jsonString == null) return@withContext false

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

                    dao.insertGrids(gridsToImport)
                    importedCount = gridsToImport.size
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
            onComplete(success, importedCount)
        }
    }
}