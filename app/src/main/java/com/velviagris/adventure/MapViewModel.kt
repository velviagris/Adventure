package com.velviagris.adventure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.velviagris.adventure.data.ExploredGrid
import com.velviagris.adventure.data.ExploredGridDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MapViewModel(private val dao: ExploredGridDao) : ViewModel() {

    // 1. 监听并暴露“模糊/省电模式”的网格数据 (accuracyLevel = 0)
    val blurryGrids: StateFlow<List<ExploredGrid>> = dao.getGridsByAccuracyFlow(0)
        .stateIn(
            scope = viewModelScope,
            // 巧妙的性能优化：只有当地图页显示时才监听数据库，切到后台5秒后自动停止，极致省电
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 2. 监听并暴露“精确/旅游模式”的网格数据 (accuracyLevel = 1)
    val preciseGrids: StateFlow<List<ExploredGrid>> = dao.getGridsByAccuracyFlow(1)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}

// 同样的，我们需要一个工厂来把 DAO 传给 ViewModel
class MapViewModelFactory(private val dao: ExploredGridDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MapViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MapViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}