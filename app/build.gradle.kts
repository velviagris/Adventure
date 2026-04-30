plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.1.10-1.0.30"
}

android {
    namespace = "com.velviagris.adventure"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.velviagris.adventure"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 🌟 核心瘦身：只打包 64 位 ARM 架构（市面上 99% 的现代手机都是这个）
            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters += listOf("arm64-v8a")
                // 如果你要兼容极其老旧的低端机，可以加上 "armeabi-v7a"
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // 1. Navigation Compose (用于底部导航)
    implementation(libs.androidx.navigation.compose)
    // 2. Material 3 (如果新建项目已包含可跳过，确保版本较新以支持更丰富的 M3 特性)
    implementation(libs.material3)
    // 3. 预留：Uber H3 (空间网格算法)
//    implementation(libs.h3)
    // 4. 预留：MapLibre GL (用于矢量地图渲染，替代传统 OSMDroid)
    implementation(libs.android.sdk)
    // 2. 🌟 替换为全新的官方 MapLibre Compose 封装库
    implementation(libs.compose.maplibre.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.play.services.location)
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version") // 提供协程和 Flow 支持
    ksp("androidx.room:room-compiler:$room_version")     // 注解处理器
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.jts.core)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}