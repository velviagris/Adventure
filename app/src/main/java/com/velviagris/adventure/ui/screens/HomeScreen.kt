package com.velviagris.adventure.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.velviagris.adventure.ui.viewmodels.HomeViewModel
import com.velviagris.adventure.R
import com.velviagris.adventure.service.LocationTrackingService
import com.velviagris.adventure.utils.AppLogger
import com.velviagris.adventure.utils.GeoJsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ==========================================
// 有状态容器 (Stateful Wrapper)
// 负责所有与 ViewModel、权限、服务、Context 相关的重量级操作
// ==========================================
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val isTrackingEnabled by viewModel.isTrackingEnabled.collectAsState()
    val isPreciseMode by viewModel.isPreciseMode.collectAsState()
    val userRecord by viewModel.userRecordFlow.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val globalExploredAreaKm2 by viewModel.exploredAreaKm2.collectAsState()
    val blurryGridsList by viewModel.blurryGrids.collectAsState()
    val preciseGridsList by viewModel.preciseGrids.collectAsState()

    var cityGeoJson by remember { mutableStateOf<JSONObject?>(null) }
    var stateGeoJson by remember { mutableStateOf<JSONObject?>(null) }
    var countryGeoJson by remember { mutableStateOf<JSONObject?>(null) }

    val unknownStr = stringResource(R.string.unknown)
    val unknownStateStr = stringResource(R.string.unknown_state)

    var currentCityName by remember { mutableStateOf(context.getString(R.string.region_not_downloaded)) }
    var cityExploredArea by remember { mutableDoubleStateOf(0.0) }
    var cityProgress by remember { mutableStateOf<Double?>(null) }

    var currentStateName by remember { mutableStateOf(context.getString(R.string.state_not_downloaded)) }
    var stateExploredArea by remember { mutableDoubleStateOf(0.0) }
    var stateProgress by remember { mutableStateOf<Double?>(null) }

    var currentCountryName by remember { mutableStateOf(context.getString(R.string.country_not_downloaded)) }
    var countryExploredArea by remember { mutableDoubleStateOf(0.0) }
    var countryProgress by remember { mutableStateOf<Double?>(null) }

    var showDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }

    val toastTrackingStarted = stringResource(R.string.toast_tracking_started)
    val toastPermissionRequired = stringResource(R.string.toast_permission_required)
    val toastLocUnavailable = stringResource(R.string.toast_location_unavailable)
    val toastNeedLocPerm = stringResource(R.string.toast_need_location_permission)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (locationGranted) {
            viewModel.toggleTracking(true)
            val serviceIntent = Intent(context, LocationTrackingService::class.java).apply {
                putExtra("EXTRA_IS_PRECISE", isPreciseMode)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            AppLogger.i("HomeScreen", "Tracking started after permission grant")
            Toast.makeText(context, toastTrackingStarted, Toast.LENGTH_SHORT).show()
        } else {
            viewModel.toggleTracking(false)
            AppLogger.w("HomeScreen", "Tracking start denied because location permission was not granted")
            Toast.makeText(context, toastPermissionRequired, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(isTrackingEnabled) {
        if (isTrackingEnabled) {
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

            if (hasLocationPermission) {
                val serviceIntent = Intent(context, LocationTrackingService::class.java).apply {
                    putExtra("EXTRA_IS_PRECISE", isPreciseMode)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                AppLogger.i("HomeScreen", "Service auto-started from UI state to ensure synchronization")
            } else {
                viewModel.toggleTracking(false)
                AppLogger.w("HomeScreen", "Tracking disabled due to missing permission on startup")
            }
        }
    }

    LaunchedEffect(Unit) {
        cityGeoJson = GeoJsonHelper.getCachedBoundary(context, "city")
        stateGeoJson = GeoJsonHelper.getCachedBoundary(context, "state")
        countryGeoJson = GeoJsonHelper.getCachedBoundary(context, "country")
    }

    LaunchedEffect(userRecord.lastBlurryGridId) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val lat = location.latitude
                    val lon = location.longitude

                    val isOutsideLocal = cityGeoJson == null || !GeoJsonHelper.isPointInPolygon(
                        lat, lon, cityGeoJson!!
                    )

                    if (isOutsideLocal) {
                        AppLogger.i("HomeScreen", "User crossed boundary or cache is empty, refreshing GeoJSON data")
                        coroutineScope.launch {
                            cityGeoJson = GeoJsonHelper.downloadAndCacheBoundary(context, lat, lon, 10, "city")
                            stateGeoJson = GeoJsonHelper.downloadAndCacheBoundary(context, lat, lon, 5, "state")
                            countryGeoJson = GeoJsonHelper.downloadAndCacheBoundary(context, lat, lon, 3, "country")
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(blurryGridsList, preciseGridsList, cityGeoJson) {
        if (cityGeoJson != null) {
            val json = cityGeoJson!!
            val addressObj = json.optJSONObject("address")

            currentCityName = json.optString("name").takeIf { it.isNotEmpty() }
                ?: addressObj?.optString("city")?.takeIf { it.isNotEmpty() }
                        ?: addressObj?.optString("town")?.takeIf { it.isNotEmpty() }
                        ?: json.optString("display_name", unknownStr).split(",").firstOrNull()?.trim() ?: unknownStr

            withContext(Dispatchers.Default) {
                val stats = GeoJsonHelper.calculateExplorationStats(blurryGridsList, preciseGridsList, json)
                cityExploredArea = stats.first
                if (stats.second > 0) cityProgress = stats.first / stats.second

                if (currentCityName != unknownStr && currentCityName != context.getString(R.string.region_not_downloaded)) {
                    viewModel.recordRegionVisit(json, 2, currentCityName, cityExploredArea)
                }
            }
        }
    }

    LaunchedEffect(blurryGridsList, preciseGridsList, stateGeoJson) {
        if (stateGeoJson != null) {
            val json = stateGeoJson!!
            val addressObj = json.optJSONObject("address")
            currentStateName = json.optString("name").takeIf { it.isNotEmpty() }
                ?: addressObj?.optString("state")?.takeIf { it.isNotEmpty() }
                        ?: addressObj?.optString("province")?.takeIf { it.isNotEmpty() }
                        ?: unknownStateStr

            withContext(Dispatchers.Default) {
                val stats = GeoJsonHelper.calculateExplorationStats(blurryGridsList, preciseGridsList, json)
                stateExploredArea = stats.first
                if (stats.second > 0) stateProgress = stats.first / stats.second

                if (currentStateName != unknownStr && currentStateName != context.getString(R.string.region_not_downloaded)) {
                    viewModel.recordRegionVisit(json, 3, currentStateName, stateExploredArea)
                }
            }
        }
    }

    LaunchedEffect(blurryGridsList, preciseGridsList, countryGeoJson) {
        if (countryGeoJson != null) {
            val json = countryGeoJson!!
            val addressObj = json.optJSONObject("address")

            currentCountryName = json.optString("name").takeIf { it.isNotEmpty() }
                ?: addressObj?.optString("country")?.takeIf { it.isNotEmpty() }
                        ?: json.optString("display_name", unknownStr).split(",").lastOrNull()?.trim() ?: unknownStr

            withContext(Dispatchers.Default) {
                val stats = GeoJsonHelper.calculateExplorationStats(blurryGridsList, preciseGridsList, json)
                countryExploredArea = stats.first
                if (stats.second > 0) countryProgress = stats.first / stats.second

                if (currentCountryName != unknownStr && currentCountryName != context.getString(R.string.country_not_downloaded)) {
                    viewModel.recordRegionVisit(json, 4, currentCountryName, countryExploredArea)
                }
            }
        }
    }

    // 将内部的复杂逻辑转化为传递给纯净 UI 的回调函数
    HomeScreenContent(
        isTrackingEnabled = isTrackingEnabled,
        isPreciseMode = isPreciseMode,
        globalExploredAreaKm2 = globalExploredAreaKm2,
        currentCityName = currentCityName,
        cityExploredArea = cityExploredArea,
        cityProgress = cityProgress,
        currentStateName = currentStateName,
        stateExploredArea = stateExploredArea,
        stateProgress = stateProgress,
        currentCountryName = currentCountryName,
        countryExploredArea = countryExploredArea,
        countryProgress = countryProgress,
        isDownloading = isDownloading,
        showRefreshDialog = showDialog,
        onShowRefreshDialogChange = { showDialog = it },
        onConfirmRefresh = {
            showDialog = false
            isDownloading = true
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        coroutineScope.launch {
                            cityGeoJson = GeoJsonHelper.downloadAndCacheBoundary(context, location.latitude, location.longitude, 10, "city")
                            stateGeoJson = GeoJsonHelper.downloadAndCacheBoundary(context, location.latitude, location.longitude, 5, "state")
                            countryGeoJson = GeoJsonHelper.downloadAndCacheBoundary(context, location.latitude, location.longitude, 3, "country")
                            isDownloading = false
                            AppLogger.i("HomeScreen", "Manual boundary refresh completed")
                        }
                    } else {
                        isDownloading = false
                        Toast.makeText(context, toastLocUnavailable, Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                isDownloading = false
                Toast.makeText(context, toastNeedLocPerm, Toast.LENGTH_SHORT).show()
            }
        },
        onTrackingToggleRequest = { isChecked ->
            if (isChecked) {
                val permissionsToRequest = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)

                val hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                if (hasLocationPermission) {
                    viewModel.toggleTracking(true)
                    val serviceIntent = Intent(context, LocationTrackingService::class.java).apply { putExtra("EXTRA_IS_PRECISE", isPreciseMode) }
                    ContextCompat.startForegroundService(context, serviceIntent)
                    AppLogger.i("HomeScreen", "Tracking enabled from home screen switch")
                } else {
                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
            } else {
                viewModel.toggleTracking(false)
                AppLogger.i("HomeScreen", "Tracking disabled from home screen switch")
                context.stopService(Intent(context, LocationTrackingService::class.java))
            }
        },
        onPreciseModeToggleRequest = { isChecked ->
            viewModel.setPreciseMode(isChecked)
            if (isTrackingEnabled) {
                val serviceIntent = Intent(context, LocationTrackingService::class.java).apply { putExtra("EXTRA_IS_PRECISE", isChecked) }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
            AppLogger.i("HomeScreen", "Precision mode switch changed: enabled=$isChecked")
        }
    )
}

// ==========================================
// 无状态核心 UI 界面 (Stateless UI)
// 只有基本的纯参数传入，完全与 ViewModel 解耦，完美支持 @Preview
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenContent(
    isTrackingEnabled: Boolean,
    isPreciseMode: Boolean,
    globalExploredAreaKm2: Double,
    currentCityName: String,
    cityExploredArea: Double,
    cityProgress: Double?,
    currentStateName: String,
    stateExploredArea: Double,
    stateProgress: Double?,
    currentCountryName: String,
    countryExploredArea: Double,
    countryProgress: Double?,
    isDownloading: Boolean,
    showRefreshDialog: Boolean,
    onShowRefreshDialogChange: (Boolean) -> Unit,
    onConfirmRefresh: () -> Unit,
    onTrackingToggleRequest: (Boolean) -> Unit,
    onPreciseModeToggleRequest: (Boolean) -> Unit
) {
    var showTrackingInfoDialog by remember { mutableStateOf(false) }
    var showPreciseInfoDialog by remember { mutableStateOf(false) }

    // ==========================================
    // 弹窗声明区域
    // ==========================================
    if (showRefreshDialog) {
        AlertDialog(
            onDismissRequest = { onShowRefreshDialogChange(false) },
            title = { Text(stringResource(R.string.dialog_download_title)) },
            text = { Text(stringResource(R.string.dialog_download_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmRefresh) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { onShowRefreshDialogChange(false) }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }

    if (showTrackingInfoDialog) {
        AlertDialog(
            onDismissRequest = { showTrackingInfoDialog = false },
            text = { Text(stringResource(R.string.tracking_switch_desc)) },
            confirmButton = {
                TextButton(onClick = { showTrackingInfoDialog = false }) { Text(stringResource(R.string.dialog_confirm)) }
            }
        )
    }

    if (showPreciseInfoDialog) {
        AlertDialog(
            onDismissRequest = { showPreciseInfoDialog = false },
            text = { Text(if (isPreciseMode) stringResource(R.string.accuracy_precise_desc) else stringResource(R.string.accuracy_battery_desc)) },
            confirmButton = {
                TextButton(onClick = { showPreciseInfoDialog = false }) { Text(stringResource(R.string.dialog_confirm)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name_display), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                ),
                windowInsets = WindowInsets(0.dp)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onShowRefreshDialogChange(true) }) {
                if (isDownloading) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.content_desc_update_boundary))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (isTrackingEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isTrackingEnabled) stringResource(R.string.tracking_active_title) else stringResource(R.string.tracking_paused_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isTrackingEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { showTrackingInfoDialog = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Info",
                                tint = if (isTrackingEnabled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                    Switch(
                        checked = isTrackingEnabled,
                        onCheckedChange = onTrackingToggleRequest
                    )
                }
            }

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (isTrackingEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.accuracy_setting_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { showPreciseInfoDialog = true },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Info",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SegmentedButton(
                            selected = !isPreciseMode, // 省电模式 (false)
                            onClick = { onPreciseModeToggleRequest(false) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Text(stringResource(R.string.mode_battery))
                        }
                        SegmentedButton(
                            selected = isPreciseMode, // 高精度模式 (true)
                            onClick = { onPreciseModeToggleRequest(true) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Text(stringResource(R.string.mode_precise))
                        }
                    }
                }
            }

            AreaProgressCard(stringResource(R.string.progress_city_title), currentCityName, cityExploredArea, cityProgress)
            AreaProgressCard(stringResource(R.string.progress_state_title), currentStateName, stateExploredArea, stateProgress)
            AreaProgressCard(stringResource(R.string.progress_country_title), currentCountryName, countryExploredArea, countryProgress)

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.progress_global_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val worldProgress = (globalExploredAreaKm2 / 148940000.0).toFloat()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = stringResource(R.string.global_explored_area_label),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            String.format(
                                stringResource(R.string.format_area_km2),
                                globalExploredAreaKm2
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    LinearProgressIndicator(
                        progress = { worldProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                    Text(
                        String.format(
                            stringResource(R.string.format_progress_global),
                            worldProgress * 100
                        ),
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(84.dp))
        }
    }
}

// 保持不变，它本来就是纯 UI 组件
@Composable
fun AreaProgressCard(
    title: String,
    locationName: String,
    exploredAreaKm2: Double,
    progress: Double?
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = locationName,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = stringResource(R.string.local_explored_area_label),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    String.format(stringResource(R.string.format_area_km2), exploredAreaKm2),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            if (progress != null) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        String.format(
                            stringResource(R.string.format_progress_local),
                            progress * 100
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}


// ==========================================
// @Preview 预览区域
// ==========================================

@Preview(name = "首页预览 - 中文", locale = "zh", showBackground = true)
@Composable
fun HomeScreenPreviewZh() {
    MaterialTheme {
        HomeScreenContent(
            isTrackingEnabled = true,
            isPreciseMode = true,
            globalExploredAreaKm2 = 23.45,
            currentCityName = "广州市",
            cityExploredArea = 12.5,
            cityProgress = 0.05,
            currentStateName = "广东省",
            stateExploredArea = 20.1,
            stateProgress = 0.001,
            currentCountryName = "中国",
            countryExploredArea = 23.45,
            countryProgress = 0.000002,
            isDownloading = false,
            showRefreshDialog = false,
            onShowRefreshDialogChange = {},
            onConfirmRefresh = {},
            onTrackingToggleRequest = {},
            onPreciseModeToggleRequest = {}
        )
    }
}

@Preview(name = "首页预览 - English", locale = "en", showBackground = true)
@Composable
fun HomeScreenPreviewEn() {
    MaterialTheme {
        HomeScreenContent(
            isTrackingEnabled = false,
            isPreciseMode = false,
            globalExploredAreaKm2 = 1234.56,
            currentCityName = "New York City",
            cityExploredArea = 50.0,
            cityProgress = 0.06,
            currentStateName = "New York",
            stateExploredArea = 120.0,
            stateProgress = 0.008,
            currentCountryName = "United States",
            countryExploredArea = 1234.56,
            countryProgress = 0.0001,
            isDownloading = false,
            showRefreshDialog = false,
            onShowRefreshDialogChange = {},
            onConfirmRefresh = {},
            onTrackingToggleRequest = {},
            onPreciseModeToggleRequest = {}
        )
    }
}