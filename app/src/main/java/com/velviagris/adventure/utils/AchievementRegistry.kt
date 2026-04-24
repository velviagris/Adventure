package com.velviagris.adventure.utils

import android.content.Context
import androidx.annotation.StringRes
import com.velviagris.adventure.R
import com.velviagris.adventure.data.Achievement

data class AchievementDef(
    val categoryId: String,
    @StringRes val titleResId: Int,
    @StringRes val descResId: Int,
    @StringRes val unitResId: Int,
    val thresholds: List<Double>,
    val tierNameResIds: List<Int>
)

object AchievementRegistry {
    val definitions = listOf(
        AchievementDef(
            "area", R.string.ach_area_title, R.string.ach_area_desc, R.string.unit_km2,
            listOf(1.0, 10.0, 100.0, 1000.0, 10000.0),
            listOf(R.string.ach_area_1, R.string.ach_area_2, R.string.ach_area_3, R.string.ach_area_4, R.string.ach_area_5)
        ),
        AchievementDef(
            "precise", R.string.ach_precise_title, R.string.ach_precise_desc, R.string.unit_count,
            listOf(100.0, 1000.0, 5000.0, 20000.0, 100000.0),
            listOf(R.string.ach_precise_1, R.string.ach_precise_2, R.string.ach_precise_3, R.string.ach_precise_4, R.string.ach_precise_5)
        ),
        AchievementDef(
            "blurry", R.string.ach_blurry_title, R.string.ach_blurry_desc, R.string.unit_count,
            listOf(10.0, 50.0, 200.0, 1000.0, 5000.0),
            listOf(R.string.ach_blurry_1, R.string.ach_blurry_2, R.string.ach_blurry_3, R.string.ach_blurry_4, R.string.ach_blurry_5)
        ),
        AchievementDef(
            "distance", R.string.ach_dist_title, R.string.ach_dist_desc, R.string.unit_km,
            listOf(10.0, 100.0, 500.0, 2000.0, 10000.0),
            listOf(R.string.ach_dist_1, R.string.ach_dist_2, R.string.ach_dist_3, R.string.ach_dist_4, R.string.ach_dist_5)
        ),
        AchievementDef(
            "city", R.string.ach_city_title, R.string.ach_city_desc, R.string.unit_count,
            listOf(1.0, 5.0, 20.0, 100.0, 500.0),
            listOf(R.string.ach_city_1, R.string.ach_city_2, R.string.ach_city_3, R.string.ach_city_4, R.string.ach_city_5)
        ),
        AchievementDef(
            "country", R.string.ach_country_title, R.string.ach_country_desc, R.string.unit_count,
            listOf(1.0, 3.0, 10.0, 50.0, 197.0),
            listOf(R.string.ach_country_1, R.string.ach_country_2, R.string.ach_country_3, R.string.ach_country_4, R.string.ach_country_5)
        ),
        AchievementDef(
            "streak_checkin", R.string.ach_streak_title, R.string.ach_streak_desc, R.string.unit_day,
            listOf(3.0, 7.0, 30.0, 100.0, 365.0),
            listOf(R.string.ach_streak_1, R.string.ach_streak_2, R.string.ach_streak_3, R.string.ach_streak_4, R.string.ach_streak_5)
        ),
        AchievementDef(
            "streak_newexp", R.string.ach_newexp_title, R.string.ach_newexp_desc, R.string.unit_day,
            listOf(2.0, 5.0, 14.0, 30.0, 100.0),
            listOf(R.string.ach_newexp_1, R.string.ach_newexp_2, R.string.ach_newexp_3, R.string.ach_newexp_4, R.string.ach_newexp_5)
        ),
        AchievementDef(
            "streak_noexp", R.string.ach_noexp_title, R.string.ach_noexp_desc, R.string.unit_day,
            listOf(3.0, 7.0, 14.0, 30.0, 100.0),
            listOf(R.string.ach_noexp_1, R.string.ach_noexp_2, R.string.ach_noexp_3, R.string.ach_noexp_4, R.string.ach_noexp_5)
        ),
        AchievementDef(
            "visit", R.string.ach_visit_title, R.string.ach_visit_desc, R.string.unit_count,
            listOf(5.0, 20.0, 50.0, 100.0, 500.0),
            listOf(R.string.ach_visit_1, R.string.ach_visit_2, R.string.ach_visit_3, R.string.ach_visit_4, R.string.ach_visit_5)
        ),
        AchievementDef(
            "teleport", R.string.ach_teleport_title, R.string.ach_teleport_desc, R.string.unit_km,
            listOf(2.0, 10.0, 50.0, 200.0, 1000.0),
            listOf(R.string.ach_teleport_1, R.string.ach_teleport_2, R.string.ach_teleport_3, R.string.ach_teleport_4, R.string.ach_teleport_5)
        )
    )

    // ==========================================
    // Automated Evaluation Engine: Processes achievement logic for background tracking and data synchronization.
    // 自动巡检引擎：处理后台追踪及数据同步场景下的成就解锁逻辑。
    // ==========================================
    suspend fun evaluateAndUnlock(
        context: Context,
        metrics: Map<String, Double>,
        existingIds: Set<String>,
        unlockAction: suspend (Achievement) -> Unit
    ) {
        definitions.forEach { def ->
            val progress = metrics[def.categoryId] ?: 0.0

            for (lvl in 1..5) {
                val threshold = def.thresholds[lvl - 1]
                val achId = "${def.categoryId}_$lvl"

                // Check if requirements are met and achievement is not yet unlocked.
                // 检查是否满足阈值且尚未解锁该成就。
                if (progress >= threshold && !existingIds.contains(achId)) {
                    val title = context.getString(def.titleResId)
                    val tierName = context.getString(def.tierNameResIds[lvl - 1])
                    val unitStr = context.getString(def.unitResId)

                    val reqStr = if (unitStr == "km²" || unitStr == "km") String.format("%.0f", threshold) else threshold.toInt().toString()
                    val desc = context.getString(R.string.achievement_desc_format, tierName, reqStr, unitStr)

                    // Execute callback for persistence storage.
                    // 执行回调以进行持久化存储。
                    unlockAction(
                        Achievement(
                            id = achId,
                            title = title, // Stored as complete string for backup compatibility. / 存储完整字符串以保证备份兼容性。
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