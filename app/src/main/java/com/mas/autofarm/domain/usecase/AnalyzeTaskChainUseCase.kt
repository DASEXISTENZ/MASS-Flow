package com.mas.autofarm.domain.usecase

import com.mas.autofarm.constant.Packages
import com.mas.autofarm.data.model.CollectingPreflightLogSink
import com.mas.autofarm.data.model.LogLevel
import com.mas.autofarm.data.model.TaskChainNode
import com.mas.autofarm.data.model.TaskParamContext
import com.mas.autofarm.data.model.WakeUpConfig
import com.mas.autofarm.data.preferences.TaskChainState
import com.mas.autofarm.data.repository.DepotRepository
import com.mas.autofarm.data.repository.OperBoxRepository
import com.mas.autofarm.data.resource.ActivityManager
import com.mas.autofarm.data.resource.ItemHelper
import com.mas.autofarm.data.resource.ResourceDataManager
import com.mas.autofarm.data.resource.ServerTimezone
import com.mas.autofarm.domain.service.FightDropsRefresher
import com.mas.autofarm.maa.task.MaaTaskParams
import com.mas.autofarm.maa.task.MaaTaskType
import com.mas.autofarm.maa.task.TaskSlot
import com.mas.autofarm.utils.i18n.UiText
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.DayOfWeek

class AnalyzeTaskChainUseCase(
    private val taskChainState: TaskChainState,
    private val resourceDataManager: ResourceDataManager,
    private val activityManager: ActivityManager,
    private val depotRepository: DepotRepository,
    private val operBoxRepository: OperBoxRepository,
    private val itemHelper: ItemHelper,
    private val dropsRefresher: FightDropsRefresher,
) {
    /** 先等 depot/operBox 分片装载；config 的 toTaskParams 仍是非 suspend。 */
    suspend operator fun invoke(chain: List<TaskChainNode>): AnalyzeTaskChainResult {
        depotRepository.isLoaded.first { it }
        operBoxRepository.isLoaded.first { it }

        val nodes = chain.filter { it.enabled }.sortedBy { it.order }
        if (nodes.isEmpty()) {
            return AnalyzeTaskChainResult.Blocked(
                reason = AnalyzeTaskChainFailureReason.NO_TASK_SELECTED,
            )
        }
        val list = getWakeUpClientTypeList(nodes)
        if (list.size > 1) {
            return AnalyzeTaskChainResult.Blocked(
                reason = AnalyzeTaskChainFailureReason.CONFLICTING_CLIENT_TYPES,
                clientTypes = list,
            )
        }

        dropsRefresher.clear()

        val clientType = taskChainState.clientType
        val log = CollectingPreflightLogSink()

        val expanded = nodes.flatMap { node ->
            val ctx = TaskParamContext(
                node = node,
                clientType = clientType,
                itemHelper = itemHelper,
                activityManager = activityManager,
                depotRepository = depotRepository,
                operBoxRepository = operBoxRepository,
                resourceDataManager = resourceDataManager,
                dropsRefresher = dropsRefresher,
                logSink = log,
            )
            node.config.toTaskParams(ctx).mapIndexed { index, task ->
                task.copy(slot = TaskSlot(node.id, index))
            }
        }
        val params = dropAdjacentDuplicateDepot(expanded)
        val logs = log.entries

        if (params.isEmpty()) {
            return AnalyzeTaskChainResult.Blocked(
                reason = AnalyzeTaskChainFailureReason.NO_EXECUTABLE_TASKS,
                logs = logs,
            )
        }

        return AnalyzeTaskChainResult.Ready(
            TaskChainPlan(
                nodes = nodes,
                params = params,
                clientType = clientType,
                gamePackageName = Packages[clientType],
                launchesGame = nodes
                    .mapNotNull { it.config as? WakeUpConfig }
                    .any { it.startGameEnabled },
                logs = logs,
            )
        )
    }

    /** 去掉相邻重复 DEPOT；中间有其它任务则保留（库存可能已变）。 */
    private fun dropAdjacentDuplicateDepot(params: List<MaaTaskParams>): List<MaaTaskParams> =
        params.filterIndexed { index, task ->
            index == 0 ||
                    task.type != MaaTaskType.DEPOT ||
                    params[index - 1].type != MaaTaskType.DEPOT
        }

    private fun getWakeUpClientTypeList(nodes: List<TaskChainNode>): List<String> {
        return nodes.mapNotNull { (it.config as? WakeUpConfig)?.clientType }
            .distinct()
    }



}

data class TaskChainPlan(
    val nodes: List<TaskChainNode>,
    val params: List<MaaTaskParams>,
    val clientType: String,
    val gamePackageName: String?,
    val launchesGame: Boolean,
    val gameAliveBeforeStart: Boolean? = null,
    /** 预检日志，会话开始后由 Composition 回放。 */
    val logs: List<Pair<UiText, LogLevel>> = emptyList(),
)

enum class AnalyzeTaskChainFailureReason {
    NO_TASK_SELECTED,
    CONFLICTING_CLIENT_TYPES,
    NO_EXECUTABLE_TASKS,
}

sealed interface AnalyzeTaskChainResult {
    data class Ready(val plan: TaskChainPlan) : AnalyzeTaskChainResult

    data class Blocked(
        val reason: AnalyzeTaskChainFailureReason,
        val clientTypes: List<String> = emptyList(),
        val logs: List<Pair<UiText, LogLevel>> = emptyList(),
    ) : AnalyzeTaskChainResult
}
