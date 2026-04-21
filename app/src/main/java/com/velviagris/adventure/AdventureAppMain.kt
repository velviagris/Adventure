package com.velviagris.adventure

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.velviagris.adventure.data.AdventureDatabase
import com.velviagris.adventure.utils.AppPreferences

sealed class Screen(val route: String, val titleResId: Int, val icon: ImageVector) {
    object Home : Screen("home", R.string.nav_home, Icons.Filled.Home)
    object Map : Screen("map", R.string.nav_map, Icons.Filled.LocationOn)
    object Achievement : Screen("achievement", R.string.nav_achievement, Icons.Filled.EmojiEvents)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Filled.Settings)
}

@Composable
fun AdventureAppMain(database: AdventureDatabase, preferences: AppPreferences) {
    val navController = rememberNavController()
    val items = listOf(Screen.Home, Screen.Map, Screen.Achievement, Screen.Settings)

    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(
            database.exploredGridDao(),
            preferences,
            database.achievementDao(),
            database.dailyStatDao(),
            database.regionProgressDao()
        )
    )
    val mapViewModel: MapViewModel = viewModel(
        factory = MapViewModelFactory(database.exploredGridDao())
    )
    val achievementViewModel: AchievementViewModel = viewModel(
        factory = AchievementViewModelFactory(database.achievementDao())
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(database.exploredGridDao(), preferences) as T
            }
        }
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(imageVector = screen.icon, contentDescription = null) },
                        label = { Text(stringResource(screen.titleResId)) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen(viewModel = homeViewModel) }
            composable(Screen.Map.route) { MapScreen(viewModel = mapViewModel) }
            // 🌟 传接完整的 6 个指标给成就页
            composable(Screen.Achievement.route) {
                val area by homeViewModel.exploredAreaKm2.collectAsState()
                val precise by homeViewModel.preciseGrids.collectAsState()
                val blurry by homeViewModel.blurryGrids.collectAsState()
                val distance by homeViewModel.totalDistanceKm.collectAsState()
                val cities by homeViewModel.cityCount.collectAsState()
                val countries by homeViewModel.countryCount.collectAsState()

                AchievementScreen(
                    viewModel = achievementViewModel,
                    currentArea = area,
                    currentDistance = distance,
                    currentPreciseCount = precise.size,
                    currentBlurryCount = blurry.size,
                    currentCityCount = cities,
                    currentCountryCount = countries
                )
            }
            composable(Screen.Settings.route) { SettingsScreen(viewModel = settingsViewModel) }
        }
    }
}