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
import kotlinx.coroutines.withContext

// 🌟 新增：成就组装包，用于将同名成就打包
data class AchievementGroup(
    val categoryTitle: String,
    val highestAchievement: Achievement, // 当前展示的最高牌子
    val history: List<Achievement>       // 解锁历史 (包含低级别和当前级别)
)

class AchievementViewModel(private val dao: AchievementDao) : ViewModel() {

//    // 暴露成就流给 UI
//    val achievements: StateFlow<List<Achievement>> = dao.getAllAchievementsFlow()
//        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 🌟 核心修改：将扁平的 List 转换为分组的 List
    val groupedAchievements: StateFlow<List<AchievementGroup>> = dao.getAllAchievementsFlow()
        .map { list ->
            list.groupBy { it.title } // 按成就大类名称（如：大地漫步者）分组
                .map { (title, groupList) ->
                    // 将组内成就按等级从高到低排序
                    val sortedGroup = groupList.sortedByDescending { it.level }
                    AchievementGroup(
                        categoryTitle = title,
                        highestAchievement = sortedGroup.first(), // 拿到最高等级的牌子
                        history = sortedGroup.sortedBy { it.level } // 历史记录按等级从低到高排列（铜 -> 银 -> 金）
                    )
                }
                // 最后，整个成就墙按“最高牌子的获得时间”从近到远排序
                .sortedByDescending { it.highestAchievement.earnedTime }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    // 🌟 将判断逻辑完全交给公共引擎
    fun syncAchievements(context: Context, metrics: Map<String, Double>) {
        viewModelScope.launch(Dispatchers.IO) {
            val existingIds = dao.getAllAchievements().map { it.id }.toSet()

            // 一句话解决！
            AchievementRegistry.evaluateAndUnlock(context, metrics, existingIds) { newAch ->
                dao.insertAchievement(newAch)
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