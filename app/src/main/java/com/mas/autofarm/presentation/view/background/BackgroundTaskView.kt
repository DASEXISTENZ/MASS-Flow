package com.mas.autofarm.presentation.view.background

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mas.autofarm.R
import com.mas.autofarm.constant.DefaultDisplayConfig
import com.mas.autofarm.data.permission.PermissionState
import com.mas.autofarm.data.preferences.AppSettingsManager
import com.mas.autofarm.domain.models.RunMode
import com.mas.autofarm.domain.service.AppWatchdog
import com.mas.autofarm.domain.service.MaaCompositionService
import com.mas.autofarm.domain.service.UnifiedStateDispatcher
import com.mas.autofarm.domain.state.MaaExecutionState
import com.mas.autofarm.manager.PermissionManager
import com.mas.autofarm.overlay.screensaver.ScreenSaverOverlayManager
import com.mas.autofarm.presentation.LocalInputFocusManager
import com.mas.autofarm.presentation.components.AdaptiveTaskPromptDialog
import com.mas.autofarm.presentation.components.ShizukuReadinessGate
import com.mas.autofarm.presentation.view.panel.LogPanel
import com.mas.autofarm.presentation.view.panel.PanelDialogType
import com.mas.autofarm.presentation.view.panel.PanelTab
import com.mas.autofarm.presentation.view.panel.TaskListDetailLayout
import com.mas.autofarm.presentation.viewmodel.BackgroundTaskViewModel
import com.mas.autofarm.theme.MaaAnimations
import com.mas.autofarm.utils.i18n.asString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import timber.log.Timber

/**
 * MAS 自研后台任务页：
 * 布局结构沿用 meow（顶部虚拟屏实时预览 + 下方 Tab 内容区 + 底部开始/停止/快捷选项），
 * 视觉为自研深色卡片风格（对齐首页），通用化无游戏写死。
 */
