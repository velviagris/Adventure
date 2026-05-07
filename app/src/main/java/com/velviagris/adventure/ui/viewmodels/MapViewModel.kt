package com.velviagris.adventure.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.velviagris.adventure.data.ExploredGrid
import com.velviagris.adventure.data.ExploredGridDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MapViewModel(private val dao: ExploredGridDao) : ViewModel() {

    /**
     * Observes and exposes grid entities for "Coarse/Power-saving" mode (accuracyLevel = 0).
     * 观察并暴露“粗略/省电模式”下的网格实体（精度等级 = 0）。
     */
    val blurryGrids: StateFlow<List<ExploredGrid>> = dao.getGridsByAccuracyFlow(0)
        .stateIn(
            scope = viewModelScope,
            /**
             * Performance optimization: The upstream database flow remains active only while the Map UI is visible.
             * Automatically terminates subscription 5 seconds after transitioning to the background to minimize energy consumption.
             * 性能优化：上游数据库流仅在地图 UI 可见时保持活跃。
             * 进入后台 5 秒后自动终止订阅，以最大限度降低能耗。
             */
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Observes and exposes grid entities for "Precise/Navigation" mode (accuracyLevel = 1).
     * 观察并暴露“高精/导航模式”下的网格实体（精度等级 = 1）。
     */
    val preciseGrids: StateFlow<List<ExploredGrid>> = dao.getGridsByAccuracyFlow(1)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Delete explored area from the database.
     * 删除已探索网格
     */
    fun deleteGrid(gridIndex: String) {
        viewModelScope.launch {
            dao.deleteGrid(gridIndex)
        }
    }
}

/**
 * Boilerplate factory implementation for providing the [ExploredGridDao] dependency to [MapViewModel].
 * 用于向 [MapViewModel] 提供 [ExploredGridDao] 依赖的工厂模式实现。
 */
class MapViewModelFactory(private val dao: ExploredGridDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MapViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MapViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}