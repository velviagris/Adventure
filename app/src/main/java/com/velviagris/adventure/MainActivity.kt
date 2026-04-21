package com.velviagris.adventure

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.velviagris.adventure.data.AdventureDatabase
import com.velviagris.adventure.ui.theme.AdventureTheme // 或者是你项目默认生成的 Theme 名字
import com.velviagris.adventure.utils.AppPreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // 👇 1. 在这里初始化 Room 数据库
        val database = AdventureDatabase.getDatabase(this)
        val appPreferences = AppPreferences(this.applicationContext)

        setContent {
            AdventureTheme {
                // 👇 2. 把数据库传进主界面
                AdventureAppMain(database = database, preferences = appPreferences)
            }
        }
    }
}