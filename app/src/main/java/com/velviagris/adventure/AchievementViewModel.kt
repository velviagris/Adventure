package com.velviagris.adventure

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.velviagris.adventure.data.Achievement
import com.velviagris.adventure.data.AchievementDao
import com.velviagris.adventure.utils.AchievementRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AchievementGroup(
    val categoryTitle: String,
    val highestAchievement: Achievement,
    val history: List<Achievement>
)

class AchievementViewModel(private val dao: AchievementDao) : ViewModel() {

    val groupedAchievements: StateFlow<List<AchievementGroup>> = dao.getAllAchievementsFlow()
        .map { list ->
            list.groupBy { it.title }
                .map { (title, groupList) ->
                    val sortedGroup = groupList.sortedByDescending { it.level }
                    AchievementGroup(
                        categoryTitle = title,
                        highestAchievement = sortedGroup.first(),
                        history = sortedGroup.sortedBy { it.level }
                    )
                }
                .sortedByDescending { it.highestAchievement.earnedTime }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 🌟 带有自我修复（降级）机制的同步引擎
    fun syncAchievements(context: Context, metrics: Map<String, Double>) {
        viewModelScope.launch(Dispatchers.IO) {
            val existingIds = dao.getAllAchievements().map { it.id }.toSet()

            AchievementRegistry.definitions.forEach { def ->
                val progress = metrics[def.categoryId] ?: 0.0

                for (lvl in 1..5) {
                    val threshold = def.thresholds[lvl - 1]
                    val achId = "${def.categoryId}_$lvl"

                    // 情况 A：进度达标且未领牌 -> 补发
                    if (progress >= threshold && !existingIds.contains(achId)) {
                        val title = context.getString(def.titleResId)
                        val tierName = context.getString(def.tierNameResIds[lvl - 1])
                        val unitStr = context.getString(def.unitResId)

                        val reqStr = if (unitStr == "km²" || unitStr == "km") String.format("%.0f", threshold) else threshold.toInt().toString()
                        val desc = context.getString(R.string.achievement_desc_format, tierName, reqStr, unitStr)

                        dao.insertAchievement(
                            Achievement(
                                id = achId,
                                title = title,
                                description = desc,
                                level = lvl,
                                iconRes = achId,
                                earnedTime = System.currentTimeMillis()
                            )
                        )
                    }

                    // 🌟 情况 B：进度不达标但已有牌 -> 删除（自我修复/降级）
                    if (progress < threshold && existingIds.contains(achId)) {
                        dao.deleteAchievementById(achId)
                    }
                }
            }
        }
    }
}

class AchievementViewModelFactory(private val dao: AchievementDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AchievementViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AchievementViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}