package com.velviagris.adventure.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.velviagris.adventure.R
import com.velviagris.adventure.data.AdventureDatabase
import com.velviagris.adventure.utils.GridHelper
import java.util.Calendar

class DailySummaryWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val database = AdventureDatabase.getDatabase(context)
        val dao = database.exploredGridDao()

        // 1. 获取今天凌晨 00:00:00 的时间戳
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfToday = calendar.timeInMillis
        val endOfToday = calendar.apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis


        // 2. 从数据库查询今天新增的网格
        val gridsToday = dao.getGridsExploredByTime(startOfToday, endOfToday)

        var preciseArea = 0.0
        var blurryArea = 0.0

        gridsToday.forEach { grid ->
            val area = GridHelper.getGridAreaKm2(grid.gridIndex)
            if (grid.accuracyLevel == 1) preciseArea += area else blurryArea += area
        }

        // 3. 组装文案
        val contentText = when {
            preciseArea > 0 && blurryArea > 0 -> context.getString(R.string.summary_both, preciseArea, blurryArea)
            preciseArea > 0 -> context.getString(R.string.summary_precise, preciseArea)
            blurryArea > 0 -> context.getString(R.string.summary_blurry, blurryArea)
            else -> context.getString(R.string.summary_none)
        }

        // 4. 发送通知
        showNotification(context, contentText)

        return Result.success()
    }

    private fun showNotification(context: Context, text: String) {
        val channelId = "daily_summary_channel"
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            channelId,
            context.getString(R.string.summary_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.summary_channel_desc) }

        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(context.getString(R.string.summary_notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)) // 支持长文本换行展示
            .build()

        notificationManager.notify(1002, notification)
    }
}