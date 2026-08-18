package com.mas.autofarm.presentation.view.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mas.autofarm.R
import com.mas.autofarm.data.model.CustomFlowConfig
import com.mas.autofarm.data.model.TaskParamProvider
import com.mas.autofarm.presentation.components.ITextField
import java.io.File

/**
 * 通用「自定义流程」任务配置面板：
 * - 本地流程：直接选择工坊保存的流程（FlowEngine 运行，流程信息节点负责启动应用）
 * - 或 MaaCore tasks.json 入口任务名
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CustomFlowConfigPanel(
    config: CustomFlowConfig,
    onConfigChange: (TaskParamProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 列出本地工坊流程
    val localFlows = remember {
        val dir = File(context.filesDir, "workshop")
        dir.listFiles()?.filter { it.isDirectory && File(it, "project.json").exists() }
            ?.map { it.name }?.sorted() ?: emptyList()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.custom_flow_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 本地流程选择
        Text("本地流程（推荐）", style = MaterialTheme.typography.labelMedium)
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = config.localFlowName,
                onValueChange = {},
                readOnly = true,
                placeholder = { Text("选择工坊流程（流程信息节点负责启动应用）") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("（不使用本地流程）") }, onClick = {
                    onConfigChange(config.copy(localFlowName = "")); expanded = false
                })
                localFlows.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        onConfigChange(config.copy(localFlowName = name)); expanded = false
                    })
                }
                if (localFlows.isEmpty()) {
                    DropdownMenuItem(text = { Text("（暂无工坊流程，请先在脚本工坊创建）") }, onClick = { expanded = false })
                }
            }
        }
        if (config.localFlowName.isNotBlank()) {
            Text(
                "将用 FlowEngine 运行「${config.localFlowName}」，流程信息节点负责启动应用到虚拟屏",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // 流程连线/突发详情（排查用）：显示全部连线与突发，并写入会话日志
        if (config.localFlowName.isNotBlank()) {
            val flowInfo = remember(config.localFlowName) {
                runCatching {
                    val proj = kotlinx.serialization.json.Json.decodeFromString(
                        com.mas.autofarm.presentation.view.workshop.FlowProject.serializer(),
                        File(File(context.filesDir, "workshop/${config.localFlowName}"), "project.json").readText()
                    )
                    val nodeName: (String) -> String = { id -> proj.nodes.find { it.id == id }?.name ?: "?" }
                    val linkLines = proj.links.map { l ->
                        val typeLabel = when (l.type) {
                            com.mas.autofarm.presentation.view.workshop.LinkType.SEQUENCE -> "顺序"
                            com.mas.autofarm.presentation.view.workshop.LinkType.YES -> "是"
                            com.mas.autofarm.presentation.view.workshop.LinkType.NO -> "非"
                            com.mas.autofarm.presentation.view.workshop.LinkType.AND -> "与"
                            com.mas.autofarm.presentation.view.workshop.LinkType.OR -> "或"
                        }
                        "[$typeLabel] ${l.fromIds.joinToString("+") { nodeName(it) }} → ${nodeName(l.toId)}"
                    }
                    val burstLines = proj.bursts.map { b ->
                        "🚨${b.name}：判定[${nodeName(b.judgeNodeId)}] 区间[${b.nodeIds.joinToString(",") { nodeName(it) }}] 命中后→[${nodeName(b.hitContinueId)}]" +
                            (b.missContinueId?.let { " 未命中→[${nodeName(it)}]" } ?: "")
                    }
                    Triple(linkLines, burstLines, proj)
                }.getOrNull()
            }
            if (flowInfo != null) {
                val (linkLines, burstLines, _) = flowInfo
                val sessionLogger: com.mas.autofarm.domain.service.MaaSessionLogger =
                    org.koin.java.KoinJavaComponent.get(com.mas.autofarm.domain.service.MaaSessionLogger::class.java)
                LaunchedEffect(config.localFlowName) {
                    sessionLogger.append(
                        "流程「${config.localFlowName}」连线明细：" + (if (linkLines.isEmpty()) "无（按节点顺序执行）" else linkLines.joinToString("；")),
                        com.mas.autofarm.data.model.LogLevel.INFO
                    )
                    if (burstLines.isNotEmpty()) {
                        sessionLogger.append(
                            "流程「${config.localFlowName}」突发明细：" + burstLines.joinToString("；"),
                            com.mas.autofarm.data.model.LogLevel.INFO
                        )
                    }
                }
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🔗 连线明细", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (linkLines.isEmpty()) {
                            Text("（无连线，按节点顺序执行）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            linkLines.forEach { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface) }
                        }
                        Text("🚨 突发明细", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        if (burstLines.isEmpty()) {
                            Text("（无突发）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            burstLines.forEach { Text(it, fontSize = 12.sp, color = Color(0xFFE53935)) }
                        }
                    }
                }
            }
        }
        // 流程节点快照（引用流程时写入）：类型 + 真实参数描述
        if (config.flowKind.isNotBlank()) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "节点类型：${config.flowKind}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = config.detail.ifBlank { "（无详情）" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "参数为流程引用快照，实际执行以工坊流程为准；如需修改请到工坊编辑该流程",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}