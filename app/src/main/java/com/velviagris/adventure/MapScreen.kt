package com.velviagris.adventure

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import com.velviagris.adventure.utils.GridHelper

import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.util.ClickResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MapViewModel) {
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val coroutineScope = rememberCoroutineScope()

    val blurryGrids by viewModel.blurryGrids.collectAsState()
    val preciseGrids by viewModel.preciseGrids.collectAsState()

    val blurryGridsMap = remember(blurryGrids) { blurryGrids.associateBy { it.gridIndex } }
    val preciseGridsMap = remember(preciseGrids) { preciseGrids.associateBy { it.gridIndex } }

    var clickedGridInfo by remember { mutableStateOf<String?>(null) }
    var gridToDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(clickedGridInfo) {
        if (clickedGridInfo != null) {
            delay(3000)
            clickedGridInfo = null
        }
    }

    val allExploredGrids = blurryGrids + preciseGrids
    val globalFogGeoJson by remember(blurryGrids, preciseGrids) {
        derivedStateOf { GridHelper.buildGlobalAdventureGeoJson(blurryGrids, preciseGrids) }
    }

    val blurryPatchGeoJson by remember(blurryGrids, preciseGrids) {
        derivedStateOf { GridHelper.buildBlurryPatchGeoJson(blurryGrids, preciseGrids) }
    }

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(target = Position(longitude = 105.0, latitude = 35.0), zoom = 3.0)
    )

    var hasLocationPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        cameraState.position = CameraPosition(
                            target = Position(longitude = location.longitude, latitude = location.latitude),
                            zoom = 16.0
                        )
                    }
                }
            } catch (e: SecurityException) { }
        }
    }

    val styleUrl = "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"
    val toastSearchingGPS = stringResource(R.string.toast_searching_gps)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.map_title), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                windowInsets = WindowInsets(0.dp)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (hasLocationPermission) {
                        try {
                            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                                if (location != null) {
                                    coroutineScope.launch {
                                        cameraState.animateTo(CameraPosition(target = Position(location.longitude, location.latitude), zoom = 16.5))
                                    }
                                } else {
                                    Toast.makeText(context, toastSearchingGPS, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: SecurityException) { }
                    }
                }
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.content_desc_my_location))
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                MaplibreMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraState = cameraState,
                    baseStyle = BaseStyle.Uri(styleUrl),
                    onMapClick = { position, _ ->
                        val lat = position.latitude
                        val lon = position.longitude

                        val preciseId = GridHelper.getGridIndex(lat, lon, isPrecise = true)
                        val preciseGrid = preciseGridsMap[preciseId]

                        if (preciseGrid != null) {
                            val timeStr = formatTime(context, preciseGrid.exploreTime)
                            clickedGridInfo = context.getString(R.string.grid_info_precise, timeStr)
                            ClickResult.Consume
                        } else {
                            val blurryId = GridHelper.getGridIndex(lat, lon, isPrecise = false)
                            val blurryGrid = blurryGridsMap[blurryId]

                            if (blurryGrid != null) {
                                val timeStr = formatTime(context, blurryGrid.exploreTime)
                                clickedGridInfo = context.getString(R.string.grid_info_blurry, timeStr)
                                ClickResult.Consume
                            } else {
                                clickedGridInfo = null
                                ClickResult.Pass
                            }
                        }
                    },
                    onMapLongClick = { position, _ ->
                        val lat = position.latitude
                        val lon = position.longitude

                        // 优先检查最高层的高精网格
                        val preciseId = GridHelper.getGridIndex(lat, lon, isPrecise = true)
                        val preciseGrid = preciseGridsMap[preciseId]

                        if (preciseGrid != null) {
                            gridToDelete = preciseId
                            ClickResult.Consume
                        } else {
                            // 如果没有高精网格，则检查底层的模糊网格
                            val blurryId = GridHelper.getGridIndex(lat, lon, isPrecise = false)
                            val blurryGrid = blurryGridsMap[blurryId]

                            if (blurryGrid != null) {
                                gridToDelete = blurryId
                                ClickResult.Consume
                            } else {
                                ClickResult.Pass
                            }
                        }
                    }
                ) {
                    val fogSource = rememberGeoJsonSource(data = GeoJsonData.JsonString(globalFogGeoJson))
                    FillLayer(
                        id = "global-fog-layer",
                        source = fogSource,
                        color = const(Color(0x80212121)),
                        outlineColor = const(Color.Transparent)
                    )

                    if (blurryGrids.isNotEmpty()) {
                        val blurrySource = rememberGeoJsonSource(data = GeoJsonData.JsonString(blurryPatchGeoJson))
                        FillLayer(
                            id = "blurry-layer",
                            source = blurrySource,
                            color = const(Color(0x6ACCCCCC)),
                            outlineColor = const(Color.Transparent)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = clickedGridInfo != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
            ) {
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Text(
                        text = clickedGridInfo ?: "",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (gridToDelete != null) {
                AlertDialog(
                    onDismissRequest = { gridToDelete = null },
                    title = { Text(stringResource(R.string.dialog_delete_grid_title)) },
                    text = { Text(stringResource(R.string.dialog_delete_grid_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            gridToDelete?.let { viewModel.deleteGrid(it) }
                            gridToDelete = null // 关闭弹窗
                        }) {
                            Text(stringResource(R.string.dialog_confirm)) // 复用你现有的确定文本
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { gridToDelete = null }) {
                            Text(stringResource(R.string.dialog_cancel)) // 复用你现有的取消文本
                        }
                    }
                )
            }
        }
    }
}

private fun formatTime(context: Context, timestamp: Long): String {
    if (timestamp <= 0L) return context.getString(R.string.time_early_record)
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}