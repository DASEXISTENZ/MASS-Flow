package com.mas.autofarm.presentation.view.workshop

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * 像素画抠图：
 * 1. 框选目标区域
 * 2. 像素精修：橡皮擦（可调大小，滑动连续擦除）/ 恢复 / 颜色抠除（点色→相似色透明）
 * 3. 缩放显示 + 像素网格（缩放越大格子越清晰），抠掉的像素显示为暗格
 * 4. 输出带透明通道 Bitmap（背景变化不影响识别）
 */
@Composable
fun PixelCropDialog(
    uri: Uri,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val srcBmp = remember(uri) {
        runCatching {
            android.graphics.BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
        }.getOrNull()
    }
    if (srcBmp == null) {
        LaunchedEffect(Unit) { onCancel() }
        return
    }
    var stage by remember { mutableStateOf("select") }
    // 框选四角（像素坐标，支持拖四角/整体移动/重新框选）
    var selX0 by remember(uri) { mutableStateOf(0) }
    var selY0 by remember(uri) { mutableStateOf(0) }
    var selX1 by remember(uri) { mutableStateOf(srcBmp.width) }
    var selY1 by remember(uri) { mutableStateOf(srcBmp.height) }
    var tol by remember { mutableFloatStateOf(30f) }
    var tool by remember { mutableStateOf("erase") }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) } // 放大后平移视图
    var tick by remember { mutableIntStateOf(0) } // 触发重绘（滑动擦除时）

    // 格子单位：每格代表多少原始像素。最大缩放(16x)时=2px，每降一档 4 格合 1（2 的幂），格子密度减半
    val gridPixel = run {
        val unit = (32f / scale).toInt().coerceAtLeast(1)
        1 shl (31 - Integer.numberOfLeadingZeros(unit))
    }
    val workBmp = remember(srcBmp, selX0, selY0, selX1, selY1, stage) {
        if (stage == "refine") {
            val x0 = selX0.coerceIn(0, srcBmp.width - 1)
            val y0 = selY0.coerceIn(0, srcBmp.height - 1)
            val x1 = selX1.coerceIn(x0 + 1, srcBmp.width)
            val y1 = selY1.coerceIn(y0 + 1, srcBmp.height)
            Bitmap.createBitmap(srcBmp, x0, y0, x1 - x0, y1 - y0)
                .copy(Bitmap.Config.ARGB_8888, true)
        } else null
    }
    // 原始像素快照：恢复（restore）时从原始像素还原，而不是永远透明
    val origPixels = remember(workBmp) {
        workBmp?.let { b ->
            IntArray(b.width * b.height).also { arr -> b.getPixels(arr, 0, b.width, 0, 0, b.width, b.height) }
        }
    }
    var mask by remember(workBmp) { mutableStateOf<IntArray?>(null) }
    val undoStack = remember(workBmp) { mutableListOf<IntArray>() }
    if (workBmp != null && mask == null) {
        mask = IntArray(workBmp.width * workBmp.height) { 1 }
    }

    /** 每次操作前压入当前 mask 快照（一次手势/一次点按 = 一个撤销步） */
    fun pushUndo() {
        val cur = mask ?: return
        undoStack.add(cur.copyOf())
        if (undoStack.size > 30) undoStack.removeAt(0)
    }

    fun applyMask() {
        val bmp = workBmp ?: return
        val m = mask ?: return
        val orig = origPixels ?: return
        for (i in m.indices) {
            if (m[i] == 0) {
                bmp.setPixel(i % bmp.width, i / bmp.width, android.graphics.Color.TRANSPARENT)
            } else {
                bmp.setPixel(i % bmp.width, i / bmp.width, orig[i])
            }
        }
    }

    /** 以格子 (gx,gy) 为单位擦除/恢复：整格操作，滑动经过的格子全部选中 */
    fun paintCell(gx: Int, gy: Int, erasing: Boolean) {
        val bmp = workBmp ?: return
        val m = mask ?: return
        val orig = origPixels
        val w = bmp.width
        val x0 = (gx * gridPixel).coerceIn(0, w - 1)
        val y0 = (gy * gridPixel).coerceIn(0, bmp.height - 1)
        val x1 = (x0 + gridPixel).coerceAtMost(w)
        val y1 = (y0 + gridPixel).coerceAtMost(bmp.height)
        // 只更新该格子范围（不做全图 applyMask，避免拖动时卡顿）
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val idx = y * w + x
                m[idx] = if (erasing) 0 else 1
                bmp.setPixel(x, y, if (erasing) android.graphics.Color.TRANSPARENT else (orig?.get(idx) ?: bmp.getPixel(x, y)))
            }
        }
        tick++
    }

    /** 颜色抠除：以 (px,py) 颜色为基准，相似色全部透明 */
    fun pickColorAt(px: Int, py: Int) {
        val bmp = workBmp ?: return
        val m = mask ?: return
        val target = bmp.getPixel(px.coerceIn(0, bmp.width - 1), py.coerceIn(0, bmp.height - 1))
        val tr = android.graphics.Color.red(target)
        val tg = android.graphics.Color.green(target)
        val tb = android.graphics.Color.blue(target)
        for (i in m.indices) {
            if (m[i] == 1) {
                val c = bmp.getPixel(i % bmp.width, i / bmp.width)
                val d = kotlin.math.abs(android.graphics.Color.red(c) - tr) +
                    kotlin.math.abs(android.graphics.Color.green(c) - tg) +
                    kotlin.math.abs(android.graphics.Color.blue(c) - tb)
                if (d < tol * 3) m[i] = 0
            }
        }
        applyMask()
        tick++
    }

    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp)).padding(16.dp)
        ) {
            if (stage == "select") {
                Text("✂ 像素抠图：框选目标区域（拖四角调整 / 选区内拖动整体移动）", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                val sx0 = minOf(selX0, selX1); val sy0 = minOf(selY0, selY1)
                val sx1 = maxOf(selX0, selX1); val sy1 = maxOf(selY0, selY1)
                Text("选区 ($sx0,$sy0)-($sx1,$sy1)  ${sx1 - sx0}×${sy1 - sy0}px", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().aspectRatio(srcBmp.width.toFloat() / srcBmp.height.toFloat())
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            // 框选交互：选区外拖动=重新框选；四角附近=拖角；选区内=整体移动
                            var dragMode = "new"
                            var startPx = Offset.Zero
                            var startRect = intArrayOf(0, 0, 0, 0)
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val px = (pos.x / size.width * srcBmp.width).toInt().coerceIn(0, srcBmp.width)
                                    val py = (pos.y / size.height * srcBmp.height).toInt().coerceIn(0, srcBmp.height)
                                    val x0 = minOf(selX0, selX1); val y0 = minOf(selY0, selY1)
                                    val x1 = maxOf(selX0, selX1); val y1 = maxOf(selY0, selY1)
                                    startPx = Offset(px.toFloat(), py.toFloat())
                                    startRect = intArrayOf(x0, y0, x1, y1)
                                    val cornerR = maxOf(10, (x1 - x0) / 4, (y1 - y0) / 4)
                                    dragMode = when {
                                        px in x0..x1 && py in y0..y1 -> {
                                            if (px - x0 <= cornerR && py - y0 <= cornerR) "tl"
                                            else if (x1 - px <= cornerR && y1 - py <= cornerR) "br"
                                            else "move"
                                        }
                                        else -> "new"
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val px = (change.position.x / size.width * srcBmp.width).toInt().coerceIn(0, srcBmp.width)
                                    val py = (change.position.y / size.height * srcBmp.height).toInt().coerceIn(0, srcBmp.height)
                                    when (dragMode) {
                                        "new" -> { selX0 = startPx.x.toInt(); selY0 = startPx.y.toInt(); selX1 = px; selY1 = py }
                                        "move" -> {
                                            val dx = px - startPx.x.toInt(); val dy = py - startPx.y.toInt()
                                            selX0 = (startRect[0] + dx).coerceIn(0, srcBmp.width - 1)
                                            selY0 = (startRect[1] + dy).coerceIn(0, srcBmp.height - 1)
                                            selX1 = (startRect[2] + dx).coerceIn(selX0 + 1, srcBmp.width)
                                            selY1 = (startRect[3] + dy).coerceIn(selY0 + 1, srcBmp.height)
                                        }
                                        "tl" -> { selX0 = px.coerceIn(0, selX1 - 1); selY0 = py.coerceIn(0, selY1 - 1) }
                                        "br" -> { selX1 = px.coerceIn(selX0 + 1, srcBmp.width); selY1 = py.coerceIn(selY0 + 1, srcBmp.height) }
                                    }
                                },
                                onDragEnd = {},
                                onDragCancel = {},
                            )
                        }
                ) {
                    Image(bitmap = srcBmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        val x0 = minOf(selX0, selX1); val y0 = minOf(selY0, selY1)
                        val x1 = maxOf(selX0, selX1); val y1 = maxOf(selY0, selY1)
                        val ox = size.width * x0 / srcBmp.width
                        val oy = size.height * y0 / srcBmp.height
                        val rw = size.width * (x1 - x0) / srcBmp.width
                        val rh = size.height * (y1 - y0) / srcBmp.height
                        drawRect(color = Color.Black.copy(alpha = 0.5f), topLeft = Offset(0f, 0f), size = Size(size.width, size.height))
                        drawRect(color = Color.Transparent, topLeft = Offset(ox, oy), size = Size(rw, rh))
                        drawRect(color = Color(0xFF4CAF50), topLeft = Offset(ox, oy), size = Size(rw, rh), style = Stroke(2.dp.toPx()))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = { scale = 1f; stage = "refine" }, modifier = Modifier.weight(1f)) { Text("下一步：像素精修") }
                }
            } else {
                val bmp = workBmp ?: return@Column
                Text("🧹 像素精修：滑动擦除背景（背景变化不影响识别）", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                // 工具
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("erase" to "橡皮擦", "restore" to "恢复", "pick" to "颜色抠除", "pan" to "平移").forEach { (v, label) ->
                        Button(
                            onClick = { tool = v },
                            modifier = Modifier.weight(1f),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = if (tool == v) Color(0xFF3A6EA5) else Color(0xFF2A2A2A),
                            ),
                        ) { Text(label, fontSize = 12.sp) }
                    }
                }
                // 容差 / 笔刷大小 / 缩放
                if (tool == "pick") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("容差", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        Slider(value = tol, onValueChange = { tol = it }, valueRange = 5f..120f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        Text("${tol.toInt()}", color = Color.White, fontSize = 12.sp)
                    }
                } else if (tool == "pan") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("拖动平移视图；放大后格子自动显示", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("按住拖动：经过的像素格自动选中", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("缩放", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    OutlinedButton(onClick = { scale = (scale * 2f).coerceAtMost(16f); pan = Offset.Zero }, modifier = Modifier.weight(1f)) { Text("＋") }
                    OutlinedButton(onClick = { scale = (scale / 2f).coerceAtLeast(1f); pan = Offset.Zero }, modifier = Modifier.weight(1f)) { Text("－") }
                    OutlinedButton(onClick = { scale = 1f; pan = Offset.Zero }, modifier = Modifier.weight(1f)) { Text("适应") }
                    Text("${"%.1f".format(scale)}x", color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                // 画布：放大层 + 涂抹 + 网格 + 抠除暗格
                val baseW = bmp.width.toFloat()
                val baseH = bmp.height.toFloat()
                Box(
                    Modifier.fillMaxWidth().aspectRatio(baseW / baseH)
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(tool) {
                            var lastGx = -1
                            var lastGy = -1
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val px = ((pos.x - pan.x) / size.width * baseW / scale).toInt().coerceIn(0, bmp.width - 1)
                                    val py = ((pos.y - pan.y) / size.height * baseH / scale).toInt().coerceIn(0, bmp.height - 1)
                                    if (tool == "erase" || tool == "restore") pushUndo() // 每次手势一个撤销步
                                    lastGx = px / gridPixel
                                    lastGy = py / gridPixel
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (tool == "pan") {
                                        // 放大后平移：只能在图像范围内拖动，避免拖出空白
                                        pan = Offset(
                                            (pan.x + dragAmount.x).coerceIn(-size.width * (scale - 1), 0f),
                                            (pan.y + dragAmount.y).coerceIn(-size.height * (scale - 1), 0f),
                                        )
                                    } else {
                                        val px = ((change.position.x - pan.x) / size.width * baseW / scale).toInt().coerceIn(0, bmp.width - 1)
                                        val py = ((change.position.y - pan.y) / size.height * baseH / scale).toInt().coerceIn(0, bmp.height - 1)
                                        if (tool == "pick") {
                                            pickColorAt(px, py)
                                        } else {
                                            // 按格子操作：滑动经过的格子全部选中（相邻格子插值补全，保证连续）
                                            val gx = px / gridPixel
                                            val gy = py / gridPixel
                                            val n = maxOf(kotlin.math.abs(gx - lastGx), kotlin.math.abs(gy - lastGy))
                                            if (n <= 1) {
                                                if (tool == "erase") paintCell(gx, gy, true) else paintCell(gx, gy, false)
                                            } else {
                                                for (i in 1..n) {
                                                    val igx = lastGx + (gx - lastGx) * i / n
                                                    val igy = lastGy + (gy - lastGy) * i / n
                                                    if (tool == "erase") paintCell(igx, igy, true) else paintCell(igx, igy, false)
                                                }
                                            }
                                            lastGx = gx
                                            lastGy = gy
                                        }
                                    }
                                },
                                onDragEnd = {},
                                onDragCancel = {},
                            )
                        }
                        .pointerInput(tool) {
                            detectTapGestures { pos ->
                                val px = ((pos.x - pan.x) / size.width * baseW / scale).toInt().coerceIn(0, bmp.width - 1)
                                val py = ((pos.y - pan.y) / size.height * baseH / scale).toInt().coerceIn(0, bmp.height - 1)
                                when (tool) {
                                    "pick" -> pickColorAt(px, py)
                                    "erase" -> { pushUndo(); paintCell(px / gridPixel, py / gridPixel, true) }
                                    "restore" -> { pushUndo(); paintCell(px / gridPixel, py / gridPixel, false) }
                                    else -> {}
                                }
                            }
                        }
                ) {
                    // 放大层：图片按 scale 放大（左上对齐绘制，pan 平移）
                    Canvas(Modifier.fillMaxSize()) {
                        val _tick = tick // 依赖 tick：擦除/恢复/取色后立即重绘
                        val drawW = size.width * scale
                        val drawH = size.height * scale
                        val off = pan
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstSize = IntSize(drawW.toInt(), drawH.toInt()),
                            dstOffset = IntOffset(off.x.toInt(), off.y.toInt()),
                        )
                        // 网格：每 gridPixel 个原始像素一格（屏幕大小恒定，缩放后自动加载）
                        val cellPx = size.width * scale * gridPixel / baseW
                        if (cellPx >= 5f) {
                            var gx = 0
                            while (gx <= bmp.width) {
                                val x = gx * size.width * scale / bmp.width + off.x
                                drawLine(Color.White.copy(alpha = 0.25f), Offset(x, off.y), Offset(x, size.height * scale + off.y), 0.5f)
                                gx += gridPixel
                            }
                            var gy = 0
                            while (gy <= bmp.height) {
                                val y = gy * size.height * scale / bmp.height + off.y
                                drawLine(Color.White.copy(alpha = 0.25f), Offset(off.x, y), Offset(size.width * scale + off.x, y), 0.5f)
                                gy += gridPixel
                            }
                        }
                        // 暗格：按格子聚合（格子内全透明→深色，部分透明→浅色）
                        val m = mask ?: return@Canvas
                        val bw = bmp.width
                        val bh = bmp.height
                        val cellW = size.width * scale * gridPixel / bw
                        val cellH = size.height * scale * gridPixel / bh
                        var gy = 0
                        while (gy < bh) {
                            var gx = 0
                            while (gx < bw) {
                                var cleared = 0
                                var total = 0
                                for (dy in 0 until gridPixel) {
                                    for (dx in 0 until gridPixel) {
                                        val xx = gx + dx
                                        val yy = gy + dy
                                        if (xx < bw && yy < bh) {
                                            total++
                                            if (m[yy * bw + xx] == 0) cleared++
                                        }
                                    }
                                }
                                if (cleared > 0) {
                                    val color = if (cleared == total) Color(0x88000000) else Color(0x33000000)
                                    drawRect(color, Offset(gx * cellW + off.x, gy * cellH + off.y), Size(cellW + 0.5f, cellH + 0.5f))
                                }
                                gx += gridPixel
                            }
                            gy += gridPixel
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        pushUndo()
                        mask = IntArray(bmp.width * bmp.height) { 1 }
                        applyMask()
                        tick++
                    }, modifier = Modifier.weight(1f)) { Text("重置") }
                    OutlinedButton(onClick = {
                        if (undoStack.isNotEmpty()) {
                            mask = undoStack.removeAt(undoStack.size - 1)
                            applyMask()
                            tick++
                        }
                    }, modifier = Modifier.weight(1f)) { Text("撤销") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { stage = "select" }, modifier = Modifier.weight(1f)) { Text("返回框选") }
                    Button(onClick = {
                        applyMask()
                        onConfirm(bmp)
                    }, modifier = Modifier.weight(1f)) { Text("完成抠图") }
                }
            }
        }
    }
}