package com.velviagris.adventure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.velviagris.adventure.data.Achievement
import com.velviagris.adventure.data.AchievementDao
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
    // ==========================================
    // 🌟 终极解决方案：全量数据巡检开牌器
    // ==========================================
    fun syncAchievements(
        area: Double,
        distance: Double,
        preciseCount: Int,
        blurryCount: Int,
        cityCount: Int,
        countryCount: Int
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 1. 获取当前数据库里已经有的成就 ID，防止重复发奖
                val existingIds = dao.getAllAchievements().map { it.id }.toSet()

                // 2. 遍历大字典，动态比对你的每一项进度
                AchievementRegistry.definitions.forEach { def ->
                    val progress = when (def.categoryId) {
                        "area" -> area
                        "precise" -> preciseCount.toDouble()
                        "blurry" -> blurryCount.toDouble()
                        "distance" -> distance
                        "city" -> cityCount.toDouble()
                        "country" -> countryCount.toDouble()
                        else -> 0.0
                    }

                    // 3. 逐级检查 (1 级到 5 级)
                    for (lvl in 1..5) {
                        val threshold = def.thresholds[lvl - 1]
                        val achId = "${def.categoryId}_$lvl"

                        // 🌟 核心：如果进度达标，且数据库里没这块牌子，立刻开牌补发！
                        if (progress >= threshold && !existingIds.contains(achId)) {
                            val tierName = def.tierNames[lvl - 1]
                            val unitStr = def.unit
                            val reqStr = if (unitStr == "km²" || unitStr == "km") String.format("%.0f", threshold) else threshold.toInt().toString()
                            val desc = "$tierName：累计达到 $reqStr $unitStr"

                            dao.insertAchievement(
                                Achievement(
                                    id = achId,
                                    title = def.title,
                                    description = desc,
                                    level = lvl,
                                    iconRes = achId,
                                    earnedTime = System.currentTimeMillis()
                                )
                            )
                        }
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