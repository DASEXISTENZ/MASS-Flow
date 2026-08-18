package com.mas.autofarm.domain.launch

import android.content.Context
import android.content.Intent
import com.mas.autofarm.R
import com.mas.autofarm.constant.Packages
import com.mas.autofarm.manager.RemoteServiceManager
import com.mas.autofarm.data.model.TaskChainNode
import com.mas.autofarm.data.preferences.AppSettingsManager
import com.mas.autofarm.domain.service.GameMuteCoordinator
import com.mas.autofarm.domain.service.MaaCompositionService
import com.mas.autofarm.domain.service.MaaSessionLogger
import com.mas.autofarm.domain.service.resolveStartResultMessage
import com.mas.autofarm.domain.usecase.PrepareTaskStartUseCase
import com.mas.autofarm.domain.usecase.TaskStartContext
import com.mas.autofarm.domain.usecase.TaskStartDecision
import com.mas.autofarm.domain.usecase.TaskStartMode
import com.mas.autofarm.schedule.model.ExecutionResult
import com.mas.autofarm.utils.i18n.UiText
import java.io.File
import kotlinx.coroutines.delay
import timber.log.Timber
import com.mas.autofarm.utils.i18n.uiTextOf

/**
 * 任务链启动尾部：prepare + mute + composition.start + achievement + 可选 schedule 会话日志
 * 手动与自动化共用；SCHEDULED 不会产生 RequiresConfirmation
 */
class StartTaskChainUseCase(
    private val prepare: PrepareTaskStartUseCase,
    private val composition: MaaCompositionService,
    private val muteCoordinator: GameMuteCoordinator,
    private val sessionLogger: MaaSessionLogger,
    private val appSettingsManager: AppSettingsManager,
    private val appContext: Context,
) {
    sealed interface Result {
        data object Success : Result
        data class Failed(
            val executionResult: ExecutionResult,
            val message: UiText,
        ) : Result
    }

    /** 启动被拦截时也落一个会话文件，让历史日志页能看到失败原因 */
    private suspend fun recordFailure(message: String) {
        runCatching {
            sessionLogger.startSession(listOf("startup"))
            sessionLogger.append(message, com.mas.autofarm.data.model.LogLevel.ERROR)
            sessionLogger.endSessionAndWait("BLOCKED")
        }
    }

    /** 运行本地工坊流程（FlowEngine） */
    private suspend fun runLocalFlow(name: String): Result {
        val dir = File(appContext.filesDir, "workshop/$name")
        val projFile = File(dir, "project.json")
        if (!projFile.exists()) {
            return Result.Failed(
                executionResult = ExecutionResult.FAILED_VALIDATION,
                message = com.mas.autofarm.utils.i18n.uiTextDynamic(
                    "本地流程不存在：$name（请先在脚本工坊创建并保存）"
                ),
            )
        }
        return runCatching {
            val project = kotlinx.serialization.json.Json.decodeFromString(
                com.mas.autofarm.presentation.view.workshop.FlowProject.serializer(), projFile.readText()
            )
            val templates = com.mas.autofarm.presentation.view.workshop.TemplateStore.listAll(appContext)
            val remote = when (val st = com.mas.autofarm.manager.RemoteServiceManager.state.value) {
                is com.mas.autofarm.manager.RemoteServiceManager.ServiceState.Connected -> st.service
                else -> null
            }
            if (remote == null) {
                return Result.Failed(
                    executionResult = ExecutionResult.FAILED_VALIDATION,
                    message = com.mas.autofarm.utils.i18n.uiTextDynamic("服务未连接，无法运行本地流程"),
                )
            }
            val engine = com.mas.autofarm.presentation.view.workshop.FlowEngine(
                context = appContext,
                remote = remote,
                onLog = { msg -> sessionLogger.append(msg, com.mas.autofarm.data.model.LogLevel.INFO) },
            )
            // 本地流程也开会话：让历史日志页能看到工坊流程记录
            sessionLogger.startSession(listOf("flow:$name"))
            try {
                engine.run(project, templates)
            } finally {
                sessionLogger.endSessionAndWait(
                    if (engine.state == com.mas.autofarm.presentation.view.workshop.FlowEngine.EngineState.ERROR) "FLOW_ERROR" else "COMPLETED"
                )
            }
            if (engine.state == com.mas.autofarm.presentation.view.workshop.FlowEngine.EngineState.ERROR) {
                Result.Failed(
                    executionResult = ExecutionResult.FAILED_START,
                    message = com.mas.autofarm.utils.i18n.uiTextDynamic("本地流程执行出错"),
                )
            } else {
                Result.Success
            }
        }.getOrElse { e ->
            Result.Failed(
                executionResult = ExecutionResult.FAILED_START,
                message = com.mas.autofarm.utils.i18n.uiTextDynamic("本地流程执行异常：${e.message}"),
            )
        }
    }

    suspend operator fun invoke(
        chain: List<TaskChainNode>,
        context: TaskStartContext,
        scheduleLabel: String? = null,
    ): Result {
        // 本地工坊流程：直接用 FlowEngine 运行（流程信息节点负责启动应用到虚拟屏，即"开始唤醒"）
        timber.log.Timber.i("StartTaskChain: nodes=${chain.size}, types=${chain.map { it.config::class.simpleName }}")
        val localFlowName = chain
            .mapNotNull { it.config as? com.mas.autofarm.data.model.CustomFlowConfig }
            .firstOrNull { it.localFlowName.isNotBlank() }
            ?.localFlowName
        timber.log.Timber.i("StartTaskChain: localFlowName=$localFlowName")
        if (localFlowName != null) {
            return runLocalFlow(localFlowName)
        }
        val plan = when (val decision = prepare(chain, context)) {
            is TaskStartDecision.Ready -> decision.plan
            is TaskStartDecision.Blocked -> {
                recordFailure("任务启动被拦截：${decision.reason.name}（clientTypes=${decision.clientTypes}）")
                return Result.Failed(
                    executionResult = ExecutionResult.FAILED_VALIDATION,
                    message = uiTextOf(
                        R.string.schedule_log_task_blocked,
                        decision.reason.name,
                    ),
                )
            }
            is TaskStartDecision.RequiresConfirmation -> {
                recordFailure("任务需要确认后才能启动（${decision.acknowledgement}）")
                return Result.Failed(
                    executionResult = ExecutionResult.FAILED_VALIDATION,
                    message = uiTextOf(R.string.schedule_log_task_needs_confirmation),
                )
            }
        }

        if (appSettingsManager.muteOnGameLaunch.value) {
            muteCoordinator.mute(plan.clientType)
        }

        val startResult = composition.start(
            tasks = plan.params,
            clientType = plan.clientType,
            preflightLogs = plan.logs,
        ) {
            if (scheduleLabel != null) {
                sessionLogger.appendAndWait(
                    appContext.getString(
                        R.string.task_start_triggered_by_schedule,
                        scheduleLabel,
                    ),
                )
            }
        }

        return when (startResult) {
            is MaaCompositionService.StartResult.Success -> {
                Result.Success
            }
            else -> Result.Failed(
                executionResult = ExecutionResult.FAILED_START,
                message = resolveStartResultMessage(startResult)
                    ?: uiTextOf(R.string.task_start_error_start_failed),
            )
        }
    }
}
