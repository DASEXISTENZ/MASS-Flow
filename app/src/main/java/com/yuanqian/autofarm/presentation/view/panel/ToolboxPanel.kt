package com.yuanqian.autofarm.presentation.view.panel

import android.graphics.BitmapFactory
import com.yuanqian.autofarm.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuanqian.autofarm.manager.RemoteServiceManager
import com.yuanqian.autofarm.presentation.viewmodel.BackgroundTaskViewModel
import com.yuanqian.autofarm.presentation.viewmodel.ToolboxTab
import com.yuanqian.autofarm.presentation.viewmodel.ToolboxViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import java.io.File

@Composable
fun ToolboxPanel(
    modifier: Modifier = Modifier,
    viewModel: ToolboxViewModel = koinInject()
) {
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val visibleTabs by viewModel.visibleTabs.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        // 子 Tab：等分铺满；前台模式不展示牛牛抽卡
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            visibleTabs.forEach { tab ->
                val selected = currentTab == tab
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (selected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clickable { viewModel.onTabChange(tab) }
                ) {
                    // Box 铺满 Surface，文字水平+垂直居中
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(tab.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            softWrap = true,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }

        // 内容区：虚拟坐标选取工具
        VirtualCoordPicker(modifier = Modifier.fillMaxSize())
    }
}

/** 虚拟坐标选取：暂停流程 → 冻结虚拟屏画面 → 点击/滑动取坐标 → 记录 */
@Composable
fun VirtualCoordPicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val flowPaused by com.yuanqian.autofarm.presentation.state.FlowRuntimeHolder.flowPaused.collectAsStateWithLifecycle()
    val isFlowRunning by com.yuanqian.autofarm.presentation.state.FlowRuntimeHolder.flowRunning.collectAsStateWithLifecycle()

    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var pickMode by remember { mutableStateOf("tap") } // tap / swipe
    var pickPoint by remember { mutableStateOf<Offset?>(null) }
    var swipeStart by remember { mutableStateOf<Offset?>(null) }
    var swipeEnd by remember { mutableStateOf<Offset?>(null) }
    var swipeDownMs by remember { mutableStateOf(0L) }
    val records = remember { mutableStateListOf<String>() }

    // 截帧：虚拟屏帧 → MaaCore 帧 → 物理屏 screencap 三层兜底。
    // 运行中持续刷新；暂停时若有画面则冻结（不再刷新），若无画面继续补截到有为止。
    LaunchedEffect(Unit) {
        while (true) {
            if (!flowPaused || frame == null) {
                val srv = RemoteServiceManager.getInstanceOrNull()
                if (srv != null) {
                    runCatching {
                        val dir = File("/data/local/tmp", "vcp").apply { mkdirs() }
                        val bmp = srv.captureFramePng(dir.absolutePath)?.let { BitmapFactory.decodeFile(it) }
                            ?: runCatching {
                                val core = srv.getMaaCoreService()
                                val pfd = core.GetImage()
                                pfd?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor) }
                            }.getOrNull()
                            ?: srv.capturePhysicalPng(dir.absolutePath)?.let { BitmapFactory.decodeFile(it) }
                        bmp
                    }.onSuccess { bmp -> if (bmp != null) frame = bmp }
                }
            }
            delay(1000)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        // 状态行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    !isFlowRunning -> "未运行流程"
                    flowPaused -> "⏸ 已暂停（画面已冻结）"
                    else -> "▶ 流程运行中"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    !isFlowRunning -> MaterialTheme.colorScheme.onSurfaceVariant
                    flowPaused -> Color(0xFFFFA000)
                    else -> Color(0xFF4CAF50)
                },
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "模式：${if (pickMode == "tap") "点击" else "滑动"}" +
                    (frame?.let { " ｜画面 ${it.width}×${it.height}" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 预览区（冻结帧 + 选点层）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        ) {
            val bmp = frame
            if (bmp != null) {
                val imgW = bmp.width.toFloat()
                val imgH = bmp.height.toFloat()
                BoxWithConstraints(Modifier.fillMaxSize()) {
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
                                                    if (pickMode == "tap") {
                                                        pickPoint = Offset(change.position.x / size.width * imgW, change.position.y / size.height * imgH)
                                                    } else {
                                                        val pt = Offset(change.position.x / size.width * imgW, change.position.y / size.height * imgH)
                                                        swipeStart = pt
                                                        swipeEnd = pt
                                                        swipeDownMs = System.currentTimeMillis()
                                                    }
                                                } else if (change.position != lastPos) {
                                                    lastPos = change.position
                                                    change.consume()
                                                    if (pickMode == "swipe") {
                                                        swipeEnd = Offset(change.position.x / size.width * imgW, change.position.y / size.height * imgH)
                                                    } else {
                                                        pickPoint = Offset(change.position.x / size.width * imgW, change.position.y / size.height * imgH)
                                                    }
                                                }
                                            } else if (down) {
                                                break
                                            }
                                        }
                                    }
                                }
                        ) {
                            Image(bmp.asImageBitmap(), contentDescription = "虚拟屏画面", modifier = Modifier.fillMaxSize())
                            Canvas(Modifier.fillMaxSize()) {
                                if (pickMode == "tap") {
                                    pickPoint?.let { p ->
                                        val px = p.x / imgW * size.width
                                        val py = p.y / imgH * size.height
                                        drawLine(Color(0xFFFF6B00).copy(alpha = 0.45f), Offset(0f, py), Offset(size.width, py), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                                        drawLine(Color(0xFFFF6B00).copy(alpha = 0.45f), Offset(px, 0f), Offset(px, size.height), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                                        drawLine(Color(0xFFFF6B00), Offset(px - 18f, py), Offset(px + 18f, py), 2.5f)
                                        drawLine(Color(0xFFFF6B00), Offset(px, py - 18f), Offset(px, py + 18f), 2.5f)
                                        drawCircle(Color(0xFFFF6B00), 6f, Offset(px, py))
                                    }
                                } else {
                                    swipeEnd?.let { p ->
                                        val px = p.x / imgW * size.width
                                        val py = p.y / imgH * size.height
                                        drawLine(Color(0xFFFF6B00).copy(alpha = 0.45f), Offset(0f, py), Offset(size.width, py), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                                        drawLine(Color(0xFFFF6B00).copy(alpha = 0.45f), Offset(px, 0f), Offset(px, size.height), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                                    }
                                    val s = swipeStart
                                    val e = swipeEnd
                                    if (s != null && e != null) {
                                        val sx = s.x / imgW * size.width
                                        val sy = s.y / imgH * size.height
                                        val ex = e.x / imgW * size.width
                                        val ey = e.y / imgH * size.height
                                        drawLine(Color(0xFFFF6B00), Offset(sx, sy), Offset(ex, ey), 3f)
                                        drawCircle(Color(0xFF4CAF50), 8f, Offset(sx, sy))
                                        drawCircle(Color(0xFFE53935), 8f, Offset(ex, ey))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "无画面：服务未连接或截图失败\n（请确认流程运行中且后台服务已连接）",
                        color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center
                    )
                }
            }
            // 当前选中坐标悬浮提示（左上角）
            val hint = if (pickMode == "tap") {
                pickPoint?.let { "选中: (${it.x.toInt()}, ${it.y.toInt()})" }
            } else {
                if (swipeStart != null && swipeEnd != null) {
                    val dur = (System.currentTimeMillis() - swipeDownMs).coerceAtLeast(0)
                    "起点(${swipeStart!!.x.toInt()},${swipeStart!!.y.toInt()}) → 终点(${swipeEnd!!.x.toInt()},${swipeEnd!!.y.toInt()}) 时长${dur}ms"
                } else null
            }
            if (hint != null) {
                Text(
                    hint,
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart).padding(8.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // 控制按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { com.yuanqian.autofarm.presentation.state.FlowRuntimeHolder.togglePause() },
                enabled = isFlowRunning,
                modifier = Modifier.weight(1f)
            ) { Text(if (flowPaused) "▶ 继续" else "⏸ 暂停") }
            OutlinedButton(
                onClick = {
                    pickMode = if (pickMode == "tap") "swipe" else "tap"
                    pickPoint = null; swipeStart = null; swipeEnd = null
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (pickMode == "tap") "🖱 点击" else "✏️ 滑动") }
            Button(
                onClick = {
                    if (pickMode == "tap") {
                        pickPoint?.let { p -> records.add(0, "tap (${p.x.toInt()}, ${p.y.toInt()})") }
                    } else {
                        val s = swipeStart; val e = swipeEnd
                        if (s != null && e != null) {
                            val dur = if (swipeDownMs > 0) (System.currentTimeMillis() - swipeDownMs).coerceAtLeast(1) else 300
                            records.add(0, "swipe (${s.x.toInt()},${s.y.toInt()})→(${e.x.toInt()},${e.y.toInt()}) ${dur}ms")
                        }
                    }
                },
                enabled = if (pickMode == "tap") pickPoint != null else (swipeStart != null && swipeEnd != null),
                modifier = Modifier.weight(1f)
            ) { Text("📋 记录") }
            OutlinedButton(
                onClick = { records.clear() },
                enabled = records.isNotEmpty()
            ) { Text("清空") }
        }

        // 记录列表
        if (records.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(6.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                records.forEachIndexed { i, rec ->
                    Text(
                        "${i + 1}. $rec",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        } else {
            Text(
                "操作：暂停流程冻结画面 → 在画面上点选/画线 → 「记录」保存坐标",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
