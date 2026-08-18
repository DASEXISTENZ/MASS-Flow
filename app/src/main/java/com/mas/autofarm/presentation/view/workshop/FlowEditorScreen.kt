package com.mas.autofarm.presentation.view.workshop
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import java.io.File
import java.util.UUID

/**
 * 原生工坊编辑器（逻辑链独立版）：
 * - 节点纯：时间/图像/动作
 * - 逻辑在线上：是(YES)/非(NO)/与(AND)/或(OR)，与/或可多开端汇聚
 * - 连线模式：选开端（多选）→ 选类型 → 点末端 → 生成逻辑链
 */
@Composable
fun FlowEditorScreen(
    projectName: String,
    onExit: () -> Unit,
    onLockChange: (Boolean) -> Unit = {},
) {
    // 编辑期间锁定底部栏滑动切换
    DisposableEffect(Unit) {
        onLockChange(true)
        onDispose { onLockChange(false) }
    }
    val context = LocalContext.current
    val sessionLogger: com.mas.autofarm.domain.service.MaaSessionLogger =
        org.koin.java.KoinJavaComponent.get(com.mas.autofarm.domain.service.MaaSessionLogger::class.java)
    // 打开时加载已保存的项目
    val loadedProj = remember(projectName) {
        runCatching {
            val f = File(context.filesDir, "workshop/$projectName/project.json")
            if (f.exists()) {
                kotlinx.serialization.json.Json.decodeFromString(
                    FlowProject.serializer(), f.readText()
                )
            } else null
        }.getOrNull()
    }
    var nodes by remember { mutableStateOf(loadedProj?.nodes ?: emptyList()) }
    var links by remember { mutableStateOf(loadedProj?.links ?: emptyList()) }
    var bursts by remember { mutableStateOf(loadedProj?.bursts ?: emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var panelExpanded by remember { mutableStateOf(false) }
    var projectTitle by remember { mutableStateOf(projectName) }
    // 历史栈（节点+连线快照）
    var history by remember { mutableStateOf(listOf<Pair<List<FlowNode>, List<FlowLink>>>()) }
    var redoHistory by remember { mutableStateOf(listOf<Pair<List<FlowNode>, List<FlowLink>>>()) }
    var dirty by remember { mutableStateOf(false) }
    val rsm by lazy { com.mas.autofarm.manager.RemoteServiceManager }
    // 引擎运行状态
    var engineLog by remember { mutableStateOf("") }
    var engineRunning by remember { mutableStateOf(false) }
    var engineScope by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 节点分类下拉栏状态（执行/判定/控制）
    var execExpanded by remember { mutableStateOf(false) }
    var judgeExpanded by remember { mutableStateOf(false) }
    var controlExpanded by remember { mutableStateOf(false) }
    // 连线模式状态（顺序自由：类型/开端可先选后选）
    var linkingMode by remember { mutableStateOf(false) }
    var linkStarts by remember { mutableStateOf(listOf<String>()) }
    var pendingType by remember { mutableStateOf<LinkType?>(null) }
    var awaitingEnd by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<FlowLink?>(null) }
    // 画布缩放/平移
    var canvasScale by remember { mutableStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }
    // 突发判定编辑状态
    var burstMode by remember { mutableStateOf(false) }
    var burstNodes by remember { mutableStateOf(listOf<String>()) }
    var burstJudgeId by remember { mutableStateOf<String?>(null) }
    var burstHitId by remember { mutableStateOf<String?>(null) }
    var burstMissId by remember { mutableStateOf<String?>(null) }
    var burstStage by remember { mutableStateOf("judge") }
    var burstAlwaysOn by remember { mutableStateOf(true) }
    var showCustomPicker by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var customRefreshTick by remember { mutableStateOf(0) } // 自定义节点增删后刷新下拉栏
    // 流动效果：虚线相位动画
    val flowTransition = rememberInfiniteTransition(label = "flow")
    val flowPhase by flowTransition.animateFloat(
        initialValue = 0f,
        targetValue = 26f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 600, easing = LinearEasing)),
        label = "flowPhase",
    )

    if (nodes.isEmpty()) {
        // 新项目：初始节点放屏幕中心
        val config = LocalConfiguration.current
        val density = LocalDensity.current.density
        val cx = (config.screenWidthDp * density) / 2f - 85f * density
        val cy = (config.screenHeightDp * density) / 2f - 36f * density
        nodes = listOf(
            FlowNode(id = UUID.randomUUID().toString(), name = "流程信息", kind = FlowNodeKind.INFO, x = cx, y = cy)
        )
    }

    val selectedNode = nodes.find { it.id == selectedId }

    fun updateNode(id: String, transform: (FlowNode) -> FlowNode) {
        nodes = nodes.map { if (it.id == id) transform(it) else it }
    }

    fun autoSaveToFile() {
        runCatching {
            val project = FlowProject(name = projectTitle, nodes = nodes, links = links, bursts = bursts)
            val dir = File(context.filesDir, "workshop/$projectTitle")
            dir.mkdirs()
            File(dir, "project.json").writeText(
                kotlinx.serialization.json.Json { prettyPrint = true }
                    .encodeToString(FlowProject.serializer(), project)
            )
        }.onFailure { e ->
            android.util.Log.e("FlowEditor", "autoSave FAILED: ${e.message}")
        }
    }

    fun commit() {
        history = (history + listOf(nodes to links)).takeLast(50)
        redoHistory = emptyList()
        dirty = true
        autoSaveToFile()
    }

    fun undo() {
        if (history.isEmpty()) return
        redoHistory = (redoHistory + listOf(nodes to links)).takeLast(50)
        val (n, l) = history.last()
        nodes = n
        links = l
        history = history.dropLast(1)
        dirty = true
        autoSaveToFile()
    }

    fun redo() {
        if (redoHistory.isEmpty()) return
        history = (history + listOf(nodes to links)).takeLast(50)
        val (n, l) = redoHistory.last()
        nodes = n
        links = l
        redoHistory = redoHistory.dropLast(1)
        dirty = true
        autoSaveToFile()
    }

    fun saveNow() {
        runCatching {
            val project = FlowProject(name = projectTitle, nodes = nodes, links = links, bursts = bursts)
            val dir = File(context.filesDir, "workshop/$projectTitle")
            dir.mkdirs()
            File(dir, "project.json").writeText(
                kotlinx.serialization.json.Json { prettyPrint = true }
                    .encodeToString(FlowProject.serializer(), project)
            )
            dirty = false
            Toast.makeText(context, "已保存：$projectTitle", Toast.LENGTH_SHORT).show()
        }.onFailure { e ->
            Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun addNode(kind: FlowNodeKind) {
        if (kind == FlowNodeKind.INFO) return // INFO 节点由系统强制第一个
        commit()
        val name = when (kind) {
            FlowNodeKind.INFO -> "流程"
            FlowNodeKind.TIME -> "等待"
            FlowNodeKind.IMAGE -> "识别"
            FlowNodeKind.ACTION -> "动作"
            FlowNodeKind.LOOP -> "循环"
            FlowNodeKind.APP_STATE -> "应用"
            FlowNodeKind.TAP -> "点击"
            FlowNodeKind.SWIPE -> "滑动"
            FlowNodeKind.WAIT -> "等待"
            FlowNodeKind.BACK -> "返回"
            FlowNodeKind.INPUT -> "输入"
            FlowNodeKind.LOOP_START -> "循环起点"
            FlowNodeKind.CONJUNCTION -> "合取(全部满足)"
            FlowNodeKind.DISJUNCTION -> "析取(任一满足)"
            FlowNodeKind.LOOP_END -> "循环终点"
        }
        // 细分执行节点：默认动作参数
        val defaultAction = when (kind) {
            FlowNodeKind.TAP -> FlowAction.Tap(0, 0, 60)
            FlowNodeKind.SWIPE -> FlowAction.Swipe(0, 0, 0, 0, 200)
            FlowNodeKind.BACK -> FlowAction.Back(1)
            FlowNodeKind.INPUT -> FlowAction.Input("")
            else -> null
        }
        nodes = nodes + FlowNode(
            id = UUID.randomUUID().toString(),
            name = "$name${nodes.size + 1}",
            kind = kind,
            action = defaultAction ?: FlowAction.Wait(500),
            x = 40f + (nodes.size % 3) * 60f,
            y = 220f + (nodes.size / 3) * 60f,
        )
    }

    fun addCustomNode(tpl: com.mas.autofarm.presentation.view.workshop.CustomNodeTemplate) {
        commit()
        // kind 按模板分类映射：判定→IMAGE(绿)、控制→LOOP(黄)、执行→ACTION(蓝)，保证颜色/分类显示一致
        val kindByCategory = when (tpl.category) {
            "JUDGE" -> FlowNodeKind.IMAGE
            "CONTROL" -> FlowNodeKind.LOOP
            else -> FlowNodeKind.ACTION
        }
        nodes = nodes + FlowNode(
            id = UUID.randomUUID().toString(),
            name = tpl.name,
            kind = kindByCategory,
            customNodeId = tpl.id,
            customParams = tpl.params.associate { it.key to it.default },
            x = 40f + (nodes.size % 3) * 60f,
            y = 220f + (nodes.size / 3) * 60f,
        )
    }

    fun addLink(toId: String) {
        // 点末端：暂存待确定（可继续调整）
        val type = pendingType ?: return
        val froms = if (type == LinkType.SEQUENCE || type == LinkType.YES || type == LinkType.NO) {
            linkStarts.take(1)
        } else {
            linkStarts
        }
        if (froms.isEmpty()) return
        pendingLink = FlowLink(id = "pending", type = type, fromIds = froms, toId = toId)
    }

    fun confirmLink() {
        val link = pendingLink ?: return
        // 起点校验：执行节点只能用顺序线（不能产生是/非信号）；连合取/析取/循环终点（顺序线）放行
        val from = nodes.find { it.id == link.fromIds.firstOrNull() }
        val target = nodes.find { it.id == link.toId }
        if (from != null) {
            val isExec = from.kind == FlowNodeKind.INFO || from.kind == FlowNodeKind.TIME ||
                from.kind == FlowNodeKind.WAIT || from.kind == FlowNodeKind.ACTION ||
                from.kind == FlowNodeKind.TAP || from.kind == FlowNodeKind.SWIPE ||
                from.kind == FlowNodeKind.BACK || from.kind == FlowNodeKind.INPUT
            if (isExec && link.type != LinkType.SEQUENCE) {
                Toast.makeText(context, "执行节点不产生判定信号，只能用「顺序」线", Toast.LENGTH_SHORT).show()
                return
            }
        }
        // 终点校验：执行节点只能一条进线（汇聚须经合取/析取）；循环终点最多一条进线
        val existingIn = links.count { it.toId == link.toId }
        if (target != null) {
            val isExec = target.kind == FlowNodeKind.INFO || target.kind == FlowNodeKind.TIME ||
                target.kind == FlowNodeKind.WAIT || target.kind == FlowNodeKind.ACTION ||
                target.kind == FlowNodeKind.TAP || target.kind == FlowNodeKind.SWIPE ||
                target.kind == FlowNodeKind.BACK || target.kind == FlowNodeKind.INPUT
            if (isExec && existingIn > 0) {
                Toast.makeText(context, "执行节点只能一条进线，多路汇聚请用合取/析取衔接", Toast.LENGTH_SHORT).show()
                return
            }
            if (target.kind == FlowNodeKind.LOOP_END && existingIn > 0) {
                Toast.makeText(context, "循环终点最多一条进线（多条件请经合取/析取并联合并）", Toast.LENGTH_SHORT).show()
                return
            }
        }
        commit()
        links = links + link.copy(id = UUID.randomUUID().toString())
        pendingLink = null
        linkStarts = emptyList()
        pendingType = null
        awaitingEnd = false
        // 保持连线模式不自动关闭，方便连续连线（用户手动点"取消"退出）
        dirty = true
    }

    /** 判定是否为循环判定（电路模型）：从判定出发沿信号线，只穿过合取/析取，能否"接通"到循环终点；
     *  执行节点=断路（阻断信号），路径上出现执行节点即断 */
    fun isLoopJudgePath(judgeId: String): Boolean {
        val frontier = mutableListOf(judgeId)
        val visited = mutableSetOf<String>()
        while (frontier.isNotEmpty()) {
            val cur = frontier.removeAt(0)
            if (!visited.add(cur)) continue
            // 从当前节点沿出线寻找
            links.filter { it.fromIds.contains(cur) }.forEach { link ->
                val toKind = nodes.find { it.id == link.toId }?.kind ?: return@forEach
                if (toKind == FlowNodeKind.LOOP_END) return true
                // 只允许穿过合取/析取（信号节点）；执行节点=断路，不展开
                if (toKind == FlowNodeKind.CONJUNCTION || toKind == FlowNodeKind.DISJUNCTION) {
                    frontier += link.toId
                }
            }
        }
        return false
    }

    /** 连线规则校验：返回 null=允许，否则返回禁止提示（在选类型时拦截） */
    fun linkTypeBlockReason(type: LinkType): String? {
        val startId = linkStarts.firstOrNull() ?: return null
val n = nodes.find { it.id == startId } ?: return null
        val cn = when (n.kind) {
            FlowNodeKind.INFO -> "流程信息"
            FlowNodeKind.TIME, FlowNodeKind.WAIT -> "时间/等待"
            FlowNodeKind.IMAGE -> "图像识别"
            FlowNodeKind.ACTION, FlowNodeKind.TAP -> "点击"
            FlowNodeKind.SWIPE -> "滑动"
            FlowNodeKind.BACK -> "返回"
            FlowNodeKind.INPUT -> "输入"
            FlowNodeKind.LOOP -> "循环"
            FlowNodeKind.LOOP_START -> "循环起点"
            FlowNodeKind.APP_STATE -> "应用状态"
            FlowNodeKind.CONJUNCTION -> "合取"
            FlowNodeKind.DISJUNCTION -> "析取"
            FlowNodeKind.LOOP_END -> "循环终点"
        }
        val customCat = if (n.customNodeId.isNotBlank())
            com.mas.autofarm.presentation.view.workshop.CustomNodeStore.find(context, n.customNodeId)?.category
        else null
        val isJudge = n.kind == FlowNodeKind.IMAGE || n.kind == FlowNodeKind.APP_STATE ||
            customCat == "JUDGE" || customCat == "CONTROL"
        val isLoop = n.kind == FlowNodeKind.LOOP
        val isControl = n.kind == FlowNodeKind.CONJUNCTION || n.kind == FlowNodeKind.DISJUNCTION ||
            n.kind == FlowNodeKind.LOOP_END
        return when (type) {
            LinkType.YES, LinkType.NO ->
                if (isJudge || isLoop || isControl) null
                else "「$cn」是执行节点，没有判定结果，不能用「是/非」链，请用「顺序」链"
            LinkType.SEQUENCE ->
                if (isJudge) "「$cn」是判定节点，请用「是/非」链（命中走「是」，未命中走「非」）"
                else if (isLoop) "「$cn」循环请用「是/非」链（是=进循环体，非=退出循环）"
                else null
            else -> null
        }
    }

    fun linkColor(type: LinkType): Color = when (type) {
        LinkType.SEQUENCE -> Color(0xFF90A4AE)
        LinkType.YES -> Color(0xFF4CAF50)
        LinkType.NO -> Color(0xFFFDD835)
        LinkType.AND -> Color(0xFFFF9800)
        LinkType.OR -> Color(0xFF2196F3)
    }

    // 自定义节点管理面板
    if (showCustomPicker) {
        var customList by remember(customRefreshTick) {
            mutableStateOf(com.mas.autofarm.presentation.view.workshop.CustomNodeStore.listAll(context))
        }
        var importMsg by remember { mutableStateOf<String?>(null) }
        var menuTarget by remember { mutableStateOf<com.mas.autofarm.presentation.view.workshop.CustomNodeTemplate?>(null) }
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val ok = com.mas.autofarm.presentation.view.workshop.CustomNodeStore.import(context, text)
                    importMsg = if (ok) "导入成功" else "导入失败：模板格式错误"
                    customRefreshTick++
                    customList = com.mas.autofarm.presentation.view.workshop.CustomNodeStore.listAll(context)
                }.onFailure { e -> importMsg = "导入失败：${e.message}" }
            }
        }
        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            val t = menuTarget
            if (uri != null && t != null) {
                runCatching {
                    com.mas.autofarm.presentation.view.workshop.CustomNodeStore.exportJson(context, t.id)?.let {
                        context.contentResolver.openOutputStream(uri)?.use { os -> os.write(it.toByteArray()) }
                        Toast.makeText(context, "已导出：${t.name}.json", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            menuTarget = null
        }
        AlertDialog(
            onDismissRequest = { showCustomPicker = false },
            title = { Text("🧩 自定义节点（全局通用）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    importMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
                    Text(
                        "点击=添加节点到画布；长按=导出/删除。导出流程时会自动附带用到的自定义节点。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (customList.isEmpty()) {
                        Text("暂无自定义节点。点「本地新建」按模板创建，或「导入文件」导入 .json 模板。", fontSize = 12.sp)
                    }
                    customList.forEach { tpl ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = { addCustomNode(tpl); showCustomPicker = false },
                                    onLongClick = { menuTarget = tpl },
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(tpl.name + "（" + tpl.categoryLabel() + "）", fontWeight = FontWeight.Medium)
                                Text(tpl.description.ifBlank { tpl.command }, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                            if (com.mas.autofarm.presentation.view.workshop.CustomNodeStore.isUserCustom(context, tpl.id)) {
                                TextButton(onClick = {
                                    com.mas.autofarm.presentation.view.workshop.CustomNodeStore.delete(context, tpl.id)
                                    Toast.makeText(context, "已删除：${tpl.name}", Toast.LENGTH_SHORT).show()
                                    customRefreshTick++
                                    customList = com.mas.autofarm.presentation.view.workshop.CustomNodeStore.listAll(context)
                                }) { Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                            } else {
                                Text("内置", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { showCreateDialog = true }) { Text("本地新建") }
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("导入文件") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomPicker = false }) { Text("关闭") }
            },
        )
        // 长按管理：导出文件 / 删除
        menuTarget?.let { t ->
            AlertDialog(
                onDismissRequest = { menuTarget = null },
                title = { Text(t.name) },
                text = { Text("管理自定义节点（${t.categoryLabel()}，全局通用）") },
                confirmButton = {
                    Row {
                        TextButton(onClick = { exportLauncher.launch("${t.name}.json") }) { Text("导出文件") }
                        TextButton(onClick = {
                            com.mas.autofarm.presentation.view.workshop.CustomNodeStore.delete(context, t.id)
                            Toast.makeText(context, "已删除：${t.name}", Toast.LENGTH_SHORT).show()
                            customRefreshTick++
                            customList = com.mas.autofarm.presentation.view.workshop.CustomNodeStore.listAll(context)
                            menuTarget = null
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                },
                dismissButton = { TextButton(onClick = { menuTarget = null }) { Text("取消") } },
            )
        }
    }

    // 本地新建自定义节点
    if (showCreateDialog) {
        var cName by remember { mutableStateOf("") }
        var cCategory by remember { mutableStateOf("EXECUTE") }
        var cCommand by remember { mutableStateOf("sleep 1000  # 等待1秒（时间节点样式）") }
        var cDesc by remember { mutableStateOf("") }
        var createMsg by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("本地自定义节点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = cName, onValueChange = { cName = it }, label = { Text("节点名称") }, modifier = Modifier.fillMaxWidth())
                    Text("节点类型", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("EXECUTE" to "执行", "JUDGE" to "判定", "CONTROL" to "控制").forEach { (v, l) ->
                            FilterChip(selected = cCategory == v, onClick = { cCategory = v }, label = { Text(l) })
                        }
                    }
                    OutlinedTextField(
                        value = cCommand, onValueChange = { cCommand = it },
                        label = { Text("节点代码（Shell 命令）") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                        supportingText = {
                            Text("参考：时间节点=sleep 1000；点击=input tap 500 800；判定类：退出码0=成功→是线，非0→非线", fontSize = 10.sp)
                        },
                    )
                    OutlinedTextField(value = cDesc, onValueChange = { cDesc = it }, label = { Text("描述（可选）") }, modifier = Modifier.fillMaxWidth())
                    createMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = com.mas.autofarm.presentation.view.workshop.CustomNodeStore.create(
                        context, cName.trim(), cCategory, cCommand.trim(), cDesc.trim()
                    )
                    if (ok) {
                        showCreateDialog = false
                        customRefreshTick++
                        Toast.makeText(context, "已创建自定义节点", Toast.LENGTH_SHORT).show()
                    } else {
                        createMsg = "保存失败：名称或代码不能为空"
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("取消") } },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 52.dp),
    ) {
        // 空白层：点击取消选中 + 双指缩放 + 单指平移画布
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        canvasScale = (canvasScale * zoom).coerceIn(0.3f, 3f)
                        canvasOffset += pan
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { _ ->
                        selectedId = null
                        panelExpanded = false
                        if (linkingMode) {
                            linkStarts = emptyList()
                            pendingType = null
                            awaitingEnd = false
                            pendingLink = null
                        }
                        if (burstMode) {
                            burstNodes = emptyList()
                            burstJudgeId = null
                            burstHitId = null
                            burstMissId = null
                            burstStage = "judge"
                        }
                    }
                }
        ) {}
        // 网格背景（世界坐标：随缩放平移延伸，铺满屏幕）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 40f
            val sc = canvasScale
            val ox = canvasOffset.x
            val oy = canvasOffset.y
            val w = size.width
            val h = size.height
            // 可视范围的世界坐标
            val worldX0 = (-ox) / sc
            val worldY0 = (-oy) / sc
            val worldX1 = (w - ox) / sc
            val worldY1 = (h - oy) / sc
            var wx = kotlin.math.floor(worldX0 / step) * step
            while (wx <= worldX1) {
                var wy = kotlin.math.floor(worldY0 / step) * step
                while (wy <= worldY1) {
                    val sx = wx * sc + ox
                    val sy = wy * sc + oy
                    drawCircle(color = Color(0x22FFFFFF), radius = 1.5f, center = Offset(sx, sy))
                    wy += step
                }
                wx += step
            }
        }
        // 画布内容（连线+节点）缩放层
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = canvasScale
                    scaleY = canvasScale
                    translationX = canvasOffset.x
                    translationY = canvasOffset.y
                }
        ) {
        // 连线层：端点统一为节点中心（用密度换算 170dp/72dp）
        val density = LocalDensity.current.density
        val nw = 170f * density
        val nh = 72f * density
        val dashDotBase = floatArrayOf(14f, 5f, 3f, 5f)
        val dashBase = floatArrayOf(8f, 6f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 流动：相位随时间变化（负号=从开端流向末端）
            val phase = -flowPhase
            val dashDot = PathEffect.dashPathEffect(dashDotBase, phase)
            val dash = PathEffect.dashPathEffect(dashBase, phase)
            val dashStatic = PathEffect.dashPathEffect(dashBase) // 开端间虚线：静止
            links.forEach { link ->
                val to = nodes.find { it.id == link.toId }
                if (to != null) {
                    val end = Offset(to.x + nw / 2f, to.y + nh / 2f) // 末端节点中心
                    when (link.type) {
                        LinkType.SEQUENCE -> {
                            // 顺序：普通实线箭头（蓝灰色），表示顺序执行
                            val from = nodes.find { it.id == link.fromIds.firstOrNull() }
                            if (from != null) {
                                val a = Offset(from.x + nw / 2f, from.y + nh / 2f)
                                val c = linkColor(LinkType.SEQUENCE)
                                drawLine(c, a, end, 3f)
                                // 末端小箭头
                                val angle = kotlin.math.atan2((end.y - a.y).toDouble(), (end.x - a.x).toDouble()).toFloat()
                                val len = 10f
                                val baseX = end.x - len * kotlin.math.cos(angle.toDouble()).toFloat()
                                val baseY = end.y - len * kotlin.math.sin(angle.toDouble()).toFloat()
                                val wing = 6f
                                drawLine(
                                    c, Offset(baseX, baseY),
                                    Offset(end.x - len * 0.5f * kotlin.math.cos((angle + 0.6f).toDouble()).toFloat() - wing * 0.5f,
                                        end.y - len * 0.5f * kotlin.math.sin((angle + 0.6f).toDouble()).toFloat() - wing * 0.5f), 2f)
                                drawLine(
                                    c, Offset(baseX, baseY),
                                    Offset(end.x - len * 0.5f * kotlin.math.cos((angle - 0.6f).toDouble()).toFloat() - wing * 0.5f,
                                        end.y - len * 0.5f * kotlin.math.sin((angle - 0.6f).toDouble()).toFloat() - wing * 0.5f), 2f)
                            }
                        }
                        LinkType.YES -> {
                            // 是：只有流动加粗的">>>"（无实线），方向开端→末端
                            val from = nodes.find { it.id == link.fromIds.firstOrNull() }
                            if (from != null) {
                                val a = Offset(from.x + nw / 2f, from.y + nh / 2f)
                                val len = kotlin.math.hypot((end.x - a.x).toDouble(), (end.y - a.y).toDouble()).toFloat()
                                if (len > 30) {
                                    val angle = kotlin.math.atan2((end.y - a.y).toDouble(), (end.x - a.x).toDouble()).toFloat()
                                    val paint = android.graphics.Paint().apply {
                                        color = android.graphics.Color.parseColor("#FF4CAF50")
                                        textSize = 30f
                                        textAlign = android.graphics.Paint.Align.CENTER
                                        style = android.graphics.Paint.Style.STROKE
                                        strokeWidth = 3f
                                        strokeJoin = android.graphics.Paint.Join.ROUND
                                    }
                                    // 正向流动（从开端向末端）
                                    var d = (flowPhase + 10f) % 50f
                                    while (d < len) {
                                        val t = d / len
                                        val px = a.x + (end.x - a.x) * t
                                        val py = a.y + (end.y - a.y) * t
                                        drawContext.canvas.nativeCanvas.save()
                                        drawContext.canvas.nativeCanvas.translate(px, py)
                                        drawContext.canvas.nativeCanvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
                                        drawContext.canvas.nativeCanvas.drawText(">>>", 0f, 0f, paint)
                                        drawContext.canvas.nativeCanvas.restore()
                                        d += 50f
                                    }
                                }
                            }
                        }
                        LinkType.NO -> {
                            // 非：与是线同款流动加粗">>>"（黄色），方向开端→末端
                            val from = nodes.find { it.id == link.fromIds.firstOrNull() }
                            if (from != null) {
                                val a = Offset(from.x + nw / 2f, from.y + nh / 2f)
                                val len = kotlin.math.hypot((end.x - a.x).toDouble(), (end.y - a.y).toDouble()).toFloat()
                                if (len > 30) {
                                    val angle = kotlin.math.atan2((end.y - a.y).toDouble(), (end.x - a.x).toDouble()).toFloat()
                                    val paint = android.graphics.Paint().apply {
                                        color = android.graphics.Color.parseColor("#FFFDD835")
                                        textSize = 30f
                                        textAlign = android.graphics.Paint.Align.CENTER
                                        style = android.graphics.Paint.Style.STROKE
                                        strokeWidth = 3f
                                        strokeJoin = android.graphics.Paint.Join.ROUND
                                    }
                                    // 正向流动（从开端向末端）
                                    var d = (flowPhase + 10f) % 50f
                                    while (d < len) {
                                        val t = d / len
                                        val px = a.x + (end.x - a.x) * t
                                        val py = a.y + (end.y - a.y) * t
                                        drawContext.canvas.nativeCanvas.save()
                                        drawContext.canvas.nativeCanvas.translate(px, py)
                                        drawContext.canvas.nativeCanvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
                                        drawContext.canvas.nativeCanvas.drawText(">>>", 0f, 0f, paint)
                                        drawContext.canvas.nativeCanvas.restore()
                                        d += 50f
                                    }
                                }
                            }
                        }
                        LinkType.AND, LinkType.OR -> {
                            // 与/或：每个开端各连一条点横线到末端；开端之间虚线表示逻辑关系
                            val starts = link.fromIds.mapNotNull { id -> nodes.find { it.id == id } }
                            if (starts.isNotEmpty()) {
                                val c = linkColor(link.type)
                                starts.forEach { from ->
                                    drawLine(c, Offset(from.x + nw / 2f, from.y + nh / 2f), end,
                                        if (link.type == LinkType.AND) 4f else 3f, pathEffect = dashDot)
                                }
                                // 开端之间：低亮度虚线两两相连（静止，黄=与、蓝=或）
                                val dimColor = c.copy(alpha = 0.5f)
                                for (i in 0 until starts.size - 1) {
                                    for (j in i + 1 until starts.size) {
                                        val a = starts[i]
                                        val b = starts[j]
                                        drawLine(dimColor,
                                            Offset(a.x + nw / 2f, a.y + nh / 2f),
                                            Offset(b.x + nw / 2f, b.y + nh / 2f),
                                            2f, pathEffect = dashStatic)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 突发：红色波浪线（加粗）+ 区间顺序线（动效）+ 判定→命中/未命中
            val burstLineColor = Color(0xFFE53935).copy(alpha = 0.55f)
            bursts.forEach { burst ->
                val judge = nodes.find { it.id == burst.judgeNodeId }
                // 判定 → 命中/未命中继续：实线（红）
                if (judge != null) {
                    val jc = Offset(judge.x + nw / 2f, judge.y + nh / 2f)
                    burst.hitContinueId?.let { id -> nodes.find { n -> n.id == id } }?.let { hit ->
                        drawLine(burstLineColor, jc, Offset(hit.x + nw / 2f, hit.y + nh / 2f), 3f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), phase))
                    }
                    burst.missContinueId?.let { id -> nodes.find { n -> n.id == id } }?.let { miss ->
                        drawLine(burstLineColor, jc, Offset(miss.x + nw / 2f, miss.y + nh / 2f), 3f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), phase))
                    }
                }
                // 区间顺序线（123...，红色动效）
                for (i in 0 until burst.nodeIds.size - 1) {
                    val na = nodes.find { it.id == burst.nodeIds[i] }
                    val nb = nodes.find { it.id == burst.nodeIds[i + 1] }
                    if (na != null && nb != null) {
                        drawLine(
                            burstLineColor,
                            Offset(na.x + nw / 2f, na.y + nh / 2f),
                            Offset(nb.x + nw / 2f, nb.y + nh / 2f),
                            3f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), phase),
                        )
                    }
                }
            }
            // 预览线（仅连线模式且有待确定线时渲染）
            if (linkingMode) pendingLink?.let { pl ->
                val to = nodes.find { it.id == pl.toId } ?: return@let
                val end = Offset(to.x + nw / 2f, to.y + nh / 2f)
                val c = linkColor(pl.type)
                pl.fromIds.mapNotNull { id -> nodes.find { it.id == id } }.forEach { from ->
                    drawLine(c, Offset(from.x + nw / 2f, from.y + nh / 2f), end, 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), phase))
                }
            }
        }
        // 节点层
        nodes.forEach { node ->
            // 突发角色：后缀 + 边框（含设置中实时预览）
            val bIdx = bursts.indexOfFirst { it.nodeIds.contains(node.id) || it.judgeNodeId == node.id || it.hitContinueId == node.id || it.missContinueId == node.id }
            val b = if (bIdx >= 0) bursts[bIdx] else null
            val setting = burstMode && (burstJudgeId == node.id || burstNodes.contains(node.id) || burstHitId == node.id || burstMissId == node.id)
            val nodeSuffix = when {
                burstMode && burstJudgeId == node.id -> "🚦*"
                burstMode && burstNodes.contains(node.id) -> "⚠️*"
                burstMode && burstHitId == node.id -> "🔵*"
                burstMode && burstMissId == node.id -> "🔴*"
                b != null && b.judgeNodeId == node.id -> "🚦${bIdx + 1}"
                b != null && b.hitContinueId == node.id -> "🔵${bIdx + 1}.1.1"
                b != null && b.missContinueId == node.id -> "🔴${bIdx + 1}.1.0"
                b != null -> "⚠️${bIdx + 1}.${b.nodeIds.indexOfFirst { it == node.id } + 1}"
                else -> ""
            }
            val bRole = when {
                burstMode && burstJudgeId == node.id -> BurstBorderRole.JUDGE
                burstMode && burstNodes.contains(node.id) -> BurstBorderRole.RANGE
                burstMode && burstHitId == node.id -> BurstBorderRole.HIT
                burstMode && burstMissId == node.id -> BurstBorderRole.MISS
                b != null && b.judgeNodeId == node.id -> BurstBorderRole.JUDGE
                b != null && b.hitContinueId == node.id -> BurstBorderRole.HIT
                b != null && b.missContinueId == node.id -> BurstBorderRole.MISS
                b != null && b.nodeIds.contains(node.id) -> BurstBorderRole.RANGE
                else -> BurstBorderRole.NONE
            }
            // 节点外层：offset + 选中外框 + 四角直角
            val nodeBurstMark = bursts.any {
                it.nodeIds.contains(node.id) || it.judgeNodeId == node.id || it.hitContinueId == node.id || it.missContinueId == node.id
            } || (burstMode && (burstNodes.contains(node.id) || burstJudgeId == node.id || burstHitId == node.id || burstMissId == node.id))
            val isSel = node.id == selectedId || linkStarts.contains(node.id) || burstNodes.contains(node.id) || setting
            Box(
                Modifier
                    .offset { IntOffset(node.x.toInt(), node.y.toInt()) }
                    .size(width = 170.dp, height = 72.dp)
            ) {
                // 选中外扩高亮框（不盖内边框）
                if (isSel) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                            .border(3.dp, Color.White, RoundedCornerShape(12.dp))
                    )
                }
                // 四角红直角（突发节点）
                if (nodeBurstMark) {
                    Canvas(Modifier.fillMaxSize()) {
                        val corner = 14f
                        val thick = 4f
                        val c = Color(0xFFE53935)
                        drawLine(c, Offset(0f, corner), Offset(0f, 0f), thick)
                        drawLine(c, Offset(0f, 0f), Offset(corner, 0f), thick)
                        drawLine(c, Offset(size.width - corner, 0f), Offset(size.width, 0f), thick)
                        drawLine(c, Offset(size.width, 0f), Offset(size.width, corner), thick)
                        drawLine(c, Offset(0f, size.height - corner), Offset(0f, size.height), thick)
                        drawLine(c, Offset(0f, size.height), Offset(corner, size.height), thick)
                        drawLine(c, Offset(size.width - corner, size.height), Offset(size.width, size.height), thick)
                        drawLine(c, Offset(size.width, size.height), Offset(size.width, size.height - corner), thick)
                    }
                }
                NodeCard(
                    node = node,
                    selected = isSel,
                    inBurst = nodeBurstMark,
                    suffix = nodeSuffix,
                    borderRole = bRole,
                    isSignalJudge = (node.kind == FlowNodeKind.IMAGE || node.kind == FlowNodeKind.APP_STATE) && isLoopJudgePath(node.id),
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(node.id) {
                            detectDragGestures(
                                onDragStart = {
                                    if (!linkingMode) {
                                        commit()
                                        selectedId = node.id
                                    }
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    if (!linkingMode) {
                                        updateNode(node.id) { it.copy(x = it.x + drag.x, y = it.y + drag.y) }
                                    }
                                },
                                onDragEnd = { if (!linkingMode) { dirty = true; autoSaveToFile() } },
                                onDragCancel = { if (!linkingMode) dirty = true },
                            )
                        },
                    onClick = {
                    if (burstMode) {
                        when (burstStage) {
                            "judge" -> {
                                // 互斥：循环判定（可达循环终点）不能作为突发判定
                                val isSignalJudge = (node.kind == FlowNodeKind.IMAGE || node.kind == FlowNodeKind.APP_STATE) && isLoopJudgePath(node.id)
                                if (isSignalJudge) {
                                    Toast.makeText(context, "循环判定不能作为突发判定（角色冲突：一个判定要么循环条件、要么突发哨兵）", Toast.LENGTH_LONG).show()
                                } else {
                                    burstJudgeId = node.id
                                }
                            }
                            "nodes" -> {
                                if (burstNodes.contains(node.id)) {
                                    burstNodes = burstNodes - node.id
                                } else {
                                    burstNodes = burstNodes + node.id
                                }
                            }
                            "hit" -> burstHitId = node.id
                            "miss" -> burstMissId = node.id
                        }
                    } else if (linkingMode) {
                        if (awaitingEnd) {
                            addLink(node.id)
                            awaitingEnd = false
                        } else {
                            if (linkStarts.contains(node.id)) {
                                linkStarts = linkStarts - node.id
                            } else {
                                linkStarts = linkStarts + node.id
                            }
                        }
                    } else {
                        selectedId = node.id
                        panelExpanded = false
                    }
                },
            )
            } // 节点外层 Box 结束
        }
        } // 缩放层结束
        // 工具栏（两行）
        Column(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 节点工具栏：执行/判定/控制 三个分类下拉栏（同种类同色；自定义节点按类型并入，排最下面）
                val allCustomTpl = remember(customRefreshTick) { com.mas.autofarm.presentation.view.workshop.CustomNodeStore.listAll(context) }
                val execCustom = allCustomTpl.filter { it.category == "EXECUTE" }.map { CategoryItem("🧩 ${it.name}", custom = it) }
                val judgeCustom = allCustomTpl.filter { it.category == "JUDGE" }.map { CategoryItem("🧩 ${it.name}", custom = it) }
                val controlCustom = allCustomTpl.filter { it.category == "CONTROL" }.map { CategoryItem("🧩 ${it.name}", custom = it) }
                val pickItem: (CategoryItem) -> Unit = { item ->
                    item.custom?.let { addCustomNode(it) } ?: item.kind?.let { addNode(it) }
                }
                CategoryDropdown(
                    label = "执行", color = Color(0xFF3A6EA5), expanded = execExpanded,
                    onExpandedChange = { execExpanded = it },
                    items = listOf(
                        CategoryItem("等待", kind = FlowNodeKind.WAIT),
                        CategoryItem("点击", kind = FlowNodeKind.TAP),
                        CategoryItem("滑动", kind = FlowNodeKind.SWIPE),
                        CategoryItem("返回", kind = FlowNodeKind.BACK),
                        CategoryItem("输入", kind = FlowNodeKind.INPUT),
                    ) + execCustom,
                    onPick = pickItem,
                )
                CategoryDropdown(
                    label = "判定", color = Color(0xFF2E8B57), expanded = judgeExpanded,
                    onExpandedChange = { judgeExpanded = it },
                    items = listOf(
                        CategoryItem("图像", kind = FlowNodeKind.IMAGE),
                        CategoryItem("应用", kind = FlowNodeKind.APP_STATE),
                    ) + judgeCustom,
                    onPick = pickItem,
                )
                CategoryDropdown(
                    label = "控制", color = Color(0xFFB58900), expanded = controlExpanded,
                    onExpandedChange = { controlExpanded = it },
                    items = listOf(
                        CategoryItem("循环起点", kind = FlowNodeKind.LOOP_START),
                        CategoryItem("循环终点", kind = FlowNodeKind.LOOP_END),
                        CategoryItem("合取(全部满足)", kind = FlowNodeKind.CONJUNCTION),
                        CategoryItem("析取(任一满足)", kind = FlowNodeKind.DISJUNCTION),
                    ) + controlCustom,
                    onPick = pickItem,
                )
                PillText(if (linkingMode) "🔗连线中" else "🔗连线",
                    onClick = { linkingMode = !linkingMode; linkStarts = emptyList(); pendingType = null; awaitingEnd = false; pendingLink = null },
                    color = if (linkingMode) Color(0xFFFF9800) else MaterialTheme.colorScheme.primary)
                PillText(if (burstMode) "突发中" else "突发",
                    onClick = {
                        burstMode = !burstMode
                        if (burstMode) linkingMode = false
                        burstNodes = emptyList(); burstJudgeId = null; burstHitId = null; burstMissId = null; burstStage = "judge"
                    },
                    color = if (burstMode) Color(0xFFFF5252) else Color(0xFFE53935))
                PillText(if (showCustomPicker) "🧩自定义中" else "🧩自定义",
                    onClick = { showCustomPicker = !showCustomPicker },
                    color = if (showCustomPicker) Color(0xFFBA68C8) else Color(0xFF7E57C2))
            }
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillText("↩ 撤回", onClick = { undo() })
                PillText("↪ 反撤回", onClick = { redo() })
                PillText(if (dirty) "● 保存" else "保存", onClick = { saveNow() },
                    color = if (dirty) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary)
                if (burstMode) {
                    PillText("✓确认突发", onClick = {
                        if (burstJudgeId != null && burstNodes.isNotEmpty() && burstHitId != null) {
                            bursts = bursts + FlowBurst(
                                id = UUID.randomUUID().toString(),
                                name = "突发${bursts.size + 1}",
                                judgeNodeId = burstJudgeId!!,
                                nodeIds = burstNodes,
                                hitContinueId = burstHitId!!,
                                missContinueId = burstMissId,
                                alwaysOn = burstAlwaysOn,
                            )
                            commit()
                            burstMode = false
                            burstNodes = emptyList(); burstJudgeId = null; burstHitId = null; burstMissId = null; burstStage = "judge"
                            dirty = true
                        } else {
                            Toast.makeText(context, "需判定节点+区间+命中继续", Toast.LENGTH_SHORT).show()
                        }
                    }, color = Color(0xFF4CAF50), fontSize = 15)
                }
                PillText(if (engineRunning) "■停止" else "▶运行", onClick = {
                    if (engineRunning) {
                        engineScope?.cancel()
                        engineRunning = false
                        engineLog = "已停止"
                    } else {
                        val project = FlowProject(name = projectTitle, nodes = nodes, links = links, bursts = bursts)
                        val templates = TemplateStore.listAll(context)
                        val remote = when (val st = rsm.state.value) {
                            is com.mas.autofarm.manager.RemoteServiceManager.ServiceState.Connected -> st.service
                            else -> null
                        }
                        if (remote == null) {
                            engineLog = "服务未连接，无法运行"
                            return@PillText
                        }
                        val engine = FlowEngine(context = context, remote = remote,
                            onLog = { msg -> engineLog = msg; sessionLogger.append(msg, com.mas.autofarm.data.model.LogLevel.INFO) },
                            onNode = { n -> engineLog = "执行: $n"; sessionLogger.append("-> $n", com.mas.autofarm.data.model.LogLevel.INFO) })
                        engineRunning = true
                        engineScope = kotlinx.coroutines.MainScope().launch {
                            sessionLogger.startSession(listOf("flow:$projectTitle"))
                            try {
                                engine.run(project, templates)
                            } finally {
                                sessionLogger.endSessionAndWait(
                                    if (engine.state == FlowEngine.EngineState.ERROR) "FLOW_ERROR" else "COMPLETED"
                                )
                            }
                            engineRunning = false
                        }
                    }
                }, color = if (engineRunning) Color(0xFFE53935) else Color(0xFF4CAF50))
                PillText("导出", onClick = {
                    try {
                        val project = FlowProject(name = projectTitle, nodes = nodes, links = links, bursts = bursts)
                        // 文件夹格式导出（流程 + 自定义节点模板 + 识别模板图片）
                        val dir = com.mas.autofarm.presentation.view.workshop.FlowExportImport.exportFlow(context, project)
                        if (dir != null) {
                            Toast.makeText(context, "已导出文件夹: Download/MASS导出/${dir.name}", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                })
                PillText("返回", onClick = onExit, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 连线模式提示条（选类型）
        if (linkingMode) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 90.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "开端: ${linkStarts.size}个 ${if (pendingType != null) "类型:${pendingType!!.name}" else "类型:未选"} ${if (awaitingEnd) "→ 点末端节点" else "→ 点节点选开端/点类型"}",
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        // 多开端时 是/非 禁用（只能与/或）
                        val multi = linkStarts.size > 1
                        fun pickType(type: LinkType) {
                            if (multi) {
                                Toast.makeText(context, "多开端汇聚只能用「与/或」", Toast.LENGTH_SHORT).show()
                                return
                            }
                            linkTypeBlockReason(type)?.let {
                                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                            } ?: run { pendingType = type }
                        }
                        PillText("顺序", onClick = { pickType(LinkType.SEQUENCE) },
                            color = if (multi) Color(0x66FFFFFF) else linkColor(LinkType.SEQUENCE))
                        PillText("是", onClick = { pickType(LinkType.YES) },
                            color = if (multi) Color(0x66FFFFFF) else linkColor(LinkType.YES))
                        PillText("非", onClick = { pickType(LinkType.NO) },
                            color = if (multi) Color(0x66FFFFFF) else linkColor(LinkType.NO))
                        PillText("选开端", onClick = { awaitingEnd = false },
                            color = if (!awaitingEnd) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary)
                        PillText("选末端", onClick = { awaitingEnd = true },
                            color = if (awaitingEnd) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary)
                        PillText("确定生成", onClick = { confirmLink() },
                            color = if (pendingLink != null) Color(0xFF4CAF50) else Color(0x66FFFFFF))
                        PillText("取消", onClick = { linkingMode = false; linkStarts = emptyList(); pendingType = null; awaitingEnd = false; pendingLink = null },
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // 突发模式提示条
        if (burstMode) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 90.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2222)),
            ) {
                Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val judgeName = burstJudgeId?.let { nodes.find { n -> n.id == it }?.name }
                    Text(
                        "判定:${judgeName ?: "未选"} | 区间:${burstNodes.size} | 命中→:${burstHitId?.let { nodes.find { n -> n.id == it }?.name ?: "" }} ${if (burstMissId != null) "| 未命中→:${nodes.find { n -> n.id == burstMissId }?.name}" else ""}",
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        PillText("设判定", onClick = { burstStage = "judge" },
                            color = if (burstStage == "judge") Color(0xFF4CAF50) else Color(0xFFFF7043))
                        PillText("选区间", onClick = { burstStage = "nodes" },
                            color = if (burstStage == "nodes") Color(0xFF4CAF50) else Color(0xFFFF7043))
                        PillText("命中继续", onClick = { burstStage = "hit" },
                            color = if (burstStage == "hit") Color(0xFF4CAF50) else Color(0xFFFF7043))
                        PillText("未命中继续", onClick = { burstStage = "miss" },
                            color = if (burstStage == "miss") Color(0xFF4CAF50) else Color(0xFFFF7043))
                        PillText("始终:" + if (burstAlwaysOn) "开" else "关",
                            onClick = { burstAlwaysOn = !burstAlwaysOn },
                            color = if (burstAlwaysOn) Color(0xFFE53935) else Color(0x66FFFFFF))
                        PillText("确认突发", onClick = {
                            if (burstJudgeId != null && burstNodes.isNotEmpty() && burstHitId != null) {
                                bursts = bursts + FlowBurst(
                                    id = UUID.randomUUID().toString(),
                                    name = "突发${bursts.size + 1}",
                                    judgeNodeId = burstJudgeId!!,
                                    nodeIds = burstNodes,
                                    hitContinueId = burstHitId!!,
                                    missContinueId = burstMissId,
                                    alwaysOn = burstAlwaysOn,
                                )
                                commit()
                                burstMode = false
                                burstNodes = emptyList(); burstJudgeId = null; burstHitId = null; burstMissId = null; burstStage = "judge"
                                dirty = true
                            } else {
                                Toast.makeText(context, "需判定节点+区间+命中继续", Toast.LENGTH_SHORT).show()
                            }
                        }, color = Color(0xFF4CAF50))
                        PillText("取消", onClick = { burstMode = false; burstNodes = emptyList(); burstJudgeId = null; burstHitId = null; burstMissId = null; burstStage = "judge" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // 运行日志条
        if (engineLog.isNotBlank()) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 128.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC000000)),
            ) {
                Text(
                    engineLog,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        // 编辑面板
        selectedNode?.let { node ->
            if (panelExpanded) {
                NodeEditorPanel(
                    node = node,
                    links = links,
                    bursts = bursts,
                    nodes = nodes,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .fillMaxWidth(),
                    onUpdate = { newNode -> commit(); updateNode(node.id, { newNode }) },
                    onCollapse = { panelExpanded = false },
                    onDelete = {
                        commit()
                        nodes = nodes.filter { it.id != node.id }
                        links = links.filter { !it.fromIds.contains(node.id) && it.toId != node.id }
                        selectedId = null
                        panelExpanded = false
                    },
                    onDirty = { dirty = true },
                    onDeleteLink = { linkId ->
                        commit()
                        links = links.filter { it.id != linkId }
                    },
                    onDeleteBurst = { burstId ->
                        commit()
                        bursts = bursts.filter { it.id != burstId }
                    },
                )
            } else {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .fillMaxWidth()
                        .clickable { panelExpanded = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${node.kindLabel()}：${node.name}", style = MaterialTheme.typography.titleSmall)
                            Text(node.summary(), style = MaterialTheme.typography.labelSmall)
                        }
                        PillText("展开编辑", onClick = { panelExpanded = true })
                    }
                }
            }
        }
    }
}

/** 分类下拉项：内置节点（kind）或自定义节点（custom） */
data class CategoryItem(
    val label: String,
    val kind: FlowNodeKind? = null,
    val custom: com.mas.autofarm.presentation.view.workshop.CustomNodeTemplate? = null,
)

/** 分类下拉栏：点击展开该分类下的节点列表 */
@Composable
private fun CategoryDropdown(
    label: String,
    color: Color,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    items: List<CategoryItem>,
    onPick: (CategoryItem) -> Unit,
) {
    Box {
        Button(
            onClick = { onExpandedChange(!expanded) },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = color),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(30.dp),
        ) {
            Text("$label ▼", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = { onExpandedChange(false); onPick(item) },
                )
            }
        }
    }
}

