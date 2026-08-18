package com.mas.autofarm.presentation.view.workshop

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mas.autofarm.constant.DefaultDisplayConfig

/**
 * 模板库：手机文件夹式浏览器。
 * 自建文件夹 / 重命名 / 删除 / 套娃（嵌套）；模板存任意层级；支持搜索全部。
 */
@Composable
fun TemplateManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var path by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf(TemplateStore.listFolders(context, "")) }
    var templates by remember { mutableStateOf(TemplateStore.list(context, "")) }
    var picking by remember { mutableStateOf<Uri?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // 文件夹操作目标
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        folders = TemplateStore.listFolders(context, path)
        templates = TemplateStore.list(context, path)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) picking = uri }

    if (picking != null) {
        CropTemplateScreen(
            src = picking!!,
            savePath = path,
            onSave = { name, roi, res ->
                TemplateStore.save(context, picking!!, name, roi, path, res)
                    ?.let { refresh() }
                picking = null
            },
            onCancel = { picking = null },
        )
        return
    }

    // 全库搜索视图
    if (showSearch) {
        val all = remember { TemplateStore.listAll(context) }
        val filtered = all.filter { searchQuery.isBlank() || it.name.contains(searchQuery, true) }
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showSearch = false }) { Text("‹ 返回") }
                Text("搜索模板", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("输入模板名") },
                modifier = Modifier.fillMaxWidth(),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.id }) { tpl ->
                    TemplateCell(tpl = tpl, onDeleted = {
                        TemplateStore.delete(context, tpl.id)
                    })
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("模板库", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { showSearch = true }) { Text("搜索") }
            Button(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("＋ 导入") }
        }
        // 面包屑
        val parts = if (path.isBlank()) emptyList() else path.split("/")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            items(listOf("根目录") + parts) { seg ->
                val idx = listOf("根目录") + parts
                val target = if (seg == "根目录") "" else parts.take(idx.indexOf(seg) - 1 + 1).joinToString("/")
                TextButton(onClick = { path = target; refresh() }) {
                    Text(if (seg == "根目录") "🏠" else seg)
                }
                if (seg != parts.lastOrNull() ?: "根目录") {
                    Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // 操作行
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { showNewFolder = true }) { Text("📁 新建文件夹") }
            Text(
                if (path.isBlank()) "根目录" else path,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (folders.isEmpty() && templates.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("空目录：可新建文件夹或导入模板", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(folders, key = { "f:$path:$it" }) { folder ->
                    FolderCell(
                        name = folder,
                        onClick = { path = if (path.isBlank()) folder else "$path/$folder"; refresh() },
                        onRename = { renameTarget = folder },
                        onDelete = { deleteTarget = folder },
                    )
                }
                items(templates, key = { it.id }) { tpl ->
                    TemplateCell(tpl = tpl, onDeleted = { refresh() })
                }
            }
        }
    }

    // 新建文件夹
    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("文件夹名（当前：${if (path.isBlank()) "根目录" else path}）") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (TemplateStore.createFolder(context, path, name)) {
                        showNewFolder = false
                        refresh()
                    } else {
                        Toast.makeText(context, "创建失败（名字不合法）", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("取消") } },
        )
    }
    // 重命名文件夹
    renameTarget?.let { target ->
        var newName by remember(target) { mutableStateOf(target) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名文件夹") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("新名字") })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (TemplateStore.renameFolder(context, path, target, newName)) {
                        Toast.makeText(context, "已重命名", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "重命名失败", Toast.LENGTH_SHORT).show()
                    }
                    renameTarget = null
                    refresh()
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
    // 删除文件夹
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除文件夹") },
            text = { Text("确定删除「$target」及其全部内容？不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    TemplateStore.deleteFolder(context, path, target)
                    deleteTarget = null
                    refresh()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun FolderCell(
    name: String,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var cropTarget by remember { mutableStateOf<FlowTemplate?>(null) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .combinedClickable(
                    onClick = { onClick() },
                    onLongClick = { showMenu = true },
                )
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text(name) },
            text = { Text("文件夹操作") },
            confirmButton = {
                TextButton(onClick = { onRename(); showMenu = false }) { Text("重命名") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onDelete(); showMenu = false }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { showMenu = false }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun TemplateCell(tpl: FlowTemplate, onDeleted: () -> Unit) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<FlowTemplate?>(null) }
    var cropTarget by remember { mutableStateOf<FlowTemplate?>(null) }

    // 预览大图
    if (preview) {
        val bmp = remember(tpl.file) { BitmapFactory.decodeFile(tpl.file) }
        AlertDialog(
            onDismissRequest = { preview = false },
            title = { Text(tpl.name) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = tpl.name,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Text("${tpl.width}x${tpl.height}" + (if (tpl.category.isNotBlank()) "  |  ${tpl.category}" else ""),
                        style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { preview = false; cropTarget = tpl }) { Text("抠图") }
                    TextButton(onClick = { preview = false; renameDialog = true }) { Text("改名") }
                    TextButton(onClick = { preview = false; showMenu = true }) { Text("更多") }
                }
            },
            dismissButton = { TextButton(onClick = { preview = false }) { Text("关闭") } },
        )
    }
    cropTarget?.let { tpl ->
        PixelCropDialog(
            uri = android.net.Uri.fromFile(java.io.File(tpl.file)),
            onCancel = { cropTarget = null },
            onConfirm = { bmp ->
                com.mas.autofarm.presentation.view.workshop.TemplateStore.saveBitmap(context, bmp, tpl.name, tpl.category)?.let {
                    Toast.makeText(context, "sample updated " + tpl.name, Toast.LENGTH_SHORT).show()
                    onDeleted()
                }
                cropTarget = null
            },
        )
    }
    // 改名对话框
    if (renameDialog) {
        var newName by remember { mutableStateOf(tpl.name) }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("重命名模板") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("新名字") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (TemplateStore.renameTemplate(context, tpl, newName)) {
                        Toast.makeText(context, "已改名", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "改名失败（重名或非法）", Toast.LENGTH_SHORT).show()
                    }
                    renameDialog = false
                    onDeleted()
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("取消") } },
        )
    }

    // 移动模板对话框（选择目标文件夹）
    moveTarget?.let { t ->
        MoveTemplateDialog(
            template = t,
            onMove = { dest ->
                if (TemplateStore.moveTemplate(context, t, dest)) {
                    Toast.makeText(context, "已移动到 $dest", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "移动失败（目标已存在同名）", Toast.LENGTH_SHORT).show()
                }
                moveTarget = null
                onDeleted()
            },
            onCancel = { moveTarget = null },
        )
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { preview = true }
                .combinedClickable(
                    onClick = { preview = true },
                    onLongClick = { showMenu = true },
                )
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val bmp = remember(tpl.file) { BitmapFactory.decodeFile(tpl.file) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = tpl.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            Text(
                tpl.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (showMenu) {
        var res by remember { mutableStateOf(tpl.recResolution) }
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text(tpl.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("识别分辨率（空=用全局）", style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            "" to "默认",
                            DefaultDisplayConfig.ResolutionPreference.P720.name to "720p",
                            DefaultDisplayConfig.ResolutionPreference.P1600x720.name to "1600x720",
                            DefaultDisplayConfig.ResolutionPreference.P1080.name to "1080p",
                        ).forEach { (v, label) ->
                            FilterChip(selected = res == v, onClick = { res = v }, label = { Text(label) })
                        }
                    }
                    TextButton(onClick = {
                        TemplateStore.updateMeta(context, tpl.copy(recResolution = res))
                        showMenu = false
                    }) { Text("保存分辨率") }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { showMenu = false; preview = true }) { Text("预览") }
                    TextButton(onClick = { showMenu = false; moveTarget = tpl }) { Text("移动") }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        TemplateStore.delete(context, tpl.id)
                        showMenu = false
                        onDeleted()
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { showMenu = false }) { Text("关闭") }
                }
            },
        )
    }
}

