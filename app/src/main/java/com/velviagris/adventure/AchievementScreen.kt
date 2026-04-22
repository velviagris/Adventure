package com.velviagris.adventure

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.velviagris.adventure.utils.AchievementDef
import com.velviagris.adventure.utils.AchievementRegistry
import java.text.SimpleDateFormat
import java.util.*

data class AchievementViewState(
    val def: AchievementDef,
    val currentProgress: Double,
    val currentLevel: Int,
    val unlockHistory: Map<Int, Long>
)

// ==========================================
// 🌟 2. 核心 UI 界面
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementScreen(
    viewModel: AchievementViewModel,
    currentArea: Double,
    currentDistance: Double,
    currentPreciseCount: Int,
    currentBlurryCount: Int,
    currentCityCount: Int,
    currentCountryCount: Int
) {
    val context = LocalContext.current

    // 🌟 在后台用公共引擎统一跑体检
    LaunchedEffect(currentArea, currentDistance, currentPreciseCount, currentBlurryCount, currentCityCount, currentCountryCount) {
        val metrics = mapOf(
            "area" to currentArea,
            "distance" to currentDistance,
            "precise" to currentPreciseCount.toDouble(),
            "blurry" to currentBlurryCount.toDouble(),
            "city" to currentCityCount.toDouble(),
            "country" to currentCountryCount.toDouble()
        )
        viewModel.syncAchievements(context, metrics)
    }

    // 拿到数据库里所有已解锁的成就
    val unlockedList by viewModel.groupedAchievements.collectAsState()
    var selectedState by remember { mutableStateOf<AchievementViewState?>(null) }

    // 用 stringResource 完美获取国际化的标题
    val displayStates = remember(unlockedList, currentArea, currentDistance, currentPreciseCount, currentBlurryCount, currentCityCount, currentCountryCount) {
        AchievementRegistry.definitions.map { def ->
            val progress = when (def.categoryId) {
                "area" -> currentArea
                "precise" -> currentPreciseCount.toDouble()
                "blurry" -> currentBlurryCount.toDouble()
                "distance" -> currentDistance
                "city" -> currentCityCount.toDouble()
                "country" -> currentCountryCount.toDouble()
                else -> 0.0
            }

            // 通过上下文和资源 ID 反查（解决历史数据匹配问题）
            val groupHistory = unlockedList.find { it.categoryTitle == context.getString(def.titleResId) }?.history ?: emptyList()
            val historyMap = groupHistory.associate { it.level to it.earnedTime }
            val currentLevel = groupHistory.maxOfOrNull { it.level } ?: 0

            AchievementViewState(def, progress, currentLevel, historyMap)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.achievement_title), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                windowInsets = WindowInsets(0.dp)
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            items(displayStates) { state ->
                AchievementMedalItem(state = state) { selectedState = state }
            }
        }

        selectedState?.let { state ->
            AchievementDetailDialog(state = state, onDismiss = { selectedState = null })
        }
    }
}

// ==========================================
// 🌟 3. 网格中的牌子组件 (支持灰态锁定)
// ==========================================
@Composable
fun AchievementMedalItem(state: AchievementViewState, onClick: () -> Unit) {
    val isLocked = state.currentLevel == 0
    val color = if (isLocked) Color.Gray.copy(alpha = 0.4f) else getLevelColor(state.currentLevel)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(64.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    tint = if (isLocked) Color.DarkGray else Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 🌟 使用国际化字符串
        Text(
            text = stringResource(state.def.titleResId),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (isLocked) Color.Gray else MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

// ==========================================
// 🌟 4. 详情弹窗 (进度条 + 目标条件预测)
// ==========================================
@Composable
fun AchievementDetailDialog(state: AchievementViewState, onDismiss: () -> Unit) {
    val isLocked = state.currentLevel == 0
    val color = if (isLocked) Color.Gray.copy(alpha = 0.4f) else getLevelColor(state.currentLevel)

    val nextLevel = (state.currentLevel + 1).coerceAtMost(5)
    val nextTarget = state.def.thresholds[nextLevel - 1]

    val isMaxLevel = state.currentLevel == 5
    val progressRatio = if (isMaxLevel) 1f else (state.currentProgress / nextTarget).toFloat().coerceIn(0f, 1f)

    // 🌟 获取国际化单位
    val unitStr = stringResource(state.def.unitResId)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = null,
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Box(modifier = Modifier.size(120.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(100.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        Icon(imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.EmojiEvents, contentDescription = null, tint = if (isLocked) Color.DarkGray else Color.White, modifier = Modifier.size(60.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                // 🌟 使用国际化标题
                Text(stringResource(state.def.titleResId), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                val currentTierStr = if (isLocked) stringResource(R.string.achievement_locked) else getTierName(state.currentLevel)
                Text(currentTierStr, color = if(isLocked) Color.Gray else color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.height(16.dp))

                val currentFmt = if (unitStr == "km²" || unitStr == "km") String.format("%.2f", state.currentProgress) else state.currentProgress.toInt().toString()
                val targetFmt = if (unitStr == "km²" || unitStr == "km") String.format("%.2f", nextTarget) else nextTarget.toInt().toString()

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("当前进度", style = MaterialTheme.typography.labelMedium)
                    Text(if (isMaxLevel) "已满级" else "$currentFmt / $targetFmt $unitStr", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(progress = { progressRatio }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)), color = if (isLocked) Color.Gray else color)

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(16.dp))

                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                    for (lvl in 1..5) {
                        val isTierUnlocked = state.unlockHistory.containsKey(lvl)
                        val tierColor = if (isTierUnlocked) getLevelColor(lvl) else Color.LightGray
                        val earnedTime = state.unlockHistory[lvl]

                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(tierColor))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(getTierName(lvl), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if(isTierUnlocked) MaterialTheme.colorScheme.onSurface else Color.Gray)
                                    // 🌟 使用国际化名称
                                    Text(stringResource(state.def.tierNameResIds[lvl-1]), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }

                            if (isTierUnlocked && earnedTime != null) {
                                Text(sdf.format(Date(earnedTime)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            } else {
                                val reqStr = if (unitStr == "km²" || unitStr == "km") String.format("%.0f", state.def.thresholds[lvl-1]) else state.def.thresholds[lvl-1].toInt().toString()
                                Text("目标: $reqStr $unitStr", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    )
}

// 辅助方法保持不变
fun getLevelColor(level: Int): Color {
    return when (level) {
        1 -> Color(0xFFCD7F32)
        2 -> Color(0xFFC0C0C0)
        3 -> Color(0xFFFFD700)
        4 -> Color(0xFF00E5FF)
        else -> Color(0xFF111111)
    }
}

@Composable
fun getTierName(level: Int): String {
    return when (level) {
        1 -> stringResource(R.string.achievement_tier_1)
        2 -> stringResource(R.string.achievement_tier_2)
        3 -> stringResource(R.string.achievement_tier_3)
        4 -> stringResource(R.string.achievement_tier_4)
        else -> stringResource(R.string.achievement_tier_5)
    }
}