fun FlowNodeKind.label(): String = when (this) {
    FlowNodeKind.INFO -> "流程"
    FlowNodeKind.TIME -> "时间"
    FlowNodeKind.IMAGE -> "图像"
    FlowNodeKind.ACTION -> "动作"
    FlowNodeKind.LOOP -> "循环"
    FlowNodeKind.APP_STATE -> "应用"
    FlowNodeKind.TAP -> "点击"
    FlowNodeKind.SWIPE -> "滑动"
    FlowNodeKind.WAIT -> "等待"
    FlowNodeKind.BACK -> "返回"
    FlowNodeKind.INPUT -> "输入"
    FlowNodeKind.LOOP_START -> "循环起点"
    FlowNodeKind.CONJUNCTION -> "合取(全部满足)"
    FlowNodeKind.DISJUNCTION -> "析取(任一满足)"
    FlowNodeKind.LOOP_END -> "循环终点"
}

/** 节点大类：执行（做事）/ 判定（输出是/非）/ 控制（流转结构） */
fun FlowNodeKind.categoryLabel(): String = when (this) {
    FlowNodeKind.INFO, FlowNodeKind.TIME, FlowNodeKind.ACTION,
    FlowNodeKind.TAP, FlowNodeKind.SWIPE, FlowNodeKind.WAIT,
    FlowNodeKind.BACK, FlowNodeKind.INPUT -> "执行"
    FlowNodeKind.IMAGE, FlowNodeKind.APP_STATE -> "判定"
    FlowNodeKind.LOOP, FlowNodeKind.LOOP_START,
    FlowNodeKind.CONJUNCTION, FlowNodeKind.DISJUNCTION,
    FlowNodeKind.LOOP_END -> "控制"
}

