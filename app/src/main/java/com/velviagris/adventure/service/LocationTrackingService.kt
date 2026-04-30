package com.velviagris.adventure.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.velviagris.adventure.MainActivity
import com.velviagris.adventure.R
import com.velviagris.adventure.data.ExploredGrid
import com.velviagris.adventure.data.AdventureDatabase
import com.velviagris.adventure.data.DailyStat
import com.velviagris.adventure.data.DailyStatDao
import com.velviagris.adventure.data.UserRecord
import com.velviagris.adventure.data.UserRecordDao
import com.velviagris.adventure.utils.AppLogger
import com.velviagris.adventure.utils.GridHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var database: AdventureDatabase

    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private lateinit var activityPendingIntent: PendingIntent
    private val ACTION_ACTIVITY_UPDATE = "com.velviagris.adventure.ACTION_ACTIVITY_UPDATE"

    private var isPreciseMode = false
    private var isLocationPaused = false
    private var stillCounter = 0

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "adventure_tracking_channel"

    private val ACTION_SWITCH_MODE = "com.velviagris.adventure.ACTION_SWITCH_MODE"

    private lateinit var dailyStatDao: DailyStatDao
    private var lastLocation: android.location.Location? = null // Cache for Euclidean distance calculations between consecutive trajectory points. / 缓存用于计算连续轨迹点间欧式距离的位置数据。

    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_ACTIVITY_UPDATE && ActivityRecognitionResult.hasResult(intent)) {
                val result = ActivityRecognitionResult.extractResult(intent)
                result?.let { handleActivityTransition(it.mostProbableActivity) }
            }
        }
    }

    private lateinit var userRecordDao: UserRecordDao
    private var currentUserRecord = UserRecord() // Hot data state maintained in memory for low-latency access. / 内存中维护的热数据状态，用于低延迟访问。

    override fun onCreate() {
        super.onCreate()
        AppLogger.initialize(applicationContext)
        AppLogger.i("LocationTrackingService", "Service created")
        database = AdventureDatabase.getDatabase(this)
        dailyStatDao = database.dailyStatDao()
        userRecordDao = database.userRecordDao()
        serviceScope.launch {
            currentUserRecord = userRecordDao.getRecord() ?: UserRecord()
        }
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)

        createNotificationChannel()

        val intentFilter = IntentFilter(ACTION_ACTIVITY_UPDATE)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.RECEIVER_NOT_EXPORTED else 0
        ContextCompat.registerReceiver(this, activityReceiver, intentFilter, flags)

        val intent = Intent(ACTION_ACTIVITY_UPDATE).apply {
            setPackage(packageName)
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        activityPendingIntent = PendingIntent.getBroadcast(this, 0, intent, pendingIntentFlags)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    // 1. Grid indexing and persistence. / 网格索引计算与持久化。
                    recordLocation(location.latitude, location.longitude, location.time)

                    // 2. Compute physical displacement from previous coordinates. / 计算与前序坐标点的物理位移。
                    if (lastLocation != null) {
                        val distanceMeters = lastLocation!!.distanceTo(location)
                        if (distanceMeters > 0) {
                            recordDistance(distanceMeters)
                        }
                    }
                    // Update reference coordinates for the next interval. / 更新下一周期的参考坐标。
                    lastLocation = location
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 🌟 新增拦截逻辑：如果是点击通知栏"切换模式"按钮进来的
        if (intent?.action == ACTION_SWITCH_MODE) {
            isPreciseMode = !isPreciseMode

            // 异步更新 DataStore，让 HomeScreen UI 里的 Switch 也能自动跟着变！
            serviceScope.launch {
                com.velviagris.adventure.utils.AppPreferences(applicationContext).setPreciseMode(isPreciseMode)
            }

            // 重启位置请求以应用新的精度，并更新通知栏文案
            requestLocationUpdates()
            updateNotification(getNotificationText())
            AppLogger.i("LocationTrackingService", "Mode switched via notification: preciseMode=$isPreciseMode")

            return START_STICKY
        }

        // --- 以下是原有的正常启动逻辑 ---
        isPreciseMode = intent?.getBooleanExtra("EXTRA_IS_PRECISE", isPreciseMode) ?: isPreciseMode
        AppLogger.i("LocationTrackingService", "Service started, preciseMode=$isPreciseMode")

        isLocationPaused = false
        stillCounter = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(getNotificationText()), getLocationServiceType())
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(getNotificationText()))
        }

        requestLocationUpdates()
        requestActivityUpdates()

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) recordLocation(location.latitude, location.longitude, location.time)
            }
        } catch (e: SecurityException) {
            AppLogger.e("LocationTrackingService", "Missing permission when requesting last known location", e)
        }

        return START_STICKY
    }

    private fun recordDistance(distanceMeters: Float) {
        val distanceKm = distanceMeters / 1000.0
        val dateString = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())

        serviceScope.launch {
            // 1. Accumulate total displacement metrics. / 累加总位移指标。
            val stat = dailyStatDao.getDailyStat(dateString) ?: DailyStat(dateString)
            stat.totalDistanceKm += distanceKm
            stat.isTrackingActive = true
            dailyStatDao.insertDailyStat(stat)

            // 2. Extreme value detection: Update maximum instantaneous displacement record. / 峰值检测：更新单次瞬时位移记录。
            if (distanceKm > currentUserRecord.maxSingleMoveDistanceKm) {
                currentUserRecord.maxSingleMoveDistanceKm = distanceKm
                userRecordDao.insertRecord(currentUserRecord)
            }
        }
    }

    private fun handleActivityTransition(activity: DetectedActivity) {
        when (activity.type) {
            DetectedActivity.STILL -> {
                stillCounter++
                if (stillCounter >= 2 && !isLocationPaused) {
                    pauseLocationUpdates()
                }
            }
            DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE -> {
                stillCounter = 0
                if (isLocationPaused) {
                    resumeLocationUpdates()
                }
            }
            else -> {
                stillCounter = maxOf(0, stillCounter - 1)
            }
        }
    }

    private fun pauseLocationUpdates() {
        isLocationPaused = true
        fusedLocationClient.removeLocationUpdates(locationCallback)
        AppLogger.i("LocationTrackingService", "Location updates paused because device is still")
        updateNotification(getString(R.string.tracking_paused_still))
    }

    private fun resumeLocationUpdates() {
        isLocationPaused = false
        requestLocationUpdates()
        AppLogger.i("LocationTrackingService", "Location updates resumed")
        updateNotification(getNotificationText())

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) recordLocation(location.latitude, location.longitude, location.time)
            }
        } catch (e: SecurityException) {
            AppLogger.e("LocationTrackingService", "Missing permission when resuming last known location", e)
        }
    }

    private fun requestLocationUpdates() {
        val locationRequest = if (isPreciseMode) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L).setMinUpdateDistanceMeters(5f).build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 180000L).setMinUpdateDistanceMeters(100f).build()
        }

        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            AppLogger.e("LocationTrackingService", "Failed to request location updates", e)
        }
    }

    private fun requestActivityUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                activityRecognitionClient.requestActivityUpdates(30000L, activityPendingIntent)
                AppLogger.i("LocationTrackingService", "Activity recognition updates requested")
            } catch (e: SecurityException) {
                AppLogger.e("LocationTrackingService", "Failed to request activity updates", e)
            }
        }
    }

    private fun recordLocation(lat: Double, lon: Double, timeMs: Long) {
        serviceScope.launch {
            val dao = database.exploredGridDao()

            // =====================================
            // 1. Grid indexing and revisit frequency calculation logic. / 网格索引及访问频次计算逻辑。
            // =====================================
            val blurryGridIndex = GridHelper.getGridIndex(lat, lon, false)
            val existingBlurry = dao.getGrid(blurryGridIndex)

            if (existingBlurry == null) {
                // Initialize record for newly discovered coarse grid (Initial visit count = 1). / 首次发现该粗略网格，初始化记录（初始访问频次为1）。
                dao.insertGrid(ExploredGrid(blurryGridIndex, 0, 0, timeMs, visitCount = 1))

                // Increment discovery metrics in DailyStat. / 递增每日统计中的新网格发现计数。
                val dateString = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(timeMs))
                val stat = dailyStatDao.getDailyStat(dateString) ?: DailyStat(dateString)
                stat.newGridsCount += 1
                stat.isTrackingActive = true
                dailyStatDao.insertDailyStat(stat)
            } else {
                // Grid re-entry detection: Verify if transition occurred from a different grid. / 网格重入检测：校验是否从不同网格单元发生迁移。
                val lastGrid = currentUserRecord.lastBlurryGridId
                if (lastGrid.isNotEmpty() && lastGrid != blurryGridIndex) {
                    // Logic for revisit increment: Transition from an external grid back to a previously explored area. / 重访递增逻辑：从外部网格迁回已知区域。
                    dao.incrementGridVisit(blurryGridIndex)
                }
            }

            // Update state machine: Current grid becomes the state reference for future transitions. / 更新状态机：当前网格作为后续迁移的状态参考。
            if (currentUserRecord.lastBlurryGridId != blurryGridIndex) {
                currentUserRecord.lastBlurryGridId = blurryGridIndex
                userRecordDao.insertRecord(currentUserRecord) // Persist current state. / 持久化当前状态。
            }

            // =====================================
            // 2. High-precision grid indexing (if operational mode permits). / 高精度网格索引记录（若运行模式允许）。
            // =====================================
            if (isPreciseMode) {
                val preciseGridIndex = GridHelper.getGridIndex(lat, lon, true)
                if (dao.getGrid(preciseGridIndex) == null) {
                    dao.insertGrid(ExploredGrid(preciseGridIndex, 1, 0, timeMs, visitCount = 1))
                }
            }
        }
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): android.app.Notification {
        // 🌟 1. 组装点击通知主体 -> 打开 MainActivity 的 Intent
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            // 这两个 Flag 确保如果 App 已经在后台，会直接唤醒它而不是新建一个页面
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingContentIntent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 🌟 2. 组装点击“切换模式”按钮 -> 触发 Service 的 Intent
        val switchIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_SWITCH_MODE
        }
        val pendingSwitchIntent = PendingIntent.getService(
            this, 1, switchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingContentIntent) // 绑定点击主体事件
            .addAction(
                android.R.drawable.ic_menu_compass, // 按钮图标
                getString(R.string.action_switch_mode), // 按钮文案
                pendingSwitchIntent // 绑定点击按钮事件
            )
            .build()
    }

    private fun getNotificationText() = if (isPreciseMode) getString(R.string.tracking_mode_precise) else getString(R.string.tracking_mode_battery)

    private fun getLocationServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i("LocationTrackingService", "Service destroyed")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        try {
            val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                activityRecognitionClient.removeActivityUpdates(activityPendingIntent)
            }
            unregisterReceiver(activityReceiver)
        } catch (e: Exception) {
            AppLogger.e("LocationTrackingService", "Failed to clean up tracking service", e)
        }

        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.tracking_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.tracking_channel_desc)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}