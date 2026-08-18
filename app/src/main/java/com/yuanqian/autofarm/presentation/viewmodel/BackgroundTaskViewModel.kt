package com.yuanqian.autofarm.presentation.viewmodel

import android.content.Context
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuanqian.autofarm.R
import com.yuanqian.autofarm.RemoteService
import com.yuanqian.autofarm.data.config.MaaPathConfig
import com.yuanqian.autofarm.data.model.CustomFlowConfig
import com.yuanqian.autofarm.data.model.LogItem
import com.yuanqian.autofarm.data.model.TaskChainNode
import com.yuanqian.autofarm.data.model.TaskParamProvider
import com.yuanqian.autofarm.data.model.TaskTypeInfo
import com.yuanqian.autofarm.data.preferences.AppSettingsManager
import com.yuanqian.autofarm.data.preferences.TaskChainState
import com.yuanqian.autofarm.domain.launch.LaunchPipeline
import com.yuanqian.autofarm.domain.launch.LaunchRequest
import com.yuanqian.autofarm.domain.launch.LaunchSession
import com.yuanqian.autofarm.domain.launch.LaunchUserEvent
import com.yuanqian.autofarm.domain.launch.toCountdownState
import com.yuanqian.autofarm.domain.service.GameMuteCoordinator
import com.yuanqian.autofarm.domain.service.MaaCompositionService
import com.yuanqian.autofarm.domain.service.MaaSessionLogger
import com.yuanqian.autofarm.domain.service.TaskEndRegistry
import com.yuanqian.autofarm.domain.usecase.PrepareTaskStartUseCase
import com.yuanqian.autofarm.domain.usecase.TaskStartContext
import com.yuanqian.autofarm.domain.usecase.TaskStartDecision
import com.yuanqian.autofarm.domain.usecase.TaskStartMode
import com.yuanqian.autofarm.manager.RemoteServiceManager
import com.yuanqian.autofarm.presentation.state.BackgroundTaskState
import com.yuanqian.autofarm.presentation.state.PreviewTouchMarker
import com.yuanqian.autofarm.presentation.state.UiEffect
import com.yuanqian.autofarm.presentation.view.panel.PanelDialogConfirmAction
import com.yuanqian.autofarm.presentation.view.panel.PanelDialogUiState
import com.yuanqian.autofarm.presentation.view.panel.PanelTab
import com.yuanqian.autofarm.schedule.model.CountdownState
import com.yuanqian.autofarm.presentation.view.workshop.summary
import com.yuanqian.autofarm.utils.i18n.UiText
import com.yuanqian.autofarm.utils.i18n.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class BackgroundTaskViewModel(
    val chainState: TaskChainState,
    private val prepareTaskStart: PrepareTaskStartUseCase,
    private val compositionService: MaaCompositionService,
    private val sessionLogger: MaaSessionLogger,
    private val appSettingsManager: AppSettingsManager,
    private val pathConfig: MaaPathConfig,
    private val gameMuteCoordinator: GameMuteCoordinator,
    private val launchPipeline: LaunchPipeline,
    private val taskEndRegistry: TaskEndRegistry,
    private val application: Context,
) : ViewModel() {

    val launchSession: StateFlow<LaunchSession> = launchPipeline.session
    val launchEffects = launchPipeline.effects
    val countdownState: StateFlow<CountdownState> = launchPipeline.session
        .map { it.toCountdownState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CountdownState.Idle)

    /**
     * 导航用：仅 [LaunchSession.InFlight.presentUi] 为 true（后台 Dialog 倒计时）时置位
     * 前台无倒计时不导航，避免强行拉回主 Tab
     */
    val pendingNavigateRequestId: StateFlow<String?> = launchPipeline.session
        .map { session ->
            when (session) {
                is LaunchSession.InFlight -> {
                    if (!session.presentUi) null
                    else when (session.phase) {
                        is LaunchSession.Phase.Counting,
                        LaunchSession.Phase.Preparing,
                        LaunchSession.Phase.Starting -> session.request.requestId
                        else -> null
                    }
                }
                LaunchSession.Idle -> null
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow(BackgroundTaskState())
    val state: StateFlow<BackgroundTaskState> = _state.asStateFlow()
    val logs: StateFlow<List<LogItem>> = sessionLogger.logs

    private val surfaceRef = AtomicReference<Surface>()

    val isGameMuted: StateFlow<Boolean> = gameMuteCoordinator.isMuted

    // 调试截图结果（已本地化的提示文案），供 UI 以 Toast 展示
    private val _screenshotMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val screenshotMessage: SharedFlow<String> = _screenshotMessage.asSharedFlow()

    private val _effects = Channel<UiEffect>(Channel.BUFFERED)
    val effects: Flow<UiEffect> = _effects.receiveAsFlow()

    private val touchPreviewController = TouchPreviewController(viewModelScope)
    val markers: StateFlow<List<PreviewTouchMarker>> = touchPreviewController.markers
    // --- 流程暂停 & 预览选点校准 ---
    private val _flowPaused = MutableStateFlow(false)
    val flowPaused: StateFlow<Boolean> = _flowPaused.asStateFlow()
    private val _isFlowRunning = MutableStateFlow(false)
    val isFlowRunning: StateFlow<Boolean> = _isFlowRunning.asStateFlow()
    private val _previewPick = MutableStateFlow<androidx.compose.ui.geometry.Offset?>(null)
    val previewPick: StateFlow<androidx.compose.ui.geometry.Offset?> = _previewPick.asStateFlow()

    fun onToggleFlowPause() {
        val engine = runningFlowEngine ?: return
        if (engine.isPaused) {
            engine.resume()
            _flowPaused.value = false
        } else {
            engine.pause()
            _flowPaused.value = true
        }
    }

    /** 暂停选点：记录虚拟屏坐标（x,y 为虚拟屏分辨率坐标） */
    fun onPreviewPick(x: Int, y: Int) {
        _previewPick.value = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat())
    }

    fun clearPreviewPick() {
        _previewPick.value = null
    }
    private var pendingStart: PendingStart? = null
    /** 当前运行的工坊流程引擎（供停止按钮中断） */
    private var runningFlowEngine: com.yuanqian.autofarm.presentation.view.workshop.FlowEngine? = null
    /** 工坊流程启动的应用包名（用于停止/关闭游戏/禁音） */
    private var currentFlowAppPackage: String? = null

    private data class PendingStart(
        val context: TaskStartContext,
    )

    init {
        Timber.i("BackgroundTaskViewModel inited")
        observeServiceState()
        observeTaskEnd()
        observeTouchPreviewToggle()
        observeDefaultTaskSelection()
    }

    /**
     * 首次进入 / 选中失效时默认打开任务链第一项，避免右侧一直停在空占位。
     * 新增任务、配置管理模式下不自动改写选中。
     */
    private fun observeDefaultTaskSelection() {
        viewModelScope.launch {
            combine(chainState.chain, _state) { nodes, ui ->
                resolveTaskPanelSelectedNodeId(
                    nodes = nodes,
                    selectedNodeId = ui.selectedNodeId,
                    isAddingTask = ui.isAddingTask,
                    isProfileMode = ui.isProfileMode,
                )
            }
                .distinctUntilChanged()
                .collect { resolved ->
                    if (_state.value.selectedNodeId != resolved) {
                        _state.update { it.copy(selectedNodeId = resolved) }
                    }
                }
        }
    }

    private fun observeTouchPreviewToggle() {
        viewModelScope.launch {
            appSettingsManager.showTouchPreview.collect { enabled ->
                touchPreviewController.onTouchCallbackChange(enabled)
            }
        }
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            RemoteServiceManager.state
                .drop(1)
                .collect { state ->
                    when (state) {
                        // 服务重连
                        is RemoteServiceManager.ServiceState.Connected -> {
                            onServiceReconnected(state.service)
                        }

                        is RemoteServiceManager.ServiceState.Error -> {
                            touchPreviewController.onClear()
                        }

                        else -> Unit
                    }
                }
        }
    }

    fun onServiceReconnected(srv: RemoteService) {
        if (surfaceRef.get() != null) {
            onMonitorSurfaceChanged(srv)
        }
        val enabled = appSettingsManager.showTouchPreview.value
        touchPreviewController.onTouchCallbackChange(enabled)
    }

    private fun observeTaskEnd() {
        viewModelScope.launch {
            taskEndRegistry.taskEnded.collect { reason ->
                // 仅自然结束关游戏
                if (reason == TaskEndRegistry.Reason.NATURAL
                    && appSettingsManager.closeAppOnTaskEnd.value
                ) {
                    Timber.i("Task ended naturally, auto closing app")
                    _effects.send(UiEffect.toast(R.string.bg_toast_auto_closed_on_end))
                    compositionService.stopVirtualDisplay()
                }
            }
        }
    }


    // ==================== Scheduled Launch ====================

    fun onExternalLaunch(request: LaunchRequest) {
        launchPipeline.execute(request)
    }

    fun onScheduledCountdownCancel() {
        launchPipeline.submit(LaunchUserEvent.Cancel)
    }

    fun onScheduledStartNow() {
        launchPipeline.submit(LaunchUserEvent.StartNow)
    }

    fun onNavigateForScheduledLaunch() {
        _state.update {
            it.copy(
                current = PanelTab.TASKS,
                selectedNodeId = null,
                isAddingTask = false,
                isEditMode = false,
                isProfileMode = false,
            )
        }
    }

    // ==================== Surface ====================

    private fun onMonitorSurfaceChanged(
        service: RemoteService? = RemoteServiceManager.getInstanceOrNull()
    ) {
        val remote = service ?: return
        val surface = surfaceRef.get()
        Timber.d("onMonitorSurfaceChanged: surface=%s", surface)
        runCatching {
            remote.setMonitorSurface(surface)
        }.onFailure {
            Timber.w(it, "setMonitorSurface failed")
        }
    }

    fun onSurfaceAvailable(surface: Surface) {
        surfaceRef.set(surface)
        onMonitorSurfaceChanged()
    }

    fun onSurfaceDestroyed() {
        val surface = surfaceRef.getAndSet(null)
        onMonitorSurfaceChanged()
        surface?.release()
    }

    // ==================== Touch Input ====================

    fun onTouchDown(x: Int, y: Int) {
        runCatching {
            RemoteServiceManager.getInstanceOrNull()?.touchDown(x, y)
        }.onFailure {
            Timber.e(it, "touchDown failed at ($x, $y)")
        }
    }

    fun onTouchMove(x: Int, y: Int) {
        runCatching {
            RemoteServiceManager.getInstanceOrNull()?.touchMove(x, y)
        }.onFailure {
            Timber.e(it, "touchMove failed at ($x, $y)")
        }
    }

    fun onTouchUp(x: Int, y: Int) {
        runCatching {
            RemoteServiceManager.getInstanceOrNull()?.touchUp(x, y)
        }.onFailure {
            Timber.e(it, "touchUp failed at ($x, $y)")
        }
    }

    fun onScreenOff() {
        // 硬件熄屏：仅下发一次关闭物理屏幕的指令，无状态、幂等（再点必发，不会卡死）。
        // 启用该功能时 MainActivity 始终持有 FLAG_KEEP_SCREEN_ON 保持系统唤醒、不锁屏；
        // 屏幕恢复由系统在用户唤醒时处理，会话结束/服务销毁时由 PowerController 的 flag 兜底。
        val service = RemoteServiceManager.getInstanceOrNull()
        if (service == null) {
            Timber.w("onScreenOff skipped: remote service unavailable")
            return
        }
        runCatching { service.setDisplayPower(false) }
            .onFailure { Timber.e(it, "onScreenOff failed") }
    }

    // ==================== Task Chain ====================

    fun onNodeEnabledChange(nodeId: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { chainState.setNodeEnabled(nodeId, enabled) }
                .onFailure { e ->
                    Timber.e(e, "Failed to update node enabled: ${e.message}")
                }
        }
    }

    fun onNodeMove(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            runCatching { chainState.reorderNodes(fromIndex, toIndex) }
                .onFailure { e ->
                    Timber.e(e, "Failed to reorder nodes: ${e.message}")
                }
        }
    }

    fun onNodeSelected(nodeId: String) {
        _state.update { it.copy(selectedNodeId = nodeId, isAddingTask = false) }
    }

    fun onToggleEditMode() {
        _state.update {
            it.copy(
                isEditMode = !it.isEditMode,
                isAddingTask = false,
                isProfileMode = false
            )
        }
        Timber.d("Edit mode toggled: %s", _state.value.isEditMode)
    }

    fun onToggleProfileMode() {
        _state.update {
            it.copy(
                isProfileMode = !it.isProfileMode,
                isEditMode = false,
                isAddingTask = false
            )
        }
        Timber.d("Profile mode toggled: %s", _state.value.isProfileMode)
    }

    fun onSwitchProfile(profileId: String) {
        viewModelScope.launch {
            chainState.switchProfile(profileId)
            _state.update { it.copy(selectedNodeId = null) }
        }
    }

    fun onCreateProfile() {
        viewModelScope.launch {
            chainState.createProfile()
            _state.update { it.copy(selectedNodeId = null) }
        }
    }

    fun onDeleteProfile(profileId: String) {
        viewModelScope.launch {
            chainState.removeProfile(profileId)
            _state.update { it.copy(selectedNodeId = null) }
        }
    }

    fun onRenameProfile(profileId: String, name: String) {
        viewModelScope.launch {
            chainState.renameProfile(profileId, name)
        }
    }

    fun onDuplicateProfile(profileId: String) {
        viewModelScope.launch {
            chainState.duplicateProfile(profileId)
        }
    }

    fun onReorderProfile(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            runCatching { chainState.reorderProfiles(fromIndex, toIndex) }
                .onFailure { e -> Timber.e(e, "Failed to reorder profile: ${e.message}") }
        }
    }

    fun onToggleAddingTask() {
        _state.update { it.copy(isAddingTask = !it.isAddingTask, selectedNodeId = null) }
        Timber.d("Adding task mode toggled: %s", _state.value.isAddingTask)
    }

    fun onAddNode(typeInfo: TaskTypeInfo) {
        viewModelScope.launch {
            val nodeId = chainState.addNode(typeInfo)
            _state.update { it.copy(isAddingTask = false, selectedNodeId = nodeId) }
        }
    }

    /**
     * 编辑配置里"引用工坊流程"：新建一个以流程名命名的配置，并把流程的每一个节点
     * 镜像为任务链中的一个任务项。任务项配置均为 CustomFlowConfig(localFlowName=流程名)。
     */
    fun onImportFlow(flowName: String) {
        viewModelScope.launch {
            runCatching {
                val dir = File(application.filesDir, "workshop/$flowName")
                val project = kotlinx.serialization.json.Json.decodeFromString(
                    com.yuanqian.autofarm.presentation.view.workshop.FlowProject.serializer(),
                    File(dir, "project.json").readText(),
                )
                val nodes = project.nodes.map { flowNode ->
                    TaskChainNode(
                        id = java.util.UUID.randomUUID().toString(),
                        name = flowNode.name.ifBlank { flowNodeKindLabel(flowNode.kind) },
                        enabled = true,
                        config = CustomFlowConfig(
                            localFlowName = flowName,
                            flowKind = flowNode.kind.name,
                            detail = flowNode.summary(),
                        ),
                    )
                }
                // 新建以流程名命名的配置，并把流程节点导入该配置（不动当前配置）
                chainState.createProfile(flowName)
                chainState.appendNodes(nodes)
                if (nodes.isNotEmpty()) {
                    _state.update { it.copy(selectedNodeId = nodes.first().id) }
                }
                _effects.send(UiEffect.toast("已创建配置「$flowName」并引用 ${nodes.size} 个节点"))
            }.onFailure { e ->
                Timber.e(e, "onImportFlow failed: %s", e.message)
                _effects.send(UiEffect.toast("引用流程失败：${e.message}"))
            }
        }
    }

    /**
     * 配置绑定流程：把配置的链替换为流程节点镜像，并记录绑定；后续流程更新自动同步。
     */
    fun onBindFlow(profileId: String, flowName: String) {
        viewModelScope.launch {
            runCatching {
                val dir = File(application.filesDir, "workshop/$flowName")
                val projFile = File(dir, "project.json")
                if (!projFile.exists()) error("流程不存在：$flowName")
                val project = kotlinx.serialization.json.Json.decodeFromString(
                    com.yuanqian.autofarm.presentation.view.workshop.FlowProject.serializer(),
                    projFile.readText(),
                )
                val nodes = project.nodes.map { flowNode ->
                    TaskChainNode(
                        id = java.util.UUID.randomUUID().toString(),
                        name = flowNode.name.ifBlank { flowNodeKindLabel(flowNode.kind) },
                        enabled = true,
                        config = CustomFlowConfig(
                            localFlowName = flowName,
                            flowKind = flowNode.kind.name,
                            detail = flowNode.summary(),
                        ),
                    )
                }
                chainState.bindFlowToProfile(profileId, flowName, nodes, projFile.lastModified())
                _effects.send(UiEffect.toast("配置已绑定流程「$flowName」（${nodes.size} 个节点），流程更新将自动同步"))
            }.onFailure { e ->
                Timber.e(e, "onBindFlow failed: %s", e.message)
                _effects.send(UiEffect.toast("绑定流程失败：${e.message}"))
            }
        }
    }

    /** 解除配置的流程绑定（链保留）。 */
    fun onUnbindFlow(profileId: String) {
        viewModelScope.launch {
            chainState.unbindFlowFromProfile(profileId)
            _effects.send(UiEffect.toast("已解除流程绑定"))
        }
    }

    /** 同步所有绑定了流程的配置：流程文件更新过则自动重建节点镜像。返回同步的配置数。 */
    suspend fun syncBoundFlows(): Int {
        var synced = 0
        val profiles = chainState.profiles.value
        for (profile in profiles) {
            val flowName = profile.boundFlowName ?: continue
            runCatching {
                val projFile = File(File(application.filesDir, "workshop/$flowName"), "project.json")
                if (!projFile.exists()) return@runCatching
                val mtime = projFile.lastModified()
                if (mtime == profile.boundFlowSyncedAt) return@runCatching
                val project = kotlinx.serialization.json.Json.decodeFromString(
                    com.yuanqian.autofarm.presentation.view.workshop.FlowProject.serializer(),
                    projFile.readText(),
                )
                val nodes = project.nodes.map { flowNode ->
                    TaskChainNode(
                        id = java.util.UUID.randomUUID().toString(),
                        name = flowNode.name.ifBlank { flowNodeKindLabel(flowNode.kind) },
                        enabled = true,
                        config = CustomFlowConfig(
                            localFlowName = flowName,
                            flowKind = flowNode.kind.name,
                            detail = flowNode.summary(),
                        ),
                    )
                }
                chainState.updateProfileChain(profile.id, nodes, mtime)
                synced++
                Timber.i("Auto-synced bound flow %s → profile %s", flowName, profile.name)
            }.onFailure { e ->
                Timber.e(e, "syncBoundFlows failed for %s: %s", flowName, e.message)
            }
        }
        return synced
    }

    private fun flowNodeKindLabel(kind: com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind): String =
        when (kind) {
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.INFO -> "流程信息"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.TIME -> "等待"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.WAIT -> "等待"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.IMAGE -> "图像识别"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.ACTION -> "动作"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.TAP -> "点击"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.SWIPE -> "滑动"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.BACK -> "返回"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.INPUT -> "输入"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.LOOP -> "循环"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.LOOP_START -> "循环起点"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.APP_STATE -> "应用状态"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.CONJUNCTION -> "合取(全部满足)"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.DISJUNCTION -> "析取(任一满足)"
            com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.LOOP_END -> "循环终点"
        }

    fun onRemoveNode(nodeId: String) {
        viewModelScope.launch {
            chainState.removeNode(nodeId)
            if (_state.value.selectedNodeId == nodeId) {
                _state.update { it.copy(selectedNodeId = null) }
            }
        }
    }

    fun onDuplicateNode(nodeId: String) {
        viewModelScope.launch {
            val newId = chainState.duplicateNode(nodeId)
            if (newId.isNotEmpty()) {
                _state.update { it.copy(selectedNodeId = newId) }
            }
        }
    }

    fun onRenameNode(nodeId: String, newName: String) {
        viewModelScope.launch {
            chainState.renameNode(nodeId, newName)
        }
    }

    fun onNodeConfigChange(nodeId: String, config: TaskParamProvider) {
        viewModelScope.launch {
            chainState.updateNodeConfig(nodeId, config)
        }
    }

    // ==================== UI State ====================

    fun onToggleFullscreenMonitor() {
        _state.update { it.copy(isFullscreenMonitor = !it.isFullscreenMonitor) }
    }

    fun onTabChange(tab: PanelTab) {
        _state.update { it.copy(current = tab) }
    }

    // ==================== Task Execution ====================

    fun onStartTasks() {
        launchManualStart(TaskStartContext(mode = TaskStartMode.MANUAL))
    }

    private fun launchManualStart(context: TaskStartContext) {
        viewModelScope.launch {
            val message = startTasksInternal(context = context)
            if (message != null && state.value.dialog == null) {
                showStartFailedDialog(message)
            }
        }
    }

    /** 手动启动（定时走 [LaunchPipeline] + [StartTaskChainUseCase]）。 */
    /** 用 FlowEngine 运行本地工坊流程（返回 null=成功，非 null=失败消息） */
    private suspend fun runLocalFlowInVm(name: String): UiText? {
        val dir = File(application.filesDir, "workshop/$name")
        val projFile = File(dir, "project.json")
        if (!projFile.exists()) {
            return com.yuanqian.autofarm.utils.i18n.uiTextDynamic("本地流程不存在：$name")
        }
        return runCatching {
            val project = kotlinx.serialization.json.Json.decodeFromString(
                com.yuanqian.autofarm.presentation.view.workshop.FlowProject.serializer(), projFile.readText()
            )
            val templates = com.yuanqian.autofarm.presentation.view.workshop.TemplateStore.listAll(application)
            val remote = when (val st = com.yuanqian.autofarm.manager.RemoteServiceManager.state.value) {
                is com.yuanqian.autofarm.manager.RemoteServiceManager.ServiceState.Connected -> st.service
                else -> null
            }
            if (remote == null) {
                return com.yuanqian.autofarm.utils.i18n.uiTextDynamic("服务未连接，无法运行本地流程")
            }
            val engine = com.yuanqian.autofarm.presentation.view.workshop.FlowEngine(
                context = application,
                remote = remote,
                onLog = { msg -> sessionLogger.append(msg, com.yuanqian.autofarm.data.model.LogLevel.INFO) },
                onNode = { name -> sessionLogger.append("-> " + name, com.yuanqian.autofarm.data.model.LogLevel.INFO) },
            )
            // 状态上报：运行期间保持 RUNNING（预览/状态灯依赖）
            compositionService.reportRunState(com.yuanqian.autofarm.domain.state.MaaExecutionState.STARTING)
            runningFlowEngine = engine
            com.yuanqian.autofarm.presentation.state.FlowRuntimeHolder.onEngineStart(engine)
            _isFlowRunning.value = true
            _flowPaused.value = false
            _previewPick.value = null
            // 记录流程信息节点要启动的应用（供停止/静音/关游戏使用）
            currentFlowAppPackage = project.nodes
                .firstOrNull { it.kind == com.yuanqian.autofarm.presentation.view.workshop.FlowNodeKind.INFO && it.launchApp && it.appPackage.isNotBlank() }
                ?.appPackage
            // 开启时静音（工坊流程：按流程指定包名静音）
            val flowMuteRequested = appSettingsManager.muteOnGameLaunch.value && !currentFlowAppPackage.isNullOrBlank()
            if (flowMuteRequested) {
                runCatching { gameMuteCoordinator.mutePackage(currentFlowAppPackage!!, true) }
            }
            compositionService.reportRunState(com.yuanqian.autofarm.domain.state.MaaExecutionState.RUNNING)
            // 开会话：让历史日志页能看到流程记录（与 runLocalFlow 保持一致）
            sessionLogger.startSession(listOf("flow:$name"))
            try {
                engine.run(project, templates)
            } finally {
                sessionLogger.endSessionAndWait(
                    if (engine.state == com.yuanqian.autofarm.presentation.view.workshop.FlowEngine.EngineState.ERROR) "FLOW_ERROR" else "COMPLETED"
                )
                // 静音全局化：流程结束不自动恢复声音（虚拟屏可能仍在运行），由用户手动关闭
            }
            runningFlowEngine = null
            currentFlowAppPackage = null
            com.yuanqian.autofarm.presentation.state.FlowRuntimeHolder.onEngineEnd()
            _isFlowRunning.value = false
            _flowPaused.value = false
            if (engine.state == com.yuanqian.autofarm.presentation.view.workshop.FlowEngine.EngineState.ERROR) {
                compositionService.reportRunState(com.yuanqian.autofarm.domain.state.MaaExecutionState.ERROR)
                com.yuanqian.autofarm.utils.i18n.uiTextDynamic("本地流程执行出错")
            } else {
                compositionService.reportRunState(com.yuanqian.autofarm.domain.state.MaaExecutionState.IDLE)
                null
            }
        }.getOrElse { e ->
            com.yuanqian.autofarm.utils.i18n.uiTextDynamic("本地流程执行异常：${e.message}")
        }
    }

    private suspend fun startTasksInternal(
        context: TaskStartContext,
    ): UiText? {
        // 本地工坊流程优先：任务链里有"自定义流程(本地)"时，直接用 FlowEngine 运行
        // （流程信息节点负责启动应用到虚拟屏，即"开始唤醒"），不走 MaaCore。
        val localFlowName = chainState.chain.value
            .mapNotNull { it.config as? com.yuanqian.autofarm.data.model.CustomFlowConfig }
            .firstOrNull { it.localFlowName.isNotBlank() }
            ?.localFlowName
        if (localFlowName != null) {
            val err = runLocalFlowInVm(localFlowName)
            return err
        }
        val plan = when (
            val decision = prepareTaskStart(
                chain = chainState.chain.value,
                context = context,
            )
        ) {
            is TaskStartDecision.Ready -> {
                pendingStart = null
                decision.plan
            }

            is TaskStartDecision.Blocked -> {
                pendingStart = null
                val message = application.resolveTaskStartDecisionMessage(decision)
                Timber.w("Validation failed: %s", message.resolve(application))
                showDialog(application.createStartBlockedDialog(message))
                return message
            }

            is TaskStartDecision.RequiresConfirmation -> {
                pendingStart = PendingStart(context.acknowledged(decision.acknowledgement))
                val message = application.resolveTaskStartDecisionMessage(decision)
                showDialog(application.createStartWarningDialog(message))
                return message
            }
        }

        // 先静音后拉起游戏：appops 状态持久，提前设置零成本，消除游戏启动初期的漏音空窗
        val muteRequested = appSettingsManager.muteOnGameLaunch.value
        if (muteRequested && !gameMuteCoordinator.mute(plan.clientType)) {
            _effects.send(UiEffect.toast(R.string.bg_toast_mute_failed))
        }

        val result = compositionService.start(
            tasks = plan.params,
            clientType = plan.clientType,
            preflightLogs = plan.logs,
        )
        if (result is MaaCompositionService.StartResult.Success) {
            // 启动成功
        }

        val message = application.resolveTaskStartFailureMessage(result)
        if (message != null) {
            Timber.w("Start failed: %s", message.resolve(application))
            return message
        }
        return null
    }

    fun onStopTasks() {
        viewModelScope.launch {
            if (runningFlowEngine != null) {
                runningFlowEngine?.stop()
                compositionService.stopVirtualDisplay()
                currentFlowAppPackage?.let { pkg ->
                    runCatching { RemoteServiceManager.getInstanceOrNull()?.forceStopApp(pkg) }
                    Timber.i("Force stopped app after stop: %s", pkg)
                }
                // 强制回空闲：保证按钮回弹/预览占位（即使引擎协程卡在节点上）
                compositionService.reportRunState(com.yuanqian.autofarm.domain.state.MaaExecutionState.IDLE)
                _effects.send(UiEffect.toast("任务已停止"))
            } else {
                compositionService.stop()
            }
        }
    }

    /** 快捷操作：关闭应用（杀掉当前流程启动的应用 + 关虚拟屏）。 */
    fun onCloseGame() {
        viewModelScope.launch {
            val pkg = currentFlowAppPackage
            if (pkg != null) {
                runCatching { RemoteServiceManager.getInstanceOrNull()?.forceStopApp(pkg) }
            }
            compositionService.stopVirtualDisplay()
            runningFlowEngine?.stop()
            compositionService.reportRunState(com.yuanqian.autofarm.domain.state.MaaExecutionState.IDLE)
            _effects.send(UiEffect.toast("应用已关闭"))
            Timber.i("onCloseGame done (pkg=%s)", pkg)
        }
    }

    fun onClearLogs() {
        sessionLogger.clearRuntimeLogs()
    }

    fun onToggleGameSound() {
        viewModelScope.launch {
            val pkg = currentFlowAppPackage
            val ok = if (pkg != null) {
                // 工坊流程模式：按当前应用包名静音
                gameMuteCoordinator.mutePackage(pkg, !gameMuteCoordinator.isMuted.value)
            } else {
                gameMuteCoordinator.toggle(chainState.clientType)
            }
            if (!ok) {
                _effects.send(UiEffect.toast(R.string.bg_toast_mute_failed))
            }
        }
    }

    /**
     * 调试用：请求远端进程抓取当前帧缓冲并保存 PNG 到 {rootDir}/debug/screenshots，
     * 结果通过 [screenshotMessage] 反馈给 UI。
     *
     * 由远端（shell 进程）直接落盘——它对 userDir/debug 有写权限（同 logcat 抓取），
     * 避免跨进程读取 ashmem 被 SELinux 拒绝。
     */
    fun onCaptureDebugScreenshot() {
        viewModelScope.launch(Dispatchers.IO) {
            val savedName = runCatching {
                RemoteServiceManager.getInstanceOrNull()
                    ?.captureFramePng(pathConfig.debugScreenshotsDir)
                    ?.let { File(it).name }
            }.onFailure { Timber.e(it, "captureDebugScreenshot failed") }
                .getOrNull()
            val message = savedName
                ?.let { application.getString(R.string.bg_toast_screenshot_saved, it) }
                ?: application.getString(R.string.bg_toast_screenshot_failed)
            _screenshotMessage.tryEmit(message)
        }
    }

    private fun showStartFailedDialog(message: UiText) {
        showDialog(application.createStartFailedDialog(message))
    }

    // ==================== Dialog ====================

    private fun showDialog(dialog: PanelDialogUiState) {
        _state.update { it.copy(dialog = dialog) }
    }

    fun onDialogDismiss() {
        pendingStart = null
        _state.update { it.copy(dialog = null) }
    }

    fun onDialogConfirm() {
        when (state.value.dialog?.confirmAction) {
            PanelDialogConfirmAction.DISMISS_ONLY -> {
                onDialogDismiss()
            }

            PanelDialogConfirmAction.CONFIRM_PENDING_START -> {
                val pending = pendingStart
                _state.update { it.copy(dialog = null) }
                pendingStart = null
                if (pending != null) {
                    viewModelScope.launch {
                        val message = startTasksInternal(context = pending.context)
                        if (message != null && state.value.dialog == null) {
                            showStartFailedDialog(message)
                        }
                    }
                }
            }

            PanelDialogConfirmAction.GO_LOG -> {
                onTabChange(PanelTab.LOG)
                onDialogDismiss()
            }

            PanelDialogConfirmAction.GO_LOG_AND_STOP -> {
                onTabChange(PanelTab.LOG)
                onDialogDismiss()
                viewModelScope.launch {
                    compositionService.stop()
                }
            }

            null -> Unit
        }
    }

    override fun onCleared() {
        touchPreviewController.onClear()
        super.onCleared()
    }
}