@Composable
private fun FlowNode.kindLabel(): String = kind.label()

fun FlowNode.summary(): String =
    if (customNodeId.isNotBlank()) "自定义:" + customNodeId
    else when (kind) {
    FlowNodeKind.INFO -> "打开:${appPackage.ifBlank { "未设置" }}" + if (launchApp) "" else "(不启动)"
    FlowNodeKind.TIME, FlowNodeKind.WAIT -> "等 ${durationMs}ms" + (if (untilTemplateId.isNotBlank()) " 直到:${untilTemplateId}" else "")
    FlowNodeKind.IMAGE -> "识别:${templateId.ifBlank { "未选模板" }}($threshold)" + (if (maxRetries > 0) " 重试x${maxRetries}" else "")
    FlowNodeKind.LOOP -> if (loopMode == "until") "循环直到:${untilTemplateId.ifBlank { "未选模板" }}" else "循环 ${durationMs.toInt()} 次"
    FlowNodeKind.LOOP_START -> "循环起点"
    FlowNodeKind.CONJUNCTION -> "合取(全部满足)"
    FlowNodeKind.DISJUNCTION -> "析取(任一满足)"
    FlowNodeKind.LOOP_END -> "循环终点${if (loopStartId.isBlank()) " 🚫未绑定起点" else ""}${if (loopTimeoutMs > 0) " 超时${loopTimeoutMs}ms" else ""}"
    FlowNodeKind.APP_STATE -> "应用:${appStatePkg.ifBlank { "未设置" }} ${if (appStateMode == "alive") "存活?" else "在虚拟屏?"}"
    FlowNodeKind.ACTION, FlowNodeKind.TAP, FlowNodeKind.SWIPE,
    FlowNodeKind.BACK, FlowNodeKind.INPUT -> when (val a = action) {
        is FlowAction.Tap -> if (a.durationMs > 60) "长按(${a.x},${a.y}) ${a.durationMs}ms" else "点(${a.x},${a.y})"
        is FlowAction.TapTemplate -> "点模板"
        is FlowAction.Wait -> "等${a.ms}ms"
        is FlowAction.Back -> "返回x${a.times}"
        is FlowAction.Swipe -> "滑动(${a.fromX},${a.fromY})→(${a.toX},${a.toY}) ${a.durationMs}ms"
        is FlowAction.Input -> "输入:${a.text}"
    }
}

