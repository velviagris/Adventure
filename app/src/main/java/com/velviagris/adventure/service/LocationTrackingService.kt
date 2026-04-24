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

    private lateinit var dailyStatDao: DailyStatDao
    private var lastLocation: android.location.Location? = null // 🌟 记录上一个点

    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_ACTIVITY_UPDATE && ActivityRecognitionResult.hasResult(intent)) {
                val result = ActivityRecognitionResult.extractResult(intent)
                result?.let { handleActivityTransition(it.mostProbableActivity) }
            }
        }
    }

    private lateinit var userRecordDao: UserRecordDao
    private var currentUserRecord = UserRecord() // 内存中维护一份热数据

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
                    // 🌟 1. 记录网格 (原有逻辑)
                    recordLocation(location.latitude, location.longitude, location.time)

                    // 🌟 2. 计算与上一个点的物理距离
                    if (lastLocation != null) {
                        val distanceMeters = lastLocation!!.distanceTo(location)
                        if (distanceMeters > 0) {
                            recordDistance(distanceMeters)
                        }
                    }
                    // 更新上一个点
                    lastLocation = location
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isPreciseMode = intent?.getBooleanExtra("EXTRA_IS_PRECISE", false) ?: false
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
            // 1. 累加总距离
            val stat = dailyStatDao.getDailyStat(dateString) ?: DailyStat(dateString)
            stat.totalDistanceKm += distanceKm
            stat.isTrackingActive = true
            dailyStatDao.insertDailyStat(stat)

            // 2. 🌟 极限状态检测：破了我的“单次瞬间位移”记录吗？
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
            // 🌟 1. 记录模糊网格及访问次数计算逻辑
            // =====================================
            val blurryGridIndex = GridHelper.getGridIndex(lat, lon, false)
            val existingBlurry = dao.getGrid(blurryGridIndex)

            if (existingBlurry == null) {
                // 首次来到这个模糊网格，新建记录 (初始访问次数为 1)
                dao.insertGrid(ExploredGrid(blurryGridIndex, 0, 0, timeMs, visitCount = 1))

                // 触发 DailyStat 的全新网格计数
                val dateString = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(timeMs))
                val stat = dailyStatDao.getDailyStat(dateString) ?: DailyStat(dateString)
                stat.newGridsCount += 1
                stat.isTrackingActive = true
                dailyStatDao.insertDailyStat(stat)
            } else {
                // 这个网格以前来过。我们检查是不是“刚刚从其他网格跨越过来的”？
                val lastGrid = currentUserRecord.lastBlurryGridId
                if (lastGrid.isNotEmpty() && lastGrid != blurryGridIndex) {
                    // 🌟 判定成功：从别的网格走回了这片老区域，访问次数 +1！
                    dao.incrementGridVisit(blurryGridIndex)
                }
            }

            // 更新状态机：无论怎样，现在我的当前模糊网格就是它了
            if (currentUserRecord.lastBlurryGridId != blurryGridIndex) {
                currentUserRecord.lastBlurryGridId = blurryGridIndex
                userRecordDao.insertRecord(currentUserRecord) // 持久化状态
            }

            // =====================================
            // 🌟 2. 记录高精网格 (如果是高精模式)
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

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.tracking_notification_title))
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .build()

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
