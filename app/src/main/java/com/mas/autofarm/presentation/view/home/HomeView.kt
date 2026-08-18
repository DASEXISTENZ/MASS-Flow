package com.mas.autofarm.presentation.view.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.items
import org.koin.compose.koinInject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mas.autofarm.data.preferences.AppSettingsManager
import com.mas.autofarm.domain.state.MaaExecutionState
import com.mas.autofarm.manager.PermissionManager
import com.mas.autofarm.manager.RemoteServiceManager
import com.mas.autofarm.presentation.viewmodel.HomeViewModel
import com.mas.autofarm.presentation.viewmodel.UpdateViewModel
import org.koin.androidx.compose.koinViewModel
import java.io.File

/**
 * MAS 自研首页：通用自动化工作流平台。
 * 状态总览 + 快捷操作 + 最近流程（自研布局，风格延续丰富清晰的设计语言）。
 */
@Composable
fun HomeView(
    navController: NavController,
    viewModel: HomeViewModel = koinViewModel(),
    updateViewModel: UpdateViewModel = koinViewModel(),
    permissionManager: PermissionManager = koinInject(),
    appSettingsManager: AppSettingsManager = koinInject(),
    onOpenWorkshop: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val resourceVersion by updateViewModel.currentResourceVersion.collectAsStateWithLifecycle()
    val appVersion = updateViewModel.currentAppVersion
    val runMode by appSettingsManager.runMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 任务执行状态
    val compositionService: com.mas.autofarm.domain.service.MaaCompositionService = koinInject()
    val execState by compositionService.state.collectAsStateWithLifecycle()

    // 最近流程（工坊项目）
    val recentFlows = remember {
        val dir = File(context.filesDir, "workshop")
        dir.listFiles()?.filter { it.isDirectory && File(it, "project.json").exists() }
            ?.sortedByDescending { File(it, "project.json").lastModified() }
            ?.take(5) ?: emptyList()
    }

    // 服务状态
    val serviceConnected = RemoteServiceManager.state.value is RemoteServiceManager.ServiceState.Connected
    val serviceText = when (RemoteServiceManager.state.value) {
        is RemoteServiceManager.ServiceState.Connected -> "已连接"
        is RemoteServiceManager.ServiceState.Connecting -> "连接中"
        is RemoteServiceManager.ServiceState.Disconnected -> "未连接"
        else -> "异常"
    }
    val serviceColor = when (RemoteServiceManager.state.value) {
        is RemoteServiceManager.ServiceState.Connected -> Color(0xFF4CAF50)
        is RemoteServiceManager.ServiceState.Connecting -> Color(0xFFFF9800)
        else -> Color(0xFFE53935)
    }
    // 任务状态
    val taskText = when (execState) {
        MaaExecutionState.RUNNING -> "运行中"
        MaaExecutionState.STARTING -> "启动中"
        MaaExecutionState.STOPPING -> "停止中"
        MaaExecutionState.ERROR -> "异常"
        else -> "空闲"
    }
    val taskColor = when (execState) {
        MaaExecutionState.RUNNING -> Color(0xFF4CAF50)
        MaaExecutionState.STARTING -> Color(0xFFFF9800)
        MaaExecutionState.ERROR -> Color(0xFFE53935)
        else -> Color(0xFF90A4AE)
    }
    // 资源状态
    val resOk = uiState.resourceInitState is com.mas.autofarm.domain.state.ResourceInitState.Ready
    val resText = if (resOk) "就绪" else "未就绪"
    val resColor = if (resOk) Color(0xFF4CAF50) else Color(0xFF90A4AE)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ===== 顶部标题 =====
        item {
            Column {
                Text("MAS", color = MaterialTheme.colorScheme.onSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "通用自动化工作流平台  v${appVersion ?: "-"}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
            }
        }
        // ===== 状态卡片（2x2） =====
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusCard("服务", serviceText, serviceColor, Modifier.weight(1f))
                StatusCard("任务", taskText, taskColor, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusCard("资源", resText, resColor, Modifier.weight(1f))
                StatusCard("模式", runMode.name, Color(0xFF64B5F6), Modifier.weight(1f))
            }
        }
        // ===== 快捷操作 =====
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickCard(
                    title = "▶ 开始任务",
                    subtitle = "后台任务",
                    color = Color(0xFF4CAF50),
                    modifier = Modifier.weight(1f),
                ) { navController.navigate(com.mas.autofarm.constant.Routes.BACKGROUND_TASK) }
                QuickCard(
                    title = "🧰 脚本工坊",
                    subtitle = "制作工作流",
                    color = Color(0xFF3A6EA5),
                    modifier = Modifier.weight(1f),
                ) { onOpenWorkshop() }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickCard(
                    title = "⏱ 定时任务",
                    subtitle = "自动执行",
                    color = Color(0xFFB58900),
                    modifier = Modifier.weight(1f),
                ) { navController.navigate(com.mas.autofarm.constant.Routes.SCHEDULE) }
                QuickCard(
                    title = "⚙ 设置",
                    subtitle = "权限与偏好",
                    color = Color(0xFF607D8B),
                    modifier = Modifier.weight(1f),
                ) { navController.navigate(com.mas.autofarm.constant.Routes.SETTINGS) }
            }
        }
        // ===== 最近流程 =====
        item {
            Text("最近流程", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (recentFlows.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        "还没有流程，去脚本工坊创建第一个工作流",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(recentFlows.size) { idx ->
                val f = recentFlows[idx]
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenWorkshop() },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .background(Color(0xFF3A6EA5), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text("📋", fontSize = 18.sp) }
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(f.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "节点:${readFlowNodeCount(f)} · 点击在工坊打开",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
        // ===== 系统信息 =====
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("资源版本", resourceVersion ?: "-")
                    InfoRow("运行模式", runMode.name)
                    InfoRow("应用版本", appVersion ?: "-")
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun StatusCard(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
                Spacer(Modifier.size(6.dp))
                Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun QuickCard(title: String, subtitle: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.25f)),
        modifier = modifier.clickable { onClick() },
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), fontSize = 13.sp)
    }
}

private fun readFlowNodeCount(dir: File): Int {
    return runCatching {
        val p = kotlinx.serialization.json.Json.decodeFromString(
            com.mas.autofarm.presentation.view.workshop.FlowProject.serializer(),
            File(dir, "project.json").readText()
        )
        p.nodes.size
    }.getOrDefault(0)
}