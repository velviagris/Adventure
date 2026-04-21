package com.velviagris.adventure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.velviagris.adventure.data.Achievement
import com.velviagris.adventure.data.AchievementDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

//    // 测试用的方法：随机塞入一个成就
//    fun addTestAchievement() {
//        viewModelScope.launch {
//            val randomLevel = (1..5).random()
//            val names = listOf("大地漫步者", "像素猎人", "迷雾先锋")
//            val desc = listOf("累计探索面积", "解锁高精网格", "解锁模糊大网格")
//
//            val ach = Achievement(
//                id = "test_ach_${System.currentTimeMillis()}",
//                title = names.random(),
//                description = desc.random() + " 达到等级 $randomLevel",
//                level = randomLevel,
//                iconRes = "placeholder",
//                earnedTime = System.currentTimeMillis() - (0..1000000000).random()
//            )
//            dao.insertAchievement(ach)
//        }
//    }
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