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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

// ==========================================
// 🌟 1. 成就总字典 (Registry)
// 定义所有可用的成就、名称、单位和每一级的阈值
// ==========================================
data class AchievementDef(
    val categoryId: String,
    val title: String,
    val unit: String,
    val thresholds: List<Double>,
    val tierNames: List<String>
)

object AchievementRegistry {
    val definitions = listOf(
        AchievementDef(
            categoryId = "area",
            title = "大地漫步者",
            unit = "km²",
            thresholds = listOf(1.0, 10.0, 100.0, 1000.0, 10000.0),
            tierNames = listOf("见微知著", "城市游侠", "广阔天地", "洲际旅人", "世界引擎")
        ),
        AchievementDef(
            categoryId = "precise",
            title = "像素猎人",
            unit = "个",
            thresholds = listOf(100.0, 1000.0, 5000.0, 20000.0, 100000.0),
            tierNames = listOf("像素学徒", "寻迹者", "细嗅蔷薇", "全视之眼", "夸克微雕师")
        ),
        AchievementDef(
            categoryId = "blurry",
            title = "迷雾先锋",
            unit = "个",
            thresholds = listOf(10.0, 50.0, 200.0, 1000.0, 5000.0),
            tierNames = listOf("启程", "破雾者", "乘风破浪", "巡天者", "苍穹之影")
        ),
        AchievementDef(
            categoryId = "distance",
            title = "行者无疆",
            unit = "km",
            thresholds = listOf(10.0, 100.0, 500.0, 2000.0, 10000.0), // 10公里铜牌，1万公里黑牌
            tierNames = listOf("初识路途", "百里游侠", "千里独行", "万里跋涉", "夸父追日")
        ),
        AchievementDef(
            categoryId = "city",
            title = "城市收集者",
            unit = "座",
            thresholds = listOf(1.0, 5.0, 20.0, 100.0, 500.0),
            tierNames = listOf("初来乍到", "走南闯北", "神州漫步", "百城领主", "大旅行家")
        ),
        AchievementDef(
            categoryId = "country",
            title = "环球旅行家",
            unit = "个",
            thresholds = listOf(1.0, 3.0, 10.0, 50.0, 197.0),
            tierNames = listOf("本国居民", "跨越国界", "护照盖满", "四海为家", "地球漫游者")
        )
    )
}

// 供 UI 渲染的聚合状态
data class AchievementViewState(
    val def: AchievementDef,
    val currentProgress: Double,
    val currentLevel: Int, // 0 表示完全没解锁
    val unlockHistory: Map<Int, Long> // Level -> 时间戳
)

// ==========================================
// 🌟 2. 核心 UI 界面
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementScreen(
    viewModel: AchievementViewModel,
    currentArea: Double,
    currentDistance: Double,       // 🌟 新增
    currentPreciseCount: Int,
    currentBlurryCount: Int,
    currentCityCount: Int,         // 🌟 新增
    currentCountryCount: Int       // 🌟 新增
) {
    // 拿到数据库里所有已解锁的成就
    val unlockedList by viewModel.groupedAchievements.collectAsState()
    var selectedState by remember { mutableStateOf<AchievementViewState?>(null) }

    // 将静态字典、实时数据、数据库历史 三者合一，生成 UI 状态流
    val displayStates = remember(unlockedList, currentArea, currentPreciseCount, currentBlurryCount) {
        AchievementRegistry.definitions.map { def ->
            // 获取实时进度
            val progress = when (def.categoryId) {
                "area" -> currentArea
                "precise" -> currentPreciseCount.toDouble()
                "blurry" -> currentBlurryCount.toDouble()
                "distance" -> currentDistance
                "city" -> currentCityCount.toDouble()
                "country" -> currentCountryCount.toDouble()
                else -> 0.0
            }

            // 从数据库结果中匹配这组牌子的历史记录
            val groupHistory = unlockedList.find { it.categoryTitle == def.title }?.history ?: emptyList()
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
        },
//        floatingActionButton = {
//            ExtendedFloatingActionButton(
//                onClick = { viewModel.addTestAchievement() },
//                icon = { Icon(Icons.Filled.Star, contentDescription = null) },
//                text = { Text(stringResource(R.string.achievement_test_add)) }
//            )
//        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            items(displayStates) { state ->
                AchievementMedalItem(state = state) {
                    selectedState = state
                }
            }
        }

        // 弹窗展示详情
        selectedState?.let { state ->
            AchievementDetailDialog(
                state = state,
                onDismiss = { selectedState = null }
            )
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
        Box(
            modifier = Modifier.size(80.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(color),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    tint = if (isLocked) Color.DarkGray else Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.def.title,
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

    // 计算下一级的目标
    val nextLevel = (state.currentLevel + 1).coerceAtMost(5)
    val nextTarget = state.def.thresholds[nextLevel - 1]

    // 如果全满(黑牌)，进度条直接 100%
    val isMaxLevel = state.currentLevel == 5
    val progressRatio = if (isMaxLevel) 1f else (state.currentProgress / nextTarget).toFloat().coerceIn(0f, 1f)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = null,
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                // 1. 顶部大徽章
                Box(
                    modifier = Modifier.size(120.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(100.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.EmojiEvents,
                            contentDescription = null,
                            tint = if (isLocked) Color.DarkGray else Color.White,
                            modifier = Modifier.size(60.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(state.def.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                val currentTierStr = if (isLocked) "尚未解锁" else getTierName(state.currentLevel)
                Text(currentTierStr, color = if(isLocked) Color.Gray else color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.height(16.dp))

                // 2. 🌟 当前进度条
                val currentFmt = if (state.def.unit == "km²") String.format("%.2f", state.currentProgress) else state.currentProgress.toInt().toString()
                val targetFmt = if (state.def.unit == "km²") String.format("%.2f", nextTarget) else nextTarget.toInt().toString()

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("当前进度", style = MaterialTheme.typography.labelMedium)
                    Text(if (isMaxLevel) "已达到最高级别" else "$currentFmt / $targetFmt ${state.def.unit}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress = { progressRatio },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = if (isLocked) Color.Gray else color
                )

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(16.dp))

                // 3. 🌟 解锁条件与历史时间轴 (完整展示 5 级)
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                    for (lvl in 1..5) {
                        val isTierUnlocked = state.unlockHistory.containsKey(lvl)
                        val tierColor = if (isTierUnlocked) getLevelColor(lvl) else Color.LightGray
                        val earnedTime = state.unlockHistory[lvl]

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 历史圆点
                                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(tierColor))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(getTierName(lvl), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if(isTierUnlocked) MaterialTheme.colorScheme.onSurface else Color.Gray)
                                    Text(state.def.tierNames[lvl-1], style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }

                            // 右侧显示时间或目标
                            if (isTierUnlocked && earnedTime != null) {
                                Text(sdf.format(Date(earnedTime)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            } else {
                                val reqStr = if (state.def.unit == "km²") String.format("%.0f", state.def.thresholds[lvl-1]) else state.def.thresholds[lvl-1].toInt().toString()
                                Text("目标: $reqStr ${state.def.unit}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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