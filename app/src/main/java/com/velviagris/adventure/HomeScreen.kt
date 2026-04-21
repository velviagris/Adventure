package com.velviagris.adventure

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.velviagris.adventure.service.LocationTrackingService
import com.velviagris.adventure.utils.GeoJsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val isTrackingEnabled by viewModel.isTrackingEnabled.collectAsState()
    val isPreciseMode by viewModel.isPreciseMode.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val globalExploredAreaKm2 by viewModel.exploredAreaKm2.collectAsState()
    val blurryGridsList by viewModel.blurryGrids.collectAsState()
    val preciseGridsList by viewModel.preciseGrids.collectAsState()

    var cityGeoJson by remember { mutableStateOf<JSONObject?>(null) }
    var stateGeoJson by remember { mutableStateOf<JSONObject?>(null) }
    var countryGeoJson by remember { mutableStateOf<JSONObject?>(null) }

    // Pre-resolve strings to use inside Coroutines safely
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
            Toast.makeText(context, toastTrackingStarted, Toast.LENGTH_SHORT).show()
        } else {
            viewModel.toggleTracking(false)
            Toast.makeText(context, toastPermissionRequired, Toast.LENGTH_LONG).show()
        }
    }

    // 初始化读取缓存
    LaunchedEffect(Unit) {
        cityGeoJson = GeoJsonHelper.getCachedBoundary(context, "city")
        stateGeoJson = GeoJsonHelper.getCachedBoundary(context, "state")
        countryGeoJson = GeoJsonHelper.getCachedBoundary(context, "country")
    }

    // ====================================================================
    // 🌟 核心魔法：电子围栏自动刷新机制
    // 当用户的网格数据变化时，检查是否走出了当前的市级边界
    // ====================================================================
    val totalGrids = blurryGridsList.size + preciseGridsList.size
    var lastAutoFetchTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(totalGrids) {
        val now = System.currentTimeMillis()
        // 防抖策略：限制至少 30 秒才允许向定位芯片索要一次快照，防止过度耗电
        if (now - lastAutoFetchTime < 30_000) return@LaunchedEffect

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val lat = location.latitude
                    val lon = location.longitude

                    // 判断当前位置是否还在我们缓存的“城市多边形”内
                    val isOutsideLocal = cityGeoJson == null || !GeoJsonHelper.isPointInPolygon(lat, lon, cityGeoJson!!)

                    if (isOutsideLocal) {
                        lastAutoFetchTime = now
                        coroutineScope.launch {
                            // 越界了！静默在后台下载新区域的数据
                            // 🌟 恢复为 zoom=10，抓取【市级】完整边界
                            cityGeoJson = GeoJsonHelper.downloadAndCacheBoundary(context, lat, lon, 10, "city")
                            stateGeoJson = GeoJsonHelper.downloadAndCacheBoundary(context, lat, lon, 5, "state")
                            countryGeoJson = GeoJsonHelper.downloadAndCacheBoundary(context, lat, lon, 3, "country")
                        }
                    }
                }
            }
        }
    }
    // ====================================================================

    // 🌟 解析本地城市层级名称
    LaunchedEffect(blurryGridsList, preciseGridsList, cityGeoJson) {
        if (cityGeoJson != null) {
            val json = cityGeoJson!!
            val addressObj = json.optJSONObject("address")

            currentCityName = addressObj?.optString("city")?.takeIf { it.isNotEmpty() }
                ?: addressObj?.optString("town")?.takeIf { it.isNotEmpty() }
                        ?: addressObj?.optString("municipality")?.takeIf { it.isNotEmpty() }
                        ?: addressObj?.optString("county")?.takeIf { it.isNotEmpty() }
                        ?: addressObj?.optString("city_district")?.takeIf { it.isNotEmpty() }
                        ?: json.optString("display_name", unknownStr).split(",").firstOrNull()?.trim() ?: unknownStr

            withContext(Dispatchers.Default) {
                val stats = GeoJsonHelper.calculateExplorationStats(blurryGridsList, preciseGridsList, json)
                cityExploredArea = stats.first
                if (stats.second > 0) {
                    cityProgress = stats.first / stats.second
                }

                // 🌟 核心拦截器：如果名字是占位符或未知，坚决不写入数据库！
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
            currentStateName = addressObj?.optString("state")?.takeIf { it.isNotEmpty() }
                ?: addressObj?.optString("province")?.takeIf { it.isNotEmpty() }
                        ?: addressObj?.optString("region")?.takeIf { it.isNotEmpty() }
                        ?: addressObj?.optString("state_district")?.takeIf { it.isNotEmpty() }
                        ?: unknownStateStr

            withContext(Dispatchers.Default) {
                val stats = GeoJsonHelper.calculateExplorationStats(blurryGridsList, preciseGridsList, json)
                stateExploredArea = stats.first
                if (stats.second > 0) {
                    stateProgress = stats.first / stats.second
                }
                // 🌟 核心拦截器：如果名字是占位符或未知，坚决不写入数据库！
                if (currentCityName != unknownStr && currentCityName != context.getString(R.string.region_not_downloaded)) {
                    viewModel.recordRegionVisit(json, 2, currentCityName, cityExploredArea)
                }            }
        }
    }

    LaunchedEffect(blurryGridsList, preciseGridsList, countryGeoJson) {
        if (countryGeoJson != null) {
            val json = countryGeoJson!!
            val addressObj = json.optJSONObject("address")
            currentCountryName = addressObj?.optString("country")?.takeIf { it.isNotEmpty() }
                ?: json.optString("display_name", unknownStr).split(",").lastOrNull()?.trim() ?: unknownStr

            withContext(Dispatchers.Default) {
                val stats = GeoJsonHelper.calculateExplorationStats(blurryGridsList, preciseGridsList, json)
                countryExploredArea = stats.first
                if (stats.second > 0) {
                    countryProgress = stats.first / stats.second
                }
                // 🌟 拦截占位符
                if (currentCountryName != unknownStr && currentCountryName != context.getString(R.string.country_not_downloaded)) {
                    viewModel.recordRegionVisit(json, 4, currentCountryName, countryExploredArea)
                }
            }
        }
    }

    val toastLocUnavailable = stringResource(R.string.toast_location_unavailable)
    val toastNeedLocPerm = stringResource(R.string.toast_need_location_permission)

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.dialog_download_title)) },
            text = { Text(stringResource(R.string.dialog_download_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    isDownloading = true
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                            if (location != null) {
                                coroutineScope.launch {
                                    // 🌟 手动刷新同样改回 Zoom 10 抓取市级边界
                                    cityGeoJson = GeoJsonHelper.downloadAndCacheBoundary(context, location.latitude, location.longitude, 10, "city")
                                    stateGeoJson = GeoJsonHelper.downloadAndCacheBoundary(context, location.latitude, location.longitude, 5, "state")
                                    countryGeoJson = GeoJsonHelper.downloadAndCacheBoundary(context, location.latitude, location.longitude, 3, "country")
                                    isDownloading = false
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
                }) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.dialog_cancel)) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = 8.dp),
                title = { Text(stringResource(R.string.app_name_display), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = Color.Unspecified,
                    navigationIconContentColor = Color.Unspecified,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = Color.Unspecified
                ),
                windowInsets = WindowInsets(0.dp)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
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
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (isTrackingEnabled) stringResource(R.string.tracking_active_title) else stringResource(R.string.tracking_paused_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isTrackingEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.tracking_switch_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isTrackingEnabled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isTrackingEnabled,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                val permissionsToRequest = mutableListOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
                                }

                                val hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                                if (hasLocationPermission) {
                                    viewModel.toggleTracking(true)
                                    val serviceIntent = Intent(context, LocationTrackingService::class.java).apply { putExtra("EXTRA_IS_PRECISE", isPreciseMode) }
                                    ContextCompat.startForegroundService(context, serviceIntent)
                                } else {
                                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
                                }
                            } else {
                                viewModel.toggleTracking(false)
                                context.stopService(Intent(context, LocationTrackingService::class.java))
                            }
                        }
                    )
                }
            }

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (isTrackingEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(stringResource(R.string.accuracy_setting_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (isPreciseMode) stringResource(R.string.accuracy_precise_desc) else stringResource(R.string.accuracy_battery_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isPreciseMode,
                        onCheckedChange = { isChecked ->
                            viewModel.setPreciseMode(isChecked)
                            if (isTrackingEnabled) {
                                val serviceIntent = Intent(context, LocationTrackingService::class.java).apply {
                                    putExtra("EXTRA_IS_PRECISE", isChecked)
                                }
                                ContextCompat.startForegroundService(context, serviceIntent)
                            }
                        }
                    )
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
                    Text(stringResource(R.string.progress_global_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val worldProgress = (globalExploredAreaKm2 / 148940000.0).toFloat()

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(text = stringResource(R.string.global_explored_area_label), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            String.format(stringResource(R.string.format_area_km2), globalExploredAreaKm2),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    LinearProgressIndicator(
                        progress = { worldProgress },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    )
                    Text(
                        String.format(stringResource(R.string.format_progress_global), worldProgress * 100),
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
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
                Text(text = stringResource(R.string.local_explored_area_label), style = MaterialTheme.typography.bodyMedium)
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
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        String.format(stringResource(R.string.format_progress_local), progress * 100),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}