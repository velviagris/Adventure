# 🌍 Adventure

**Adventure** is a location-based "Fog of World" style footprint tracking application. It silently records your real-world trajectory in the background and clears the "fog of the unknown" from the map. Whether you are commuting daily or traveling across the globe, Adventure serves as your faithful footprint chronicler.

## 📖 Introduction

The app divides the Earth into millions of grids. As you physically move, the app reveals your explored areas in real-time. It features an intelligent battery-saving location engine, precise spherical polygon area calculations, automatic administrative boundary downloads, and a multi-dimensional achievement system.

All your footprint data is stored **locally** on your device, with full JSON backup/restore.

---

## ✨ Key Features

### 🗺️ Dual-Precision Tracking System
To balance battery consumption and recording accuracy, the app offers two seamlessly switchable modes:
* **📍 Precise Mode**: Records highly detailed street-level grids (Zoom 18). Ideal for walking tours, hiking, and sightseeing.
* **🔋 Battery Saver Mode**: Records coarse district/village-level grids (Zoom 14). Highly battery-efficient, perfect for daily commuting, driving, or high-speed rail travel.

### 🔋 Smart Hibernation & Seamless Recording
* **Activity Recognition Integration**: The app intelligently detects your motion state. If the device remains "Still" for a certain period, GPS tracking is automatically paused to enter a deep sleep mode. The moment you start moving (walking, cycling, driving), the app wakes up and resumes recording.

### 🏙️ Real-World Boundaries & Progress Tracking
* **Automatic Boundary Parsing**: Downloads real GeoJSON boundaries of your current City, State/Province, and Country via the OpenStreetMap Nominatim API.
* **Precise Area Calculation**: Abandons rough BBox calculations in favor of true Spherical Polygon Area Line Integrals and Ray-Casting algorithms to accurately calculate your explored area and percentage.

### 🏆 Rich Achievement Engine
* **Offline Evaluation Engine**: Automatically calculates metrics such as total area, distance, new grid streaks, and revisit counts.
* **Tiers**: Unlock achievements from Bronze to Onyx. Badges include *Earth Walker, Globetrotter, Pathfinder Fever, Chronos Leap*, and more.

### 📊 Daily Summary
* Powered by `WorkManager`, the app pushes a daily summary notification at 21:00, reporting the exact square kilometers you deeply explored or passed through that day.

### 🗄️ Complete Data Sovereignty
* **Local-First**: All grids, achievements, and regions are stored locally via Room Database.
* **Import / Export**: One-click JSON backup and restore functionality.
* **Built-in Logger**: Custom `AppLogger` that catches crashes and writes operational logs, exportable directly from the settings.

---

## 🛠️ Tech Stack

Built following modern Android development practices and Native design guidelines:
* **UI Framework**: Jetpack Compose + Material Design 3 (Dynamic Color support)
* **Map Rendering**: MapLibre GL Native (Vector tiles, high-performance masking)
* **Architecture**: MVVM + Kotlin Coroutines & Flow (Reactive Data)
* **Local Storage**: Room Database (SQLite) + DataStore (Preferences)
* **Background Services**: Foreground Service + FusedLocationProviderClient + ActivityRecognitionClient
* **Network & Tasks**: OkHttp, WorkManager
