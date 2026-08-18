package com.yuanqian.autofarm.presentation.view.panel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yuanqian.autofarm.R
import com.yuanqian.autofarm.data.model.CustomFlowConfig
import com.yuanqian.autofarm.data.model.TaskChainNode
import com.yuanqian.autofarm.data.model.TaskTypeInfo
import sh.calvin.reorderable.ReorderableColumn

/**
 * 左侧任务列表（支持模式切换、拖拽排序、勾选、新增任务入口）
 */
@Composable
fun TaskListPanel(
    nodes: List<TaskChainNode>,
    selectedNodeId: String?,
    isEditMode: Boolean,
    isAddingTask: Boolean,
    isProfileMode: Boolean,
    onNodeEnabledChange: (String, Boolean) -> Unit,
    onNodeSelected: (String) -> Unit,
    onNodeMove: (Int, Int) -> Unit,
    onToggleEditMode: () -> Unit,
    onToggleAddingTask: () -> Unit,
    onToggleProfileMode: () -> Unit,
    onAddNode: (TaskTypeInfo) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var addExpanded by remember { mutableStateOf(false) }
    // 宽度由调用方决定：双栏用 IntrinsicSize.Max 收窄列表；单栏用 fillMaxSize 铺满
    Column(modifier = modifier) {
        // 配置选择按钮 - 在编辑任务按钮上方
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleProfileMode() },
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isProfileMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                1.dp,
                if (isProfileMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isProfileMode) 2.dp else 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isProfileMode) Icons.Default.Check else Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isProfileMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isProfileMode) stringResource(R.string.common_done) else stringResource(
                        R.string.panel_task_list_edit_config
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isProfileMode) FontWeight.Bold else FontWeight.Normal,
                    color = if (isProfileMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 编辑任务按钮 - 具备高亮状态
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleEditMode() },
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isEditMode) 2.dp else 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isEditMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isEditMode) stringResource(R.string.common_done) else stringResource(
                        R.string.panel_task_list_edit_tasks
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isEditMode) FontWeight.Bold else FontWeight.Normal,
                    color = if (isEditMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 新增任务按钮 - 仅在编辑模式下显示；点击展开下拉菜单直接选择任务类型
        AnimatedVisibility(
            visible = isEditMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(modifier = Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { addExpanded = !addExpanded },
                        shape = RoundedCornerShape(4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (addExpanded) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        border = if (addExpanded) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        } else {
                            null
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.panel_task_list_add),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    DropdownMenu(expanded = addExpanded, onDismissRequest = { addExpanded = false }) {
                        TaskTypeInfo.entries.filter { !it.hidden }.forEach { typeInfo ->
                            DropdownMenuItem(
                                text = { Text(taskTypeLabel(typeInfo)) },
                                onClick = {
                                    addExpanded = false
                                    onAddNode(typeInfo)
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        ReorderableColumn(
            list = nodes,
            onSettle = { fromIndex, toIndex -> onNodeMove(fromIndex, toIndex) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) { _, node, _ ->
            key(node.id) {
                ReorderableItem {
                    TaskNodeRow(
                        node = node,
                        isSelected = selectedNodeId == node.id,
                        isEditMode = isEditMode,
                        onEnabledChange = { enabled -> onNodeEnabledChange(node.id, enabled) },
                        onSelected = { onNodeSelected(node.id) },
                        modifier = Modifier.longPressDraggableHandle()
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskNodeRow(
    node: TaskChainNode,
    isSelected: Boolean,
    isEditMode: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        ) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .clickable { onSelected() }
                .padding(horizontal = 0.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧整块类型色（仅流程引用节点）：把卡片左侧一块涂成类型色；普通任务不显示
            val flowBarColor = (node.config as? CustomFlowConfig)?.let { fc ->
                // 前缀规则：IMAGE/APP_STATE=判定绿；LOOP/CONJUNCTION/DISJUNCTION=控制黄；其余=执行蓝
                // （新增节点命名遵循前缀即可自动着色，无需改这里）
                when {
                    fc.flowKind.startsWith("IMAGE") || fc.flowKind.startsWith("APP_STATE") -> Color(0xFF2E8B57)
                    fc.flowKind.startsWith("LOOP") || fc.flowKind.startsWith("CONJUNCTION") ||
                        fc.flowKind.startsWith("DISJUNCTION") -> Color(0xFFB58900)
                    else -> Color(0xFF3A6EA5)
                }
            }
            if (flowBarColor != null) {
                Box(
                    Modifier
                        .width(5.dp)
                        .fillMaxHeight()
                        .background(flowBarColor, RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            // 在编辑模式下也可以保留勾选框，或者隐藏以展示纯粹的排序视图
            // 依然显示勾选框以便快速切换状态，但调整间距
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Checkbox(
                    checked = node.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 流程引用节点：显示真实参数摘要（如"等待1000ms"），超长自动换行
                val flowDetail = (node.config as? CustomFlowConfig)?.detail
                if (!flowDetail.isNullOrBlank()) {
                    Text(
                        text = flowDetail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
