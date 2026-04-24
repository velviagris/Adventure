package com.velviagris.adventure

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.velviagris.adventure.data.AdventureDatabase
import com.velviagris.adventure.ui.theme.AdventureTheme
import com.velviagris.adventure.utils.AppLogger
import com.velviagris.adventure.utils.AppPreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        AppLogger.initialize(applicationContext)
        AppLogger.i("MainActivity", "MainActivity created")

        val database = AdventureDatabase.getDatabase(this)
        val appPreferences = AppPreferences(applicationContext)

        setContent {
            AdventureTheme {
                AdventureAppMain(database = database, preferences = appPreferences)
            }
        }
    }
}