@Composable
fun BackgroundTaskView(
    viewModel: BackgroundTaskViewModel,
    compositionService: MaaCompositionService = koinInject(),
    dispatcher: UnifiedStateDispatcher = koinInject(),
    screenSaverManager: ScreenSaverOverlayManager = koinInject(),
    appWatchdog: AppWatchdog = koinInject(),
    appSettingsManager: AppSettingsManager = koinInject(),
    permissionManager: PermissionManager = koinInject(),
) {
    val coroutineScope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val maaState by compositionService.state.collectAsStateWithLifecycle()
    val runMode by appSettingsManager.runMode.collectAsStateWithLifecycle()
    val permissionState by permissionManager.state.collectAsStateWithLifecycle()
    val markers by viewModel.markers.collectAsStateWithLifecycle()
    val displayResolution by compositionService.displayResolution.collectAsStateWithLifecycle()
    val isChainLoaded by viewModel.chainState.isLoaded.collectAsStateWithLifecycle()
    val watchdogState by appWatchdog.state.collectAsStateWithLifecycle()
    var hasInitialized by rememberSaveable { mutableStateOf(false) }
    if (isChainLoaded) {
        hasInitialized = true
    }
    val isInitialized = hasInitialized
    var showCloseConfirm by remember { mutableStateOf(false) }
    var showMoreActions by remember { mutableStateOf(false) }
    val nodes by viewModel.chainState.chain.collectAsStateWithLifecycle()
    val profiles by viewModel.chainState.profiles.collectAsStateWithLifecycle()
    val profileId by viewModel.chainState.profileId.collectAsStateWithLifecycle()
    val selectedNode = nodes.find { it.id == state.selectedNodeId }
    val clientType = remember(nodes) { viewModel.chainState.clientType }
    val canShowTaskActions = PanelTab.canShowTaskActions(state.current)
    val pagerState = rememberPagerState(
        initialPage = state.current.ordinal, pageCount = { PanelTab.entries.size })

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val newTab = PanelTab.entries[page]
            if (newTab != state.current) {
                viewModel.onTabChange(newTab)
            }
        }
    }
    LaunchedEffect(state.current) {
        if (pagerState.currentPage != state.current.ordinal) {
            pagerState.animateScrollToPage(
                state.current.ordinal, animationSpec = tween(
                    easing = MaaAnimations.springEasing, durationMillis = 250
                )
            )
        }
    }
    val context = LocalContext.current
    val serviceDiedMessage = stringResource(R.string.bg_toast_service_died)
    val appDiedMessage = stringResource(R.string.bg_toast_app_died)
    val displayDriftMessage = stringResource(R.string.bg_toast_display_drift)
    ShizukuReadinessGate()
    LaunchedEffect(Unit) {
        dispatcher.serviceDiedEvent.collect {
            Toast.makeText(context, serviceDiedMessage, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) {
        appWatchdog.appDiedEvent.collect {
            Toast.makeText(context, appDiedMessage, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) {
        appWatchdog.displayDriftEvent.collect {
            Toast.makeText(context, displayDriftMessage, Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.screenshotMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    val shouldHideMoreActions by remember {
        derivedStateOf {
            !canShowTaskActions || showCloseConfirm || state.isFullscreenMonitor || state.dialog != null
        }
    }
    LaunchedEffect(shouldHideMoreActions) {
        if (shouldHideMoreActions) showMoreActions = false
    }
    var isSurfaceAvailable by remember { mutableStateOf(false) }
    var lastSentSurface by remember { mutableStateOf<Surface?>(null) }
    val currentResolution by rememberUpdatedState(displayResolution)
    val previewContent = remember {
        movableContentOf {
            val innerScope = rememberCoroutineScope()
            Box(
                modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.aspectRatio(DefaultDisplayConfig.ASPECT_RATIO)) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.setFormat(PixelFormat.RGBA_8888)
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        isSurfaceAvailable = true
                                        innerScope.launch {
                                            delay(50)
                                            val res = currentResolution
                                            holder.setFixedSize(res.width, res.height)
                                        }
                                    }
                                    override fun surfaceChanged(
                                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                                    ) {
                                        Timber.d("Surface size changed to $width x $height")
                                        val res = currentResolution
                                        if (width == res.width && height == res.height) {
                                            if (lastSentSurface != holder.surface) {
                                                lastSentSurface = holder.surface
                                                viewModel.onSurfaceAvailable(holder.surface)
                                            }
                                        }
                                    }
                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        isSurfaceAvailable = false
                                        lastSentSurface = null
                                        viewModel.onSurfaceDestroyed()
                                    }
                                })
                            }
                        }, modifier = Modifier.fillMaxSize()
                    )
                    if (markers.isNotEmpty()) TouchPreviewOverlay(
                        markers = markers,
                        displayResolution = displayResolution,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 8.dp)
        ) {
            // --- 自研预览卡：实时虚拟屏 ---
            MasPreviewCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f),
                maaState = maaState,
                isSurfaceAvailable = isSurfaceAvailable,
                watchdogState = watchdogState,
                onFullscreen = { viewModel.onToggleFullscreenMonitor() },
                content = previewContent,
            )
            Spacer(modifier = Modifier.height(10.dp))
            // --- 内容区：Tab + Pager + 底部操作栏 ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(7f)
            ) {
                MasTabRow(
                    selectedTab = state.current,
                    onTabSelected = { tab -> viewModel.onTabChange(tab) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isInitialized) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            userScrollEnabled = true,
                            beyondViewportPageCount = 0
                        ) { page ->
                            when (page) {
                                0 -> {
                                    TaskListDetailLayout(
                                        nodes = nodes,
                                        selectedNode = selectedNode,
                                        selectedNodeId = state.selectedNodeId,
                                        isEditMode = state.isEditMode,
                                        isAddingTask = state.isAddingTask,
                                        isProfileMode = state.isProfileMode,
                                        profiles = profiles,
                                        activeProfileId = profileId,
                                        clientType = clientType,
                                        onNodeEnabledChange = viewModel::onNodeEnabledChange,
                                        onNodeSelected = viewModel::onNodeSelected,
                                        onNodeMove = viewModel::onNodeMove,
                                        onToggleEditMode = viewModel::onToggleEditMode,
                                        onToggleAddingTask = viewModel::onToggleAddingTask,
                                        onToggleProfileMode = viewModel::onToggleProfileMode,
                                        onConfigChange = { config ->
                                            val nodeId = selectedNode?.id
                                                ?: return@TaskListDetailLayout
                                            viewModel.onNodeConfigChange(nodeId, config)
                                        },
                                        onAddNode = viewModel::onAddNode,
                                        onImportFlow = viewModel::onImportFlow,
                                        onBindFlow = viewModel::onBindFlow,
                                        onUnbindFlow = viewModel::onUnbindFlow,
                                        onSyncBoundFlows = { coroutineScope.launch { viewModel.syncBoundFlows() } },
                                        onRemoveNode = viewModel::onRemoveNode,
                                        onDuplicateNode = viewModel::onDuplicateNode,
                                        onRenameNode = viewModel::onRenameNode,
                                        onSwitchProfile = viewModel::onSwitchProfile,
                                        onRenameProfile = viewModel::onRenameProfile,
                                        onDuplicateProfile = viewModel::onDuplicateProfile,
                                        onDeleteProfile = viewModel::onDeleteProfile,
                                        onCreateProfile = viewModel::onCreateProfile,
                                        onReorderProfile = viewModel::onReorderProfile,
                                        modifier = Modifier.fillMaxSize(),
                                        wrapDetailInCard = true,
                                    )
                                }
                                1 -> {
                                    com.mas.autofarm.presentation.view.panel.VirtualCoordPicker(modifier = Modifier.fillMaxSize())
                                }
                                2 -> {
                                    val runtimeLogs by viewModel.logs.collectAsStateWithLifecycle()
                                    LogPanel(
                                        logs = runtimeLogs,
                                        onClearLogs = { viewModel.onClearLogs() },
                                    )
                                }
                            }
                        }
                        if (canShowTaskActions) {
                            Spacer(modifier = Modifier.height(8.dp))
                            MasActionBar(
                                maaState = maaState,
                                runMode = runMode,
                                permissionState = permissionState,
                                showMoreActions = showMoreActions,
                                onStart = { viewModel.onStartTasks() },
                                onStop = { viewModel.onStopTasks() },
                                onToggleMore = { showMoreActions = !showMoreActions },
                            )
                        }
                    }
                } else {
                    // 初始化骨架占位
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF3A6EA5),
                        )
                    }
                }
            }
        }
        BackHandler(enabled = showMoreActions) {
            showMoreActions = false
        }
        if (showMoreActions) {
            val isGameMuted by viewModel.isGameMuted.collectAsStateWithLifecycle()
            MasQuickActionsSheet(
                onDismissRequest = { showMoreActions = false },
                isGameMuted = isGameMuted,
                onToggleGameSound = viewModel::onToggleGameSound,
                onScreenOff = viewModel::onScreenOff,
                onShowScreenSaver = { coroutineScope.launch { screenSaverManager.show() } },
                onCaptureScreenshot = viewModel::onCaptureDebugScreenshot,
                onCloseApp = viewModel::onCloseGame,
            )
        }

        // 全屏预览：横屏 + 隐藏系统栏 + 触摸映射
        if (state.isFullscreenMonitor) {
            val activity = context as? Activity
            DisposableEffect(Unit) {
                val window = activity?.window
                val controller = window?.let {
                    WindowCompat.getInsetsController(it, it.decorView)
                }
                controller?.hide(WindowInsetsCompat.Type.systemBars())
                controller?.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                onDispose {
                    controller?.show(WindowInsetsCompat.Type.systemBars())
                }
            }
            DisposableEffect(Unit) {
                val originalOrientation = activity?.requestedOrientation
                onDispose {
                    if (originalOrientation != null) {
                        activity.requestedOrientation = originalOrientation
                    }
                }
            }
            LaunchedEffect(Unit) {
                val current = activity?.resources?.configuration?.orientation
                if (current != Configuration.ORIENTATION_LANDSCAPE) {
                    activity?.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
            BackHandler { viewModel.onToggleFullscreenMonitor() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                viewToVirtualDisplay(
                                    viewX = change.position.x,
                                    viewY = change.position.y,
                                    viewWidth = size.width,
                                    viewHeight = size.height,
                                    bufferWidth = displayResolution.width,
                                    bufferHeight = displayResolution.height
                                ) { vx, vy ->
                                    when (event.type) {
                                        PointerEventType.Press -> viewModel.onTouchDown(vx, vy)
                                        PointerEventType.Move -> {
                                            if (change.pressed) {
                                                viewModel.onTouchMove(vx, vy)
                                            }
                                        }
                                        PointerEventType.Release -> viewModel.onTouchUp(vx, vy)
                                    }
                                }
                                change.consume()
                            }
                        }
                    }, contentAlignment = Alignment.Center
            ) {
                previewContent()
                IconButton(
                    onClick = { viewModel.onToggleFullscreenMonitor() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.task_close_preview_cd),
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        val activeDialog = state.dialog
        val activeDialogCallbacks = when {
            state.dialog != null -> viewModel::onDialogDismiss to viewModel::onDialogConfirm
            else -> null
        }
        activeDialog?.let { dialog ->
            val (onDismiss, onConfirm) = activeDialogCallbacks!!
            val confirmColor = when (dialog.type) {
                PanelDialogType.SUCCESS -> MaterialTheme.colorScheme.primary
                PanelDialogType.WARNING -> MaterialTheme.colorScheme.tertiary
                PanelDialogType.ERROR -> MaterialTheme.colorScheme.error
            }
            AdaptiveTaskPromptDialog(
                visible = true,
                title = dialog.title.asString(),
                message = dialog.message.asString(),
                onDismissRequest = onDismiss,
                onConfirm = onConfirm,
                confirmText = dialog.confirmText.asString().ifBlank {
                    stringResource(R.string.common_confirm)
                },
                dismissText = dialog.dismissText.asString().ifBlank {
                    stringResource(R.string.common_close)
                },
                icon = when (dialog.type) {
                    PanelDialogType.SUCCESS -> Icons.Filled.CheckCircle
                    else -> Icons.Filled.Warning
                },
                iconTint = confirmColor,
                confirmColor = confirmColor,
            )
        }
        if (showCloseConfirm) {
            AdaptiveTaskPromptDialog(
                visible = true,
                title = stringResource(R.string.dialog_close_app_title),
                message = stringResource(R.string.dialog_close_app_message),
                onDismissRequest = { showCloseConfirm = false },
                onConfirm = {
                    showCloseConfirm = false
                    coroutineScope.launch { compositionService.stopVirtualDisplay() }
                },
                confirmText = stringResource(R.string.dialog_close_app_confirm),
                dismissText = stringResource(R.string.common_cancel),
                icon = Icons.Filled.Warning,
                iconTint = MaterialTheme.colorScheme.error,
                confirmColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ==================== 自研组件 ====================

/** 预览卡片：深色圆角卡 + 实时画面 + 看门狗徽章 + 全屏入口。 */
@Composable
private fun MasPreviewCard(
    modifier: Modifier = Modifier,
    maaState: MaaExecutionState,
    isSurfaceAvailable: Boolean,
    watchdogState: AppWatchdog.WatchdogState,
    onFullscreen: () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val maxWidth = maxWidth
        val maxHeight = maxHeight
        val aspectRatio = 16f / 9f
        val widthFromHeight = maxHeight * aspectRatio
        val heightFromWidth = maxWidth / aspectRatio
        val (cardWidth, cardHeight) = if (widthFromHeight <= maxWidth) {
            widthFromHeight to maxHeight
        } else {
            maxWidth to heightFromWidth
        }

        Card(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .clickable(onClick = onFullscreen),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()

                when {
                    maaState != MaaExecutionState.RUNNING -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF2A2A2A).copy(alpha = 0.92f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🖥", fontSize = 30.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "虚拟屏未运行",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    fontSize = 13.sp,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "点击预览区进入全屏监控",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }

                    !isSurfaceAvailable -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF2A2A2A).copy(alpha = 0.92f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF3A6EA5),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "正在连接虚拟屏…",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }

                // 状态徽章：执行状态优先（FlowEngine 运行中），看门狗兜底
                val (dotColor, label) = when {
                    maaState == MaaExecutionState.RUNNING -> Color(0xFF4CAF50) to "运行中"
                    maaState == MaaExecutionState.STARTING || maaState == MaaExecutionState.STOPPING -> Color(0xFFFF9800) to "切换中"
                    watchdogState == AppWatchdog.WatchdogState.APP_DIED -> Color(0xFFF44336) to "已停止"
                    watchdogState == AppWatchdog.WatchdogState.WATCHING -> Color(0xFF4CAF50) to "运行中"
                    else -> Color(0xFF9E9E9E) to "空闲"
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                    )
                }
                // 全屏入口
                IconButton(
                    onClick = onFullscreen,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(30.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Fullscreen,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** 插件占位（接口预留）。 */
@Composable
private fun MasPluginsPlaceholder(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🧩", fontSize = 34.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "暂无插件",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "插件接口预留，后续版本开放",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                fontSize = 12.sp,
            )
        }
    }
}

/** 自研 Tab 行：深色胶囊切换。 */
@Composable
private fun MasTabRow(
    selectedTab: PanelTab,
    onTabSelected: (PanelTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PanelTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) Color(0xFF3A6EA5) else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTabSelected(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

/** 自研底部操作栏：开始/停止 + 快捷选项。 */
@Composable
private fun MasActionBar(
    maaState: MaaExecutionState,
    runMode: RunMode,
    permissionState: PermissionState,
    showMoreActions: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleMore: () -> Unit,
) {
    val context = LocalContext.current
    val inputFocusManager = LocalInputFocusManager.current
    val foregroundBlocked = runMode == RunMode.FOREGROUND
    val backendBlocked =
        !permissionState.isStartupBackendAvailable(permissionState.startupBackend)
    val startBlocked = foregroundBlocked || backendBlocked
    val switchBackgroundModeMessage = stringResource(R.string.navigation_toast_switch_background_mode)
    val backendUnavailableMessage = stringResource(
        R.string.home_toast_backend_unavailable,
        permissionState.startupBackend.display,
    )
    val taskRunning = maaState == MaaExecutionState.RUNNING || maaState == MaaExecutionState.STOPPING
    val canStart = maaState != MaaExecutionState.RUNNING &&
        maaState != MaaExecutionState.STARTING &&
        maaState != MaaExecutionState.STOPPING

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 开始任务（常显；任务期间变暗不可点）
        MasActionButton(
            text = stringResource(R.string.task_btn_start),
            color = Color(0xFF4CAF50),
            enabled = canStart,
            loading = maaState == MaaExecutionState.STARTING,
            dimmed = startBlocked || taskRunning,
            onClick = {
                inputFocusManager.clear()
                Toast.makeText(context, "任务启动中…", Toast.LENGTH_SHORT).show()
                if (foregroundBlocked) {
                    Toast.makeText(context, switchBackgroundModeMessage, Toast.LENGTH_SHORT).show()
                    return@MasActionButton
                }
                if (backendBlocked) {
                    Toast.makeText(context, backendUnavailableMessage, Toast.LENGTH_SHORT).show()
                    return@MasActionButton
                }
                onStart()
            },
            modifier = Modifier.weight(1f),
        )
        // 停止任务（仅任务期间亮起可点，空闲时变暗）
        MasActionButton(
            text = stringResource(R.string.task_btn_stop),
            color = Color(0xFFE53935),
            enabled = maaState == MaaExecutionState.RUNNING,
            loading = maaState == MaaExecutionState.STOPPING,
            dimmed = !taskRunning,
            onClick = {
                inputFocusManager.clear()
                Toast.makeText(context, "正在停止…", Toast.LENGTH_SHORT).show()
                onStop()
            },
            modifier = Modifier.weight(1f),
        )
        // 快捷选项
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (showMoreActions) Color(0xFF3A6EA5).copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onToggleMore() }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.task_btn_quick_options),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 自研操作按钮：大圆角彩色卡片按钮。 */
@Composable
private fun MasActionButton(
    text: String,
    color: Color,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    dimmed -> Color(0xFF3A3A3A)
                    enabled -> color.copy(alpha = 0.3f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (enabled) onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = color,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = when {
                    dimmed -> Color.White.copy(alpha = 0.4f)
                    enabled -> color
                    else -> Color.White.copy(alpha = 0.4f)
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** 自研快捷选项面板：底部弹出深色卡片。 */
@Composable
private fun MasQuickActionsSheet(
    onDismissRequest: () -> Unit,
    isGameMuted: Boolean,
    onToggleGameSound: () -> Unit,
    onScreenOff: () -> Unit,
    onShowScreenSaver: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onCloseApp: () -> Unit,
    appSettingsManager: AppSettingsManager = koinInject(),
) {
    val coroutineScope = rememberCoroutineScope()
    val muteOnGameLaunch by appSettingsManager.muteOnGameLaunch.collectAsStateWithLifecycle()
    val closeAppOnTaskEnd by appSettingsManager.closeAppOnTaskEnd.collectAsStateWithLifecycle()
    val useHardwareScreenOff by appSettingsManager.useHardwareScreenOff.collectAsStateWithLifecycle()
    val showTouchPreview by appSettingsManager.showTouchPreview.collectAsStateWithLifecycle()
    val debugMode by appSettingsManager.debugMode.collectAsStateWithLifecycle()
    var showHardwareScreenOffConfirm by remember { mutableStateOf(false) }
    var showCloseAppConfirm by remember { mutableStateOf(false) }
    val overlayInteractionSource = remember { MutableInteractionSource() }
    val cardInteractionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = overlayInteractionSource,
                indication = null,
                onClick = onDismissRequest,
            )
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .clickable(
                    interactionSource = cardInteractionSource,
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // 标题
                Text(
                    text = stringResource(R.string.bg_actions_title),
                    color = Color(0xFF64B5F6),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                // 快捷操作
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MasActionTile(
                        icon = Icons.Filled.PowerSettingsNew,
                        label = stringResource(R.string.bg_action_screen_off),
                        color = Color(0xFF3A6EA5),
                        onClick = {
                            if (useHardwareScreenOff) onScreenOff() else onShowScreenSaver()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    MasActionTile(
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        label = stringResource(R.string.bg_action_close_game),
                        color = Color(0xFFE53935),
                        onClick = { showCloseAppConfirm = true },
                        modifier = Modifier.weight(1f),
                    )
                    MasActionTile(
                        icon = if (isGameMuted) Icons.AutoMirrored.Filled.VolumeOff
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        label = if (isGameMuted) stringResource(R.string.bg_action_game_muted)
                        else stringResource(R.string.bg_action_mute_game),
                        color = if (isGameMuted) Color(0xFFFFB300) else Color(0xFF4CAF50),
                        onClick = onToggleGameSound,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 调试截图
                if (debugMode) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MasActionTile(
                            icon = Icons.Filled.Screenshot,
                            label = stringResource(R.string.bg_action_screenshot),
                            color = Color(0xFF3A6EA5),
                            onClick = onCaptureScreenshot,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.bg_auto_settings_title),
                    color = Color(0xFF64B5F6),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                MasSettingSwitchRow(
                    icon = Icons.Filled.NotificationsPaused,
                    label = stringResource(R.string.bg_auto_mute_on_launch),
                    checked = muteOnGameLaunch,
                    onCheckedChange = {
                        coroutineScope.launch { appSettingsManager.setMuteOnGameLaunch(it) }
                    },
                )
                MasSettingSwitchRow(
                    icon = Icons.Filled.Cancel,
                    label = stringResource(R.string.bg_auto_close_on_end),
                    checked = closeAppOnTaskEnd,
                    onCheckedChange = {
                        coroutineScope.launch { appSettingsManager.setCloseAppOnTaskEnd(it) }
                    },
                )
                MasSettingSwitchRow(
                    icon = Icons.Filled.StayCurrentPortrait,
                    label = stringResource(R.string.bg_auto_hardware_screen_off),
                    checked = useHardwareScreenOff,
                    onCheckedChange = { checked ->
                        if (checked) {
                            showHardwareScreenOffConfirm = true
                        } else {
                            coroutineScope.launch {
                                appSettingsManager.setUseHardwareScreenOff(false)
                            }
                        }
                    },
                )
                MasSettingSwitchRow(
                    icon = Icons.Filled.TouchApp,
                    label = stringResource(R.string.bg_auto_show_touch_preview),
                    checked = showTouchPreview,
                    onCheckedChange = {
                        coroutineScope.launch { appSettingsManager.setShowTouchPreview(it) }
                    },
                )
            }
        }
    }
    if (showCloseAppConfirm) {
        AdaptiveTaskPromptDialog(
            visible = true,
            title = "关闭应用",
            message = "应用正在运行，强行关闭会导致流程终止，确定关闭？",
            onDismissRequest = { showCloseAppConfirm = false },
            onConfirm = {
                showCloseAppConfirm = false
                onCloseApp()
            },
            confirmText = "关闭",
            dismissText = "取消",
            icon = Icons.Filled.Warning,
            iconTint = MaterialTheme.colorScheme.error,
            confirmColor = MaterialTheme.colorScheme.error,
        )
    }
    if (showHardwareScreenOffConfirm) {
        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.dialog_hardware_screen_off_title),
            message = stringResource(R.string.dialog_hardware_screen_off_message),
            onDismissRequest = { showHardwareScreenOffConfirm = false },
            onConfirm = {
                showHardwareScreenOffConfirm = false
                coroutineScope.launch { appSettingsManager.setUseHardwareScreenOff(true) }
            },
            confirmText = stringResource(R.string.common_confirm),
            dismissText = stringResource(R.string.common_cancel),
            icon = Icons.Filled.PowerSettingsNew,
            iconTint = MaterialTheme.colorScheme.primary,
            confirmColor = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 自研快捷操作块。 */
@Composable
private fun MasActionTile(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = color,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 自研设置开关行。 */
@Composable
private fun MasSettingSwitchRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = Color.White.copy(alpha = 0.45f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private inline fun viewToVirtualDisplay(
    viewX: Float,
    viewY: Float,
    viewWidth: Int,
    viewHeight: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    block: (vx: Int, vy: Int) -> Unit,
) {
    val bufferW = bufferWidth.toFloat()
    val bufferH = bufferHeight.toFloat()
    val scale = minOf(viewWidth / bufferW, viewHeight / bufferH)
    val offsetX = (viewWidth - bufferW * scale) / 2f
    val offsetY = (viewHeight - bufferH * scale) / 2f
    val vx = ((viewX - offsetX) / scale).toInt()
    val vy = ((viewY - offsetY) / scale).toInt()
    if (vx < 0 || vx >= bufferW.toInt() || vy < 0 || vy >= bufferH.toInt()) return
    block(vx, vy)
}