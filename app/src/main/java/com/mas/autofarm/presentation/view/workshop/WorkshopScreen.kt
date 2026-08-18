package com.mas.autofarm.presentation.view.workshop

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import org.koin.compose.koinInject
import com.mas.autofarm.constant.DefaultDisplayConfig
import com.mas.autofarm.data.preferences.AppSettingsManager
import kotlinx.coroutines.launch
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 原生脚本工坊：项目列表（新建 / 打开 / 删除流程），进入 FlowEditorScreen 编辑。
 * 完全本地：项目与模板存 filesDir/workshop/。
 */
@Composable
fun WorkshopScreen(onLockChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<String?>(null) }

    val projectDir = remember { File(context.filesDir, "workshop") }

    fun listProjects(): List<File> =
        projectDir.listFiles()?.filter { it.isDirectory && File(it, "project.json").exists() }
            ?.sortedByDescending { File(it, "project.json").lastModified() } ?: emptyList()

    var projects by remember { mutableStateOf(listProjects()) }
    var showTemplates by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }

    if (showGuide) {
        WorkshopGuideDialog(onClose = { showGuide = false })
    }

    // 导入流程（JSON 文件 → 解析 FlowProject → 保存到工坊）
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var pendingImportName by remember { mutableStateOf("") }
    var missingConfirm by remember { mutableStateOf<Pair<String, FlowProject>?>(null) } // 未装配自定义节点二次确认

    fun doImportFlow(finalProject: FlowProject, name: String) {
        runCatching {
            val dir = File(projectDir, name)
            dir.mkdirs()
            File(dir, "project.json").writeText(
                kotlinx.serialization.json.Json { prettyPrint = true }
                    .encodeToString(FlowProject.serializer(), finalProject)
            )
            projects = listProjects()
            pendingImportText = null
            Toast.makeText(context, "已导入：$name（${finalProject.nodes.size} 个节点）", Toast.LENGTH_SHORT).show()
        }.onFailure { e ->
            Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                // pipeline 格式没有 name 字段时，用文件名作为默认流程名（可弹窗修改）
                val displayName = runCatching {
                    context.contentResolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
                val baseName = displayName
                    ?.substringBeforeLast('.')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "导入流程"
                // 先解析校验格式（失败直接报错，不弹窗）；默认名优先用文件内 name
                val project = parseImportJson(text, baseName)
                pendingImportText = text
                pendingImportName = project.name.trim().ifBlank { baseName }
            }.onFailure { e ->
                Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 导出流程（文件夹格式：project.json + 自定义节点模板 + 识别模板图片）
    fun exportProject(name: String) {
        runCatching {
            val project = kotlinx.serialization.json.Json.decodeFromString(
                FlowProject.serializer(),
                File(File(projectDir, name), "project.json").readText(),
            )
            val dir = com.mas.autofarm.presentation.view.workshop.FlowExportImport.exportFlow(context, project)
            if (dir != null) {
                Toast.makeText(context, "已导出文件夹：Download/MASS导出/${dir.name}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
            }
        }.onFailure { e ->
            Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 导入文件夹（含流程 + 自定义节点 + 模板图片）
    var folderMissingConfirm by remember { mutableStateOf<List<Pair<String, List<String>>>?>(null) } // 导入后未装配检查
    val folderImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val msg = com.mas.autofarm.presentation.view.workshop.FlowExportImport.importFolder(context, uri)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            projects = listProjects()
            // 导入后检查：用到的自定义节点是否已装配，未装配则弹窗提示
            val missingMap = projects.mapNotNull { dir ->
                runCatching {
                    val p = kotlinx.serialization.json.Json.decodeFromString(
                        FlowProject.serializer(), File(dir, "project.json").readText()
                    )
                    val m = p.nodes.mapNotNull { n ->
                        if (n.customNodeId.isNotBlank() &&
                            com.mas.autofarm.presentation.view.workshop.CustomNodeStore.find(context, n.customNodeId) == null
                        ) n.customNodeId else null
                    }
                    if (m.isNotEmpty()) dir.name to m else null
                }.getOrNull()
            }
            if (missingMap.isNotEmpty()) folderMissingConfirm = missingMap
        }
    }

    if (showTemplates) {
        TemplateManagerScreen(onBack = { showTemplates = false })
        return
    }

    if (editing != null) {
        FlowEditorScreen(
            projectName = editing!!,
            onExit = { editing = null; projects = listProjects() },
            onLockChange = onLockChange,
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("脚本工坊", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ⓘ",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable { showGuide = true }
                    .padding(2.dp),
            )
        }
        Text(
            "可视化制作任务流程：节点 → 识别/动作 → 跳转。完全本地运行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 识别分辨率（难度自选：简单用720p快，难用高分辨率准）
        val appSettings: AppSettingsManager = koinInject()
        val scope = rememberCoroutineScope()
        val recRes by appSettings.recognitionResolution.collectAsState()
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("识别分辨率", style = MaterialTheme.typography.labelMedium)
            FilterChip(
                selected = recRes == DefaultDisplayConfig.ResolutionPreference.P720,
                onClick = { scope.launch { appSettings.setRecognitionResolution(DefaultDisplayConfig.ResolutionPreference.P720) } },
                label = { Text("720p 快") },
            )
            FilterChip(
                selected = recRes == DefaultDisplayConfig.ResolutionPreference.P1600x720,
                onClick = { scope.launch { appSettings.setRecognitionResolution(DefaultDisplayConfig.ResolutionPreference.P1600x720) } },
                label = { Text("1600x720 准") },
            )
            FilterChip(
                selected = recRes == DefaultDisplayConfig.ResolutionPreference.P1080,
                onClick = { scope.launch { appSettings.setRecognitionResolution(DefaultDisplayConfig.ResolutionPreference.P1080) } },
                label = { Text("1080p 最高") },
            )
        }
        // 模板库醒目入口
        Button(
            onClick = { showTemplates = true },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("📷 模板库（导入截图 → 框选 → 做识别模板）")
        }
        OutlinedButton(
            onClick = {
                val dir = com.mas.autofarm.presentation.view.workshop.FlowExportImport.exportAll(context)
                if (dir != null) {
                    Toast.makeText(context, "已导出全部：Download/MASS导出/${dir.name}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("📦 一键导出全部（流程+模板+自定义节点）")
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("新流程名") },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
            ) { Text("导入文件") }
            OutlinedButton(
                onClick = { folderImportLauncher.launch(null) },
            ) { Text("导入文件夹") }
            Button(onClick = {
                val name = newName.trim().ifEmpty { "流程${System.currentTimeMillis() % 10000}" }
                val dir = File(projectDir, name)
                dir.mkdirs()
                val project = FlowProject(name = name)
                File(dir, "project.json").writeText(
                    kotlinx.serialization.json.Json { prettyPrint = true }
                        .encodeToString(FlowProject.serializer(), project)
                )
                newName = ""
                editing = name
            }) { Text("新建") }
        }
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有流程，输入名字新建一个", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.name }) { dir ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { editing = dir.name },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(dir.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "节点: ${readNodeCount(dir)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { renameTarget = dir.name }) { Text("改名") }
                            TextButton(onClick = { exportProject(dir.name) }) { Text("导出") }
                            TextButton(onClick = {
                                dir.deleteRecursively()
                                projects = listProjects()
                                Toast.makeText(context, "已删除：${dir.name}", Toast.LENGTH_SHORT).show()
                            }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
    // 导入名确认对话框（默认=文件名或文件内 name，可修改）
    if (pendingImportText != null) {
        val text = pendingImportText!!
        var inputName by remember(text) { mutableStateOf(pendingImportName) }
        AlertDialog(
            onDismissRequest = { pendingImportText = null },
            title = { Text("导入流程") },
            text = {
                Column {
                    Text(
                        "确认流程名称：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text("流程名") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = inputName.trim()
                        .replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
                        .ifBlank { "导入流程" }
                    val project = parseImportJson(text, name)
                    val finalProject = if (project.name != name) project.copy(name = name) else project
                    // 未装配自定义节点：弹窗二次确认（列出缺失模板），确认后才导入
                    val missingCustom = finalProject.nodes.mapNotNull { n ->
                        if (n.customNodeId.isNotBlank() &&
                            com.mas.autofarm.presentation.view.workshop.CustomNodeStore.find(context, n.customNodeId) == null
                        ) n.customNodeId else null
                    }
                    if (missingCustom.isNotEmpty()) {
                        missingConfirm = name to finalProject
                    } else {
                        doImportFlow(finalProject, name)
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportText = null }) { Text("取消") }
            },
        )
    }
    // 未装配自定义节点二次确认弹窗
    missingConfirm?.let { (name, finalProject) ->
        val missingCustom = finalProject.nodes.mapNotNull { n ->
            if (n.customNodeId.isNotBlank() &&
                com.mas.autofarm.presentation.view.workshop.CustomNodeStore.find(context, n.customNodeId) == null
            ) n.customNodeId else null
        }
        AlertDialog(
            onDismissRequest = { missingConfirm = null },
            title = { Text("⚠️ 使用了未装配的自定义节点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("该流程用到了本地没有的自定义节点模板：")
                    missingCustom.forEach { Text("· $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    Text("导入流程后这些节点会以占位形式存在，导入对应自定义节点 .json 后自动生效。仍要导入吗？", fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    missingConfirm = null
                    doImportFlow(finalProject, name)
                }) { Text("仍要导入") }
            },
            dismissButton = { TextButton(onClick = { missingConfirm = null }) { Text("取消") } },
        )
    }

    // 文件夹导入后的未装配自定义节点弹窗提示
    folderMissingConfirm?.let { map ->
        AlertDialog(
            onDismissRequest = { folderMissingConfirm = null },
            title = { Text("⚠️ 检测到未装配的自定义节点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("以下流程用到了本地没有的自定义节点模板：")
                    map.forEach { (flowName, missing) ->
                        Text("「$flowName」：${missing.joinToString(",")}", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    Text("导入对应自定义节点 .json 后自动生效。", fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { folderMissingConfirm = null }) { Text("知道了") } },
        )
    }

    // 改名对话框
    renameTarget?.let { old ->
        var input by remember(old) { mutableStateOf(old) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名流程") },
            text = {
                OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("新名字") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val safe = input.trim().replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
                    if (safe.isBlank() || safe == old) {
                        renameTarget = null
                    } else {
                        val newDir = File(projectDir, safe)
                        if (newDir.exists()) {
                            Toast.makeText(context, "已存在同名流程", Toast.LENGTH_SHORT).show()
                        } else {
                            val ok = File(projectDir, old).renameTo(newDir)
                            if (ok) {
                                // 更新 project.json 里的 name
                                runCatching {
                                    val projFile = File(newDir, "project.json")
                                    if (projFile.exists()) {
                                        val proj = kotlinx.serialization.json.Json.decodeFromString(
                                            FlowProject.serializer(), projFile.readText()
                                        )
                                        projFile.writeText(
                                            kotlinx.serialization.json.Json { prettyPrint = true }
                                                .encodeToString(FlowProject.serializer(), proj.copy(name = safe))
                                        )
                                    }
                                }
                                Toast.makeText(context, "已改名为：$safe（文件夹已同步）", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "改名失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                        renameTarget = null
                        projects = listProjects()
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
}


private fun readNodeCount(dir: File): Int {
    return runCatching {
        val project = kotlinx.serialization.json.Json
            .decodeFromString(FlowProject.serializer(), File(dir, "project.json").readText())
        project.nodes.size
    }.getOrDefault(0)
}

/**
 * 导入解析：自动识别格式。
 * 1. 工坊 FlowProject 格式（含 "nodes" 字段）→ 直接解析。
 * 2. MaaFW Pipeline 格式（任务名 → 任务配置：action/algorithm/next/onError）→ 转换为 FlowProject：
 *    - 每个任务转一个节点（识别算法→IMAGE，其余→ACTION，preDelay→等待时长）
 *    - next → YES 逻辑链，onError → NO 逻辑链（目标找不到时忽略）
 *    - 头部补一个不启动应用的 INFO 节点（工坊约定第一个节点为 INFO）
 */
fun parseImportJson(text: String, fallbackName: String): FlowProject {
    val root = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
    if (root.containsKey("nodes")) {
        return kotlinx.serialization.json.Json.decodeFromString(FlowProject.serializer(), text)
    }
    // MaaFW Pipeline 格式
    val idByName = mutableMapOf<String, String>()
    val orderedNames = root.keys.toList()
    orderedNames.forEach { name -> idByName[name] = java.util.UUID.randomUUID().toString() }

    val nodes = mutableListOf<FlowNode>()
    nodes.add(
        FlowNode(
            id = java.util.UUID.randomUUID().toString(),
            name = "启动",
            kind = FlowNodeKind.INFO,
            launchApp = false,
            x = 40f,
            y = 0f,
        )
    )
    orderedNames.forEachIndexed { index, name ->
        val element = root.getValue(name)
        val obj = element.jsonObject
        val nodeId = idByName.getValue(name)
        val kind = if (obj.containsKey("algorithm")) FlowNodeKind.IMAGE else FlowNodeKind.ACTION
        val durationMs = (obj["preDelay"]?.jsonPrimitive?.longOrNull ?: 0L).coerceAtLeast(0)
        val templateId = obj["template"]?.jsonPrimitive?.content.orEmpty().removeSuffix(".png")
        val threshold = obj["threshold"]?.jsonPrimitive?.doubleOrNull ?: 0.8
        val action = when (obj["action"]?.jsonPrimitive?.content) {
            "Click" -> {
                val rect = obj["specificRect"]?.jsonArray
                FlowAction.Tap(
                    rect?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0,
                    rect?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
            "Back" -> FlowAction.Back(1)
            "Swipe" -> {
                val rect = obj["specificRect"]?.jsonArray
                FlowAction.Swipe(
                    rect?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0,
                    rect?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 0,
                    rect?.getOrNull(2)?.jsonPrimitive?.intOrNull ?: 0,
                    rect?.getOrNull(3)?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
            else -> FlowAction.Wait(durationMs)
        }
        // 按顺序垂直排列（节点约 170x72dp，间距 100dp 错开）
        nodes.add(
            FlowNode(
                id = nodeId,
                name = name,
                kind = kind,
                durationMs = durationMs,
                templateId = templateId,
                threshold = threshold,
                action = action,
                x = 40f,
                y = (index + 1) * 172f,
            )
        )
    }

    val allIds = nodes.map { it.id }.toSet()
    fun resolveTarget(ref: String): String? =
        idByName[ref] ?: ref.takeIf { it in allIds }

    val links = mutableListOf<FlowLink>()
    // 忠实还原文件内容：不生成文件里不存在的顺序线；
    // 顺序执行由 FlowEngine 按节点列表顺序兜底（无连线自动走下一个）
    // 只保留文件里显式的 next/onError 分支线（目标存在时）
    root.forEach { (name, element) ->
        val obj = element.jsonObject
        val fromId = idByName.getValue(name)
        obj["next"]?.jsonArray?.forEach { el ->
            resolveTarget(el.jsonPrimitive.content)?.let { to ->
                links.add(
                    FlowLink(
                        id = java.util.UUID.randomUUID().toString(),
                        type = LinkType.YES,
                        fromIds = listOf(fromId),
                        toId = to,
                    )
                )
            }
        }
        obj["onError"]?.jsonArray?.forEach { el ->
            resolveTarget(el.jsonPrimitive.content)?.let { to ->
                links.add(
                    FlowLink(
                        id = java.util.UUID.randomUUID().toString(),
                        type = LinkType.NO,
                        fromIds = listOf(fromId),
                        toId = to,
                    )
                )
            }
        }
    }
    return FlowProject(name = fallbackName, nodes = nodes, links = links)
}