/** 导入图片：直接保存整图为模板（已移除框选功能） */
@Composable
private fun CropTemplateScreen(
    src: Uri,
    savePath: String,
    onSave: (String, IntArray?, String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val bmp = remember(src) {
        context.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it) }
    }
    var name by remember { mutableStateOf("") }
    var res by remember { mutableStateOf("") }

    if (bmp == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("图片加载失败") }
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("取消") }
            Text("导入模板图片", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { onSave(name, null, res) }) { Text("保存") }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                .background(Color.DarkGray)
        ) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            "整图保存：${bmp.width}x${bmp.height}px",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("模板名") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("识别分辨率", style = MaterialTheme.typography.labelMedium)
            listOf(
                "" to "默认",
                DefaultDisplayConfig.ResolutionPreference.P720.name to "720p",
                DefaultDisplayConfig.ResolutionPreference.P1600x720.name to "1600x720",
                DefaultDisplayConfig.ResolutionPreference.P1080.name to "1080p",
            ).forEach { (v, label) ->
                FilterChip(selected = res == v, onClick = { res = v }, label = { Text(label) })
            }
        }
        Text(
            "保存到：${if (savePath.isBlank()) "根目录" else savePath}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 移动模板对话框：浏览文件夹树选择目标位置 */
@Composable
private fun MoveTemplateDialog(
    template: FlowTemplate,
    onMove: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var path by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf(TemplateStore.listFolders(context, "")) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("移动到：${if (path.isBlank()) "根目录" else path}") },
        text = {
            Column {
                if (path.isNotBlank()) {
                    TextButton(onClick = {
                        path = path.substringBeforeLast("/", "")
                        folders = TemplateStore.listFolders(context, path)
                    }) { Text("‹ 上级") }
                }
                folders.forEach { f ->
                    TextButton(onClick = {
                        path = if (path.isBlank()) f else "$path/$f"
                        folders = TemplateStore.listFolders(context, path)
                    }) {
                        Text("📁 $f")
                    }
                }
                if (folders.isEmpty() && path.isBlank()) {
                    Text("（根目录下没有文件夹，可移到这里）", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(path) }) { Text("移动到这里") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}