enum class BurstBorderRole { NONE, JUDGE, HIT, MISS, RANGE }
@Composable

private fun NodeCard(
    node: FlowNode,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    inBurst: Boolean = false,
    suffix: String = "",
    borderRole: BurstBorderRole = BurstBorderRole.NONE,
    isSignalJudge: Boolean = false,
) {
    val bg = when (node.kind) {
        FlowNodeKind.INFO -> Color(0xFF546E7A) // 流程信息：灰色独立
        else -> when (node.kind.categoryLabel()) {
            "判定" -> Color(0xFF2E8B57) // 判定：绿
            "控制" -> Color(0xFFB58900) // 控制：黄
            else -> Color(0xFF3A6EA5)   // 执行：蓝
        }
    }
    Box {
    Card(
        modifier = modifier.size(width = 170.dp, height = 72.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) bg else bg.copy(alpha = 0.75f)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 6.dp else 2.dp),
        border = when {
            selected -> BorderStroke(4.dp, Color.White)
            borderRole == BurstBorderRole.JUDGE -> BorderStroke(2.dp, Color(0xFFE53935)) // 判定：红
            borderRole == BurstBorderRole.HIT -> BorderStroke(2.dp, Color(0xFF4CAF50)) // 命中继续：绿
            borderRole == BurstBorderRole.MISS -> BorderStroke(2.dp, Color(0xFFFDD835)) // 未命中继续：黄
            else -> null
        },
        onClick = onClick,
    ) {
        Box {
            Column(Modifier.padding(10.dp)) {
                Text(
                    "${node.kind.categoryLabel()}·${if (node.customNodeId.isNotBlank()) "自定义" else node.kind.label()}：${node.name}$suffix",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(node.summary(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f), maxLines = 2)
            }
        }
    }
    // 循环判定标识：黄色四角（判定节点有出线到合取/析取/循环终点 = 信号型判定）
    if (isSignalJudge) {
        Canvas(Modifier.matchParentSize()) {
            val s = 12f
            val c = Color(0xFFFDD835)
            drawLine(c, Offset(0f, 0f), Offset(s, 0f), 3f)
            drawLine(c, Offset(0f, 0f), Offset(0f, s), 3f)
            drawLine(c, Offset(size.width - s, 0f), Offset(size.width, 0f), 3f)
            drawLine(c, Offset(size.width, 0f), Offset(size.width, s), 3f)
            drawLine(c, Offset(0f, size.height - s), Offset(0f, size.height), 3f)
            drawLine(c, Offset(0f, size.height), Offset(s, size.height), 3f)
            drawLine(c, Offset(size.width - s, size.height), Offset(size.width, size.height), 3f)
            drawLine(c, Offset(size.width, size.height - s), Offset(size.width, size.height), 3f)
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeEditorPanel(
    node: FlowNode,
    links: List<FlowLink>,
    bursts: List<FlowBurst>,
    nodes: List<FlowNode>,
    modifier: Modifier = Modifier,
    onUpdate: (FlowNode) -> Unit,
    onCollapse: () -> Unit,
    onDelete: () -> Unit,
    onDirty: () -> Unit = {},
    onDeleteLink: (String) -> Unit = {},
    onDeleteBurst: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var name by remember(node.id) { mutableStateOf(node.name) }
    var appPkg by remember(node.id) { mutableStateOf(node.appPackage) }
    var launchApp by remember(node.id) { mutableStateOf(node.launchApp) }
    var showAppPicker by remember { mutableStateOf(false) }
    var durationMs by remember(node.id) { mutableStateOf(node.durationMs.toString()) }
    var untilTemplate by remember(node.id) { mutableStateOf(node.untilTemplateId) }
    var untilTimeout by remember(node.id) { mutableStateOf(node.untilTimeoutMs.toString()) }
    var templateId by remember(node.id) { mutableStateOf(node.templateId) }
    var threshold by remember(node.id) { mutableStateOf(node.threshold.toString()) }
    var retryInterval by remember(node.id) { mutableStateOf(node.retryIntervalMs.toString()) }
    var maxRetries by remember(node.id) { mutableStateOf(node.maxRetries.toString()) }
    val editActionType = when (node.action) {
        is FlowAction.Tap -> "tap"
        is FlowAction.TapTemplate -> "tapt"
        is FlowAction.Wait -> "wait"
        is FlowAction.Back -> "back"
        is FlowAction.Swipe -> "swipe"
        is FlowAction.Input -> "input"
        else -> "wait"
    }
    var tapX by remember(node.id) { mutableStateOf((node.action as? FlowAction.Tap)?.x?.toString() ?: "540") }
    var tapY by remember(node.id) { mutableStateOf((node.action as? FlowAction.Tap)?.y?.toString() ?: "1200") }
    var pickBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var pickPoint by remember { mutableStateOf<Offset?>(null) }
    var pickSwipeStart by remember { mutableStateOf<Offset?>(null) }
    var pickSwipeEnd by remember { mutableStateOf<Offset?>(null) }
    var pickSwipeDownMs by remember { mutableStateOf(0L) }
    var pickMode by remember { mutableStateOf("tap") }
    var waitMs by remember(node.id) { mutableStateOf((node.action as? FlowAction.Wait)?.ms?.toString() ?: "500") }
    var inputText by remember(node.id) { mutableStateOf((node.action as? FlowAction.Input)?.text ?: "") }
    var tapDur by remember(node.id) { mutableStateOf((node.action as? FlowAction.Tap)?.durationMs?.toString() ?: "60") }
    var resolution by remember(node.id) { mutableStateOf(node.resolution) }
    var appStatePkg by remember(node.id) { mutableStateOf(node.appStatePkg) }
    var appStateMode by remember(node.id) { mutableStateOf(node.appStateMode) }
    var loopStartId by remember(node.id) { mutableStateOf(node.loopStartId) }
    var loopTimeoutMs by remember(node.id) { mutableStateOf(node.loopTimeoutMs.toString()) }
    var untilJudgeId by remember(node.id) { mutableStateOf(node.untilJudgeId) }
    var loopMode by remember(node.id) { mutableStateOf(node.loopMode) }
    val swipe = node.action as? FlowAction.Swipe
    var swipeFromX by remember(node.id) { mutableStateOf(swipe?.fromX?.toString() ?: "100") }
    var swipeFromY by remember(node.id) { mutableStateOf(swipe?.fromY?.toString() ?: "1000") }
    var swipeToX by remember(node.id) { mutableStateOf(swipe?.toX?.toString() ?: "500") }
    var swipeToY by remember(node.id) { mutableStateOf(swipe?.toY?.toString() ?: "1000") }
    var swipeDur by remember(node.id) { mutableStateOf(swipe?.durationMs?.toString() ?: "300") }
    var backTimes by remember(node.id) { mutableStateOf((node.action as? FlowAction.Back)?.times?.toString() ?: "1") }
    var cropUri by remember(node.id) { mutableStateOf<Uri?>(null) }
    val panelScope = rememberCoroutineScope()
    val cropLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) cropUri = u }
    fun testAction(act: FlowAction) {
        val srv = com.mas.autofarm.manager.RemoteServiceManager.getInstanceOrNull()
        if (srv == null) { Toast.makeText(context, "服务未连接", Toast.LENGTH_SHORT).show(); return }
        panelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                when (act) {
                    is FlowAction.Tap -> { srv.touchDown(act.x, act.y); kotlinx.coroutines.delay(act.durationMs); srv.touchUp(act.x, act.y) }
                    FlowAction.TapTemplate -> { kotlinx.coroutines.delay(1) }
                    is FlowAction.Wait -> kotlinx.coroutines.delay(act.ms)
                    is FlowAction.Back -> repeat(act.times) { srv.touchDown(60, 1200); kotlinx.coroutines.delay(60); srv.touchUp(60, 1200); kotlinx.coroutines.delay(300) }
                    is FlowAction.Swipe -> { srv.touchDown(act.fromX, act.fromY); kotlinx.coroutines.delay(100); srv.touchMove(act.toX, act.toY); kotlinx.coroutines.delay(act.durationMs); srv.touchUp(act.toX, act.toY) }
                    is FlowAction.Input -> srv.inputText(act.text)
                }
            }.onSuccess { kotlinx.coroutines.MainScope().launch { Toast.makeText(context, "试运行完成", Toast.LENGTH_SHORT).show() } }
                .onFailure { e -> kotlinx.coroutines.MainScope().launch { Toast.makeText(context, "试运行失败: ${e.message}", Toast.LENGTH_SHORT).show() } }
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${node.kind.categoryLabel()}·${if (node.customNodeId.isNotBlank()) "自定义" else node.kind.label()}节点", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                PillText("收起", onClick = onCollapse, fontSize = 13)
                PillDangerText("删除", onClick = {
                if (node.kind == FlowNodeKind.INFO) {
                    android.widget.Toast.makeText(context, "流程信息节点为初始节点，不可删除", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    onDelete()
                }
            }, fontSize = 13)
            }
            OutlinedTextField(value = name, onValueChange = { name = it; onDirty() }, label = { Text("节点名") }, modifier = Modifier.fillMaxWidth())
            if (node.customNodeId.isNotBlank()) {
                    val tpl = com.mas.autofarm.presentation.view.workshop.CustomNodeStore.find(context, node.customNodeId)
                    Text("🧩 自定义节点（" + (tpl?.categoryLabel() ?: "未知") + "）", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(tpl?.description ?: ("模板不存在：" + node.customNodeId), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tpl?.params?.forEach { param ->
                        val v = node.customParams[param.key] ?: param.default
                        var value by remember(node.id, param.key) { mutableStateOf(v) }
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it; onDirty(); onUpdate(node.copy(customParams = node.customParams + (param.key to it))) },
                            label = { Text(param.label) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else when (node.kind) {
                FlowNodeKind.CONJUNCTION -> {
                    Text("合取（全部满足）：所有输入线都收到信号 → 输出「是」；任一缺失 → 输出「非」。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("输入线需 ≥2 条（判定的是/非线连入）；连顺序线=二极管纯汇聚。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowNodeKind.DISJUNCTION -> {
                    Text("析取（任一满足）：任一输入线收到信号 → 输出「是」；全部无信号 → 输出「非」。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("输入线需 ≥2 条；连顺序线=二极管纯汇聚。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowNodeKind.INFO -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = appPkg, onValueChange = { appPkg = it; onDirty() }, label = { Text("应用包名") }, modifier = Modifier.weight(1f))
                        PillText("选应用", onClick = { showAppPicker = true })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("执行时启动应用", style = MaterialTheme.typography.labelMedium)
                        androidx.compose.material3.Switch(
                            checked = launchApp,
                            onCheckedChange = { launchApp = it; onDirty() },
                        )
                    }
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("虚拟屏分辨率", style = MaterialTheme.typography.labelMedium)
                        listOf(
                            "" to "默认",
                            "P720" to "720p",
                            "P1600x720" to "1600x720",
                            "P1080" to "1080p",
                        ).forEach { (v, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = resolution == v,
                                onClick = { resolution = v; onDirty() },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                FlowNodeKind.LOOP_END -> {
                    // 循环终点：绑定循环起点 + 超时保护
                    val startOptions = nodes.filter { it.kind == FlowNodeKind.LOOP_START }
                    Text("循环终点：收到「继续信号」→ 回循环起点；否则结束", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("绑定循环起点", style = MaterialTheme.typography.labelMedium)
                        var startExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = startExpanded, onExpandedChange = { startExpanded = it }) {
                            OutlinedTextField(
                                value = nodes.find { it.id == loopStartId }?.name ?: if (loopStartId.isBlank()) "" else loopStartId,
                                onValueChange = {}, readOnly = true,
                                placeholder = { Text(if (startOptions.isEmpty()) "先添加循环起点" else "选择循环起点") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = startExpanded) },
                                modifier = Modifier.menuAnchor().weight(1f),
                            )
                            ExposedDropdownMenu(expanded = startExpanded, onDismissRequest = { startExpanded = false }) {
                                if (startOptions.isEmpty()) {
                                    DropdownMenuItem(text = { Text("没有循环起点，请先添加") }, onClick = { startExpanded = false })
                                }
                                startOptions.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.name) },
                                        onClick = { loopStartId = s.id; startExpanded = false; onDirty() }
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = loopTimeoutMs, onValueChange = { loopTimeoutMs = it; onDirty() }, label = { Text("超时保护ms（0=不启用，超时强制成功放行）") }, modifier = Modifier.fillMaxWidth())
                }
                FlowNodeKind.LOOP -> {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("times" to "次数循环", "until" to "直到识别").forEach { (v, label) ->
                            androidx.compose.material3.FilterChip(selected = loopMode == v, onClick = { loopMode = v; onDirty() }, label = { Text(label) })
                        }
                    }
                    if (loopMode == "until") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("直到识别到", style = MaterialTheme.typography.labelMedium)
                            var untilExpanded by remember { mutableStateOf(false) }
                            val untilTemplates = TemplateStore.listAll(context)
                            ExposedDropdownMenuBox(expanded = untilExpanded, onExpandedChange = { untilExpanded = it }) {
                                OutlinedTextField(value = untilTemplate, onValueChange = {}, readOnly = true,
                                    placeholder = { Text("选模板") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = untilExpanded) },
                                    modifier = Modifier.menuAnchor())
                                ExposedDropdownMenu(expanded = untilExpanded, onDismissRequest = { untilExpanded = false }) {
                                    untilTemplates.forEach { t ->
                                        DropdownMenuItem(text = { Text(if (t.category.isBlank()) t.name else "${t.category}/${t.name}") },
                                            onClick = { untilTemplate = t.name; untilExpanded = false; onDirty() })
                                    }
                                }
                            }
                        }
                        OutlinedTextField(value = untilTimeout, onValueChange = { untilTimeout = it; onDirty() }, label = { Text("超时ms") }, modifier = Modifier.fillMaxWidth())
                    } else {
                        OutlinedTextField(value = durationMs, onValueChange = { durationMs = it; onDirty() }, label = { Text("循环次数") }, modifier = Modifier.fillMaxWidth())
                    }
                }
                FlowNodeKind.LOOP_START -> {
                    Text(
                        "循环起点：循环体入口。用法：循环节点「是」线 → 循环起点 → 循环体 → 体末连回循环节点（终点=循环本身）",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowNodeKind.APP_STATE -> {
                    OutlinedTextField(value = appStatePkg, onValueChange = { appStatePkg = it; onDirty() }, label = { Text("应用包名") }, modifier = Modifier.fillMaxWidth())
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("foreground" to "在虚拟屏?", "alive" to "应用存活?").forEach { (v, label) ->
                            androidx.compose.material3.FilterChip(selected = appStateMode == v, onClick = { appStateMode = v; onDirty() }, label = { Text(label) })
                        }
                    }
                }
                FlowNodeKind.TIME, FlowNodeKind.WAIT -> {
                    OutlinedTextField(value = durationMs, onValueChange = { durationMs = it; onDirty() }, label = { Text("等待毫秒（超时上限）") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("直到识别到", style = MaterialTheme.typography.labelMedium)
                        var untilExpanded by remember { mutableStateOf(false) }
                        val untilTemplates = TemplateStore.listAll(context)
                        ExposedDropdownMenuBox(expanded = untilExpanded, onExpandedChange = { untilExpanded = it }) {
                            OutlinedTextField(
                                value = untilTemplate, onValueChange = {}, readOnly = true,
                                placeholder = { Text("无（纯等待）") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = untilExpanded) },
                                modifier = Modifier.menuAnchor(),
                            )
                            ExposedDropdownMenu(expanded = untilExpanded, onDismissRequest = { untilExpanded = false }) {
                                DropdownMenuItem(text = { Text("（无）") }, onClick = { untilTemplate = ""; untilExpanded = false; onDirty() })
                                untilTemplates.forEach { t ->
                                    DropdownMenuItem(text = { Text(if (t.category.isBlank()) t.name else "${t.category}/${t.name}") },
                                        onClick = { untilTemplate = t.name; untilExpanded = false; onDirty() })
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = untilTimeout, onValueChange = { untilTimeout = it; onDirty() }, label = { Text("直到超时ms（0=用等待毫秒）") }, modifier = Modifier.fillMaxWidth())
                }
                FlowNodeKind.IMAGE -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("模板", style = MaterialTheme.typography.labelMedium)
                        PillText("✂抠图", onClick = { cropLauncher.launch(arrayOf("image/*")) }, color = Color(0xFF2E8B57))
                        var tplExpanded by remember { mutableStateOf(false) }
                        val templates = TemplateStore.listAll(context)
                        ExposedDropdownMenuBox(expanded = tplExpanded, onExpandedChange = { tplExpanded = it }) {
                            OutlinedTextField(
                                value = templateId, onValueChange = {}, readOnly = true,
                                placeholder = { Text("选模板") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tplExpanded) },
                                modifier = Modifier.menuAnchor(),
                            )
                            ExposedDropdownMenu(expanded = tplExpanded, onDismissRequest = { tplExpanded = false }) {
                                templates.forEach { t ->
                                    DropdownMenuItem(text = { Text(if (t.category.isBlank()) t.name else "${t.category}/${t.name}") },
                                        onClick = { templateId = t.name; tplExpanded = false; onDirty() })
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = threshold, onValueChange = { threshold = it; onDirty() }, label = { Text("匹配阈值 (0.5~0.95)") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = retryInterval, onValueChange = { retryInterval = it; onDirty() }, label = { Text("未命中重试间隔ms") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = maxRetries, onValueChange = { maxRetries = it; onDirty() }, label = { Text("最大重试次数") }, modifier = Modifier.weight(1f))
                    }
                }
                FlowNodeKind.ACTION, FlowNodeKind.TAP, FlowNodeKind.SWIPE,
                FlowNodeKind.BACK, FlowNodeKind.INPUT -> {
                    val pickImgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) {
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                            }.onSuccess { bmp ->
                                if (bmp == null) {
                                    Toast.makeText(context, "无法读取该图片", Toast.LENGTH_SHORT).show()
                                } else {
                                    pickBitmap = bmp
                                    pickPoint = null
                                    pickSwipeStart = null
                                    pickSwipeEnd = null
                                }
                            }.onFailure { e ->
                                Toast.makeText(context, "读取图片失败：${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    when (editActionType) {
                        "tap" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = tapX, onValueChange = { tapX = it; onDirty() }, label = { Text("X") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = tapY, onValueChange = { tapY = it; onDirty() }, label = { Text("Y") }, modifier = Modifier.weight(1f))
                            }
                            OutlinedButton(
                                onClick = { pickMode = "tap"; pickImgLauncher.launch(arrayOf("image/*")) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("🖼 图片选点") }
                            OutlinedTextField(value = tapDur, onValueChange = { tapDur = it; onDirty() }, label = { Text("按住时长ms（>60=长按）") }, modifier = Modifier.fillMaxWidth())
                            // 图片选点对话框：放大显示，点击/按住拖动选点，XY辅助线（自动换算到流程坐标 1600×720）
                            pickBitmap?.let { bmp ->
                                Dialog(onDismissRequest = { pickBitmap = null }) {
                                    Card(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(8.dp)) {
                                        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("在图片上点击/按住滑动选择位置", style = MaterialTheme.typography.titleSmall)
                                            Text("图片 ${bmp.width}×${bmp.height} → 流程坐标 1600×720（自动换算）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            val imgW = bmp.width.toFloat()
                                            val imgH = bmp.height.toFloat()
                                            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp)) {
                                                val scale = kotlin.math.min(maxWidth.value / imgW, maxHeight.value / imgH)
                                                val dispW = imgW * scale
                                                val dispH = imgH * scale
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Box(
                                                        Modifier.size(dispW.dp, dispH.dp)
                                                            .pointerInput(bmp) {
                                                                awaitEachGesture {
                                                                    var down = false
                                                                    var lastPos: Offset? = null
                                                                    while (true) {
                                                                        val event = awaitPointerEvent()
                                                                        val change = event.changes.firstOrNull() ?: break
                                                                        if (change.pressed) {
                                                                            if (!down) {
                                                                                down = true
                                                                                lastPos = change.position
                                                                                // 按下即选中（点击/拖动都立即生效）
                                                                                pickPoint = Offset(change.position.x / size.width * imgW, change.position.y / size.height * imgH)
                                                                            } else if (change.position != lastPos) {
                                                                                lastPos = change.position
                                                                                change.consume()
                                                                                pickPoint = Offset(change.position.x / size.width * imgW, change.position.y / size.height * imgH)
                                                                            }
                                                                        } else if (down) {
                                                                            break
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                    ) {
                                                        Image(bmp.asImageBitmap(), contentDescription = "选点图片", modifier = Modifier.fillMaxSize())
                                                        Canvas(Modifier.fillMaxSize()) {
                                                            pickPoint?.let { p ->
                                                                val px = p.x / imgW * size.width
                                                                val py = p.y / imgH * size.height
                                                                // XY 辅助线（贯穿虚线）
                                                                drawLine(Color.Red.copy(alpha = 0.35f), Offset(0f, py), Offset(size.width, py), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                                                                drawLine(Color.Red.copy(alpha = 0.35f), Offset(px, 0f), Offset(px, size.height), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                                                                // 十字标记
                                                                drawLine(Color.Red, Offset(px - 25f, py), Offset(px + 25f, py), 2.5f)
                                                                drawLine(Color.Red, Offset(px, py - 25f), Offset(px, py + 25f), 2.5f)
                                                                drawCircle(Color.Red, 7f, Offset(px, py))
                                                            }
                                                        }
                                                    }
                                                }
                                                // 放大镜：跟随手指（不做边缘约束，边角也能放大）
                                                pickPoint?.let { p ->
                                                    val imgLeft = (maxWidth.value - dispW) / 2f
                                                    val imgTop = (maxHeight.value - dispH) / 2f
                                                    val px = imgLeft + p.x / imgW * dispW
                                                    val py = imgTop + p.y / imgH * dispH
                                                    val lensW = 150f
                                                    val lensH = 150f
                                                    val offX = px - lensW - 20f
                                                    val offY = py - lensH - 20f
                                                    Canvas(
                                                        Modifier.offset(offX.dp, offY.dp).size(lensW.dp, lensH.dp)
                                                            .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.65f))
                                                    ) {
                                                        val zoom = 2.5f
                                                        val srcW = (imgW / zoom).coerceAtLeast(24f)
                                                        val srcH = (imgH / zoom).coerceAtLeast(24f)
                                                        val srcLeft = (p.x - srcW / 2f).toInt()
                                                        val srcTop = (p.y - srcH / 2f).toInt()
                                                        drawImage(
                                                            bmp.asImageBitmap(),
                                                            srcOffset = IntOffset(srcLeft, srcTop),
                                                            srcSize = IntSize(srcW.toInt(), srcH.toInt()),
                                                            dstOffset = IntOffset.Zero,
                                                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                                        )
                                                        val cx = size.width / 2f
                                                        val cy = size.height / 2f
                                                        drawLine(Color.White.copy(alpha = 0.85f), Offset(cx - 14f, cy), Offset(cx + 14f, cy), 2f)
                                                        drawLine(Color.White.copy(alpha = 0.85f), Offset(cx, cy - 14f), Offset(cx, cy + 14f), 2f)
                                                        drawRect(Color.White.copy(alpha = 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                                                    }
                                                }
                                            }
                                            Text(
                                                "流程坐标：${pickPoint?.let { "(${(it.x / imgW * 1600f).toInt()}, ${(it.y / imgH * 720f).toInt()})" } ?: "未选"}",
                                                fontWeight = FontWeight.Bold
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                                                Button(
                                                    onClick = {
                                                        pickPoint?.let { p ->
                                                            tapX = (p.x / imgW * 1600f).toInt().toString()
                                                            tapY = (p.y / imgH * 720f).toInt().toString()
                                                            onDirty()
                                                        }
                                                        pickBitmap = null
                                                    },
                                                    enabled = pickPoint != null
                                                ) { Text("确定使用") }
                                                TextButton(onClick = { pickBitmap = null }) { Text("取消") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "input" -> OutlinedTextField(value = inputText, onValueChange = { inputText = it; onDirty() }, label = { Text("输入文本") }, modifier = Modifier.fillMaxWidth())
                        "wait" -> OutlinedTextField(value = waitMs, onValueChange = { waitMs = it; onDirty() }, label = { Text("等待毫秒") }, modifier = Modifier.fillMaxWidth())
                        "back" -> OutlinedTextField(value = backTimes, onValueChange = { backTimes = it; onDirty() }, label = { Text("返回次数") }, modifier = Modifier.fillMaxWidth())
                        "swipe" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = swipeFromX, onValueChange = { swipeFromX = it; onDirty() }, label = { Text("起点X") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = swipeFromY, onValueChange = { swipeFromY = it; onDirty() }, label = { Text("起点Y") }, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = swipeToX, onValueChange = { swipeToX = it; onDirty() }, label = { Text("终点X") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = swipeToY, onValueChange = { swipeToY = it; onDirty() }, label = { Text("终点Y") }, modifier = Modifier.weight(1f))
                            }
                            OutlinedTextField(value = swipeDur, onValueChange = { swipeDur = it; onDirty() }, label = { Text("时长ms") }, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(
                                onClick = { pickMode = "swipe"; pickImgLauncher.launch(arrayOf("image/*")) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("🖼 滑动选点（按住画线）") }
                            // 滑动选点对话框：按住拖动画线，起点=按下 终点=抬起，时长自动记录
                            pickBitmap?.let { bmp ->
                                Dialog(onDismissRequest = { pickBitmap = null }) {
                                    Card(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(8.dp)) {
                                        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("按住拖动画出滑动路径", style = MaterialTheme.typography.titleSmall)
                                            Text("起点=按下位置，终点=抬起位置，时长自动记录", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            val imgW = bmp.width.toFloat()
                                            val imgH = bmp.height.toFloat()
                                            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp)) {
                                                val scale = kotlin.math.min(maxWidth.value / imgW, maxHeight.value / imgH)
                                                val dispW = imgW * scale
                                                val dispH = imgH * scale
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Box(
                                                        Modifier.size(dispW.dp, dispH.dp)
                                                            .pointerInput(bmp) {
                                                                awaitEachGesture {
                                                                    var down = false
                                                                    var lastPos: Offset? = null
                                                                    while (true) {
                                                                        val event = awaitPointerEvent()
                                                                        val change = event.changes.firstOrNull() ?: break
                                                                        if (change.pressed) {
                                                                            if (!down) {
                                                                                down = true
                                                                                lastPos = change.position
                                                                                val downPt = Offset(change.position.x / size.width * imgW, change.position.y / size.height * imgH)
                                                                                pickSwipeStart = downPt
                                                                                pickSwipeEnd = downPt
                                                                                pickSwipeDownMs = System.currentTimeMillis()
                                                                            } else if (change.position != lastPos) {
                                                                                lastPos = change.position
                                                                                change.consume()
                                                                                pickSwipeEnd = Offset(change.position.x / size.width * imgW, change.position.y / size.height * imgH)
                                                                            }
                                                                        } else if (down) {
                                                                            val dur = System.currentTimeMillis() - pickSwipeDownMs
                                                                            if (dur >= 50) swipeDur = dur.toString()
                                                                            break
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                    ) {
                                                        Image(bmp.asImageBitmap(), contentDescription = "滑动选点图片", modifier = Modifier.fillMaxSize())
                                                        Canvas(Modifier.fillMaxSize()) {
                                                            // 终点辅助线（贯穿虚线）
                                                            pickSwipeEnd?.let { p ->
                                                                val px = p.x / imgW * size.width
                                                                val py = p.y / imgH * size.height
                                                                drawLine(Color(0xFFFF6B00).copy(alpha = 0.35f), Offset(0f, py), Offset(size.width, py), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                                                                drawLine(Color(0xFFFF6B00).copy(alpha = 0.35f), Offset(px, 0f), Offset(px, size.height), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                                                            }
                                                            // 滑动线段
                                                            val s = pickSwipeStart
                                                            val e = pickSwipeEnd
                                                            if (s != null && e != null) {
                                                                val sx = s.x / imgW * size.width
                                                                val sy = s.y / imgH * size.height
                                                                val ex = e.x / imgW * size.width
                                                                val ey = e.y / imgH * size.height
                                                                drawLine(Color(0xFFFF6B00), Offset(sx, sy), Offset(ex, ey), 3f)
                                                                drawCircle(Color(0xFF4CAF50), 8f, Offset(sx, sy))
                                                                drawCircle(Color(0xFFE53935), 8f, Offset(ex, ey))
                                                                // 方向箭头
                                                                val dx = ex - sx
                                                                val dy = ey - sy
                                                                val len = kotlin.math.sqrt(dx * dx + dy * dy)
                                                                if (len > 10f) {
                                                                    val ux = dx / len
                                                                    val uy = dy / len
                                                                    val hx = ex - ux * 22f
                                                                    val hy = ey - uy * 22f
                                                                    drawLine(Color(0xFFFF6B00), Offset(hx - uy * 10f, hy + ux * 10f), Offset(ex, ey), 3f)
                                                                    drawLine(Color(0xFFFF6B00), Offset(hx + uy * 10f, hy - ux * 10f), Offset(ex, ey), 3f)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                // 放大镜：跟随手指（不做边缘约束，边角也能放大）
                                                pickSwipeEnd?.let { p ->
                                                    val imgLeft = (maxWidth.value - dispW) / 2f
                                                    val imgTop = (maxHeight.value - dispH) / 2f
                                                    val px = imgLeft + p.x / imgW * dispW
                                                    val py = imgTop + p.y / imgH * dispH
                                                    val lensW = 150f
                                                    val lensH = 150f
                                                    val offX = px - lensW - 20f
                                                    val offY = py - lensH - 20f
                                                    Canvas(
                                                        Modifier.offset(offX.dp, offY.dp).size(lensW.dp, lensH.dp)
                                                            .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.65f))
                                                    ) {
                                                        val zoom = 2.5f
                                                        val srcW = (imgW / zoom).coerceAtLeast(24f)
                                                        val srcH = (imgH / zoom).coerceAtLeast(24f)
                                                        val srcLeft = (p.x - srcW / 2f).toInt()
                                                        val srcTop = (p.y - srcH / 2f).toInt()
                                                        drawImage(
                                                            bmp.asImageBitmap(),
                                                            srcOffset = IntOffset(srcLeft, srcTop),
                                                            srcSize = IntSize(srcW.toInt(), srcH.toInt()),
                                                            dstOffset = IntOffset.Zero,
                                                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                                        )
                                                        val cx = size.width / 2f
                                                        val cy = size.height / 2f
                                                        drawLine(Color.White.copy(alpha = 0.85f), Offset(cx - 14f, cy), Offset(cx + 14f, cy), 2f)
                                                        drawLine(Color.White.copy(alpha = 0.85f), Offset(cx, cy - 14f), Offset(cx, cy + 14f), 2f)
                                                        drawRect(Color.White.copy(alpha = 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                                                    }
                                                }
                                            }
                                            Text(
                                                "起点(${pickSwipeStart?.let { "(${(it.x / imgW * 1600f).toInt()}, ${(it.y / imgH * 720f).toInt()})" } ?: "-"}) 终点(${pickSwipeEnd?.let { "(${(it.x / imgW * 1600f).toInt()}, ${(it.y / imgH * 720f).toInt()})" } ?: "-"}) 时长:${swipeDur}ms",
                                                fontWeight = FontWeight.Bold, fontSize = 12.sp
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                                                Button(
                                                    onClick = {
                                                        pickSwipeStart?.let { s ->
                                                            swipeFromX = (s.x / imgW * 1600f).toInt().toString()
                                                            swipeFromY = (s.y / imgH * 720f).toInt().toString()
                                                        }
                                                        pickSwipeEnd?.let { e ->
                                                            swipeToX = (e.x / imgW * 1600f).toInt().toString()
                                                            swipeToY = (e.y / imgH * 720f).toInt().toString()
                                                        }
                                                        onDirty()
                                                        pickBitmap = null
                                                    },
                                                    enabled = pickSwipeStart != null && pickSwipeEnd != null
                                                ) { Text("确定使用") }
                                                TextButton(onClick = { pickBitmap = null }) { Text("取消") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
            // 突发判定管理
            val nodeBursts = bursts.filter { it.nodeIds.contains(node.id) || it.hitContinueId == node.id || it.judgeNodeId == node.id }
            if (nodeBursts.isNotEmpty()) {
                Text("突发判定", style = MaterialTheme.typography.labelMedium, color = Color(0xFFE53935))
                nodeBursts.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val range = b.nodeIds.mapNotNull { id -> nodes.find { it.id == id }?.name }.joinToString("→")
                        Text(
                            "突发 ${b.name} 判定:${nodes.find { it.id == b.judgeNodeId }?.name ?: "?"} [$range] 命中→:${nodes.find { it.id == b.hitContinueId }?.name ?: "?"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE53935),
                            modifier = Modifier.weight(1f),
                        )
                        PillDangerText("删", onClick = { onDeleteBurst(b.id) }, fontSize = 12)
                    }
                }
            }
            // 相关逻辑链管理
            val nodeLinks = links.filter { it.toId == node.id || it.fromIds.contains(node.id) }
            if (nodeLinks.isNotEmpty()) {
                Text("相关逻辑链", style = MaterialTheme.typography.labelMedium)
                nodeLinks.forEach { link ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val fromNames = link.fromIds.mapNotNull { id -> nodes.find { it.id == id }?.name }.joinToString("+")
                        Text(
                            "${link.type.name} [$fromNames → ${nodes.find { it.id == link.toId }?.name ?: "?"}]",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                            color = linkColor(link.type),
                        )
                        PillDangerText("删", onClick = { onDeleteLink(link.id) }, fontSize = 12)
                    }
                }
            }
            // 像素抠图对话框（框选 → 像素精修 → 透明背景模板）
            cropUri?.let { u ->
                PixelCropDialog(
                    uri = u,
                    onCancel = { cropUri = null },
                    onConfirm = { bmp ->
                        val name = "tpl" + (System.currentTimeMillis() % 100000)
                        com.mas.autofarm.presentation.view.workshop.TemplateStore.saveBitmap(context, bmp, name)?.let { t ->
                            templateId = t.name
                            onDirty()
                        }
                        cropUri = null
                    },
                )
            }
            // 应用选择对话框
            if (showAppPicker) {
                AppPickerDialog(
                    onPick = { pkg ->
                        appPkg = pkg
                        onDirty()
                        showAppPicker = false
                    },
                    onCancel = { showAppPicker = false },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillText("▶试运行", onClick = {
                    val act = when (node.kind) {
                        FlowNodeKind.ACTION, FlowNodeKind.TAP, FlowNodeKind.SWIPE,
                        FlowNodeKind.WAIT, FlowNodeKind.BACK, FlowNodeKind.INPUT -> when (editActionType) {
                            "tap" -> FlowAction.Tap(tapX.toIntOrNull() ?: 0, tapY.toIntOrNull() ?: 0, tapDur.toLongOrNull() ?: 60)
                            "tapt" -> FlowAction.TapTemplate
                            "wait" -> FlowAction.Wait(waitMs.toLongOrNull() ?: 500)
                            "back" -> FlowAction.Back(backTimes.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                            "swipe" -> FlowAction.Swipe(
                                swipeFromX.toIntOrNull() ?: 100,
                                swipeFromY.toIntOrNull() ?: 1000,
                                swipeToX.toIntOrNull() ?: 500,
                                swipeToY.toIntOrNull() ?: 1000,
                                swipeDur.toLongOrNull() ?: 300,
                            )
                            "input" -> FlowAction.Input(inputText)
                            else -> FlowAction.Wait(500)
                        }
                        else -> node.action
                    }
                    testAction(act)
                }, color = Color(0xFF4CAF50))
                PillText("应用", onClick = {
                    val action = when (node.kind) {
                        FlowNodeKind.ACTION, FlowNodeKind.TAP, FlowNodeKind.SWIPE,
                        FlowNodeKind.WAIT, FlowNodeKind.BACK, FlowNodeKind.INPUT -> when (editActionType) {
                            "tap" -> FlowAction.Tap(tapX.toIntOrNull() ?: 0, tapY.toIntOrNull() ?: 0, tapDur.toLongOrNull() ?: 60)
                            "tapt" -> FlowAction.TapTemplate
                            "wait" -> FlowAction.Wait(waitMs.toLongOrNull() ?: 500)
                            "back" -> FlowAction.Back(backTimes.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                            "swipe" -> FlowAction.Swipe(
                                swipeFromX.toIntOrNull() ?: 100,
                                swipeFromY.toIntOrNull() ?: 1000,
                                swipeToX.toIntOrNull() ?: 500,
                                swipeToY.toIntOrNull() ?: 1000,
                                swipeDur.toLongOrNull() ?: 300,
                            )
                            "input" -> FlowAction.Input(inputText)
                            else -> FlowAction.Wait(500)
                        }
                        else -> node.action
                    }
                    onUpdate(
                        node.copy(
                            name = name,
                            appPackage = appPkg.trim(),
                            launchApp = launchApp,
                            durationMs = durationMs.toLongOrNull() ?: 1000,
                            untilTemplateId = untilTemplate,
                            untilTimeoutMs = untilTimeout.toLongOrNull() ?: 0,
                            templateId = templateId,
                            threshold = threshold.toDoubleOrNull() ?: 0.8,
                            retryIntervalMs = retryInterval.toLongOrNull() ?: 2000,
                            maxRetries = maxRetries.toIntOrNull() ?: 0,
                            action = action,
                            resolution = resolution,
                            loopMode = loopMode,
                            loopStartId = loopStartId,
                            loopTimeoutMs = loopTimeoutMs.toLongOrNull() ?: 0,
                            untilJudgeId = untilJudgeId,
                            appStatePkg = appStatePkg.trim(),
                            appStateMode = appStateMode,
                        )
                    )
                    Toast.makeText(context, "应用成功", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }
}

private fun linkColor(type: LinkType): Color = when (type) {
    LinkType.SEQUENCE -> Color(0xFF90A4AE)
    LinkType.YES -> Color(0xFF4CAF50)
    LinkType.NO -> Color(0xFFFDD835)
    LinkType.AND -> Color(0xFFFF9800)
    LinkType.OR -> Color(0xFF2196F3)
}


/** 已安装应用选择器：按应用名搜索，选中自动填入包名 */
@Composable
private fun AppPickerDialog(
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val apps = remember {
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val pm = context.packageManager
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo }
                .filter { it.packageName != context.packageName }
                .mapNotNull { info ->
                    val label = runCatching { info.loadLabel(pm).toString() }.getOrNull() ?: return@mapNotNull null
                    label to info.packageName
                }
                .distinctBy { it.second }
                .sortedBy { it.first }
        }.getOrDefault(emptyList())
    }
    var query by remember { mutableStateOf("") }
    val filtered = apps.filter { (name, pkg) ->
        query.isBlank() || name.contains(query, true) || pkg.contains(query, true)
    }
    val showList = filtered.take(60)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("选择应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用名或包名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp).padding(top = 8.dp),
                ) {
                    items(count = showList.size) { index ->
                        val (name, pkg) = showList[index]
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pkg) }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (showList.isEmpty()) {
                        item { Text("没有匹配的应用", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}
