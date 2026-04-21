package com.velviagris.adventure.utils

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 扩展属性，创建 DataStore 实例
private val Context.dataStore by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {
    companion object {
        val IS_TRACKING_ENABLED = booleanPreferencesKey("is_tracking_enabled")
        val IS_PRECISE_MODE = booleanPreferencesKey("is_precise_mode")
        val IS_DAILY_SUMMARY_ENABLED = booleanPreferencesKey("is_daily_summary_enabled")
    }

    // 读取状态
    val isTrackingEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_TRACKING_ENABLED] ?: false }
    val isPreciseMode: Flow<Boolean> = context.dataStore.data.map { it[IS_PRECISE_MODE] ?: false }
    val isDailySummaryEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_DAILY_SUMMARY_ENABLED] ?: false } // 🌟 新增

    // 写入状态
    suspend fun setTrackingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[IS_TRACKING_ENABLED] = enabled }
    }

    suspend fun setPreciseMode(enabled: Boolean) {
        context.dataStore.edit { it[IS_PRECISE_MODE] = enabled }
    }

    suspend fun setDailySummaryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[IS_DAILY_SUMMARY_ENABLED] = enabled }
    }